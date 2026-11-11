package com.travelrisk.platform.alerts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.database.entities.AlertOutboxEntry;
import com.travelrisk.platform.repository.AlertOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox for risk-change alerts.
 *
 * <p>An alert touches two systems that can't share a transaction: our Postgres database (the
 * in-app notification) and an outside service (the email API or a Slack webhook). Writing to one and
 * then calling the other can leave them disagreeing: the app crashes after the commit and the email
 * never goes out, or the email goes out for a change that was rolled back.
 *
 * <p>So {@link #enqueue} only writes outbox rows, in the same database transaction as the
 * notification. Either both commit or neither does. After the commit a relay ({@link #drain}) sends
 * each row and marks it SENT; a failed send is retried with exponential backoff and, after
 * {@link #MAX_ATTEMPTS}, parked as FAILED for someone to look at. A scheduled poll picks up anything
 * left behind by a restart.
 *
 * <p>Safe with several app instances: a worker takes a row with one conditional UPDATE, so only one
 * instance sends it, and a row whose worker died is taken again once its lease runs out. Delivery is
 * at least once: a crash between the send and the "mark SENT" can repeat a message, never lose one.
 */
@Component
public class TripAlertOutbox implements TripAlertQueue {
  private static final Logger log = LoggerFactory.getLogger(TripAlertOutbox.class);
  static final int MAX_ATTEMPTS = 6;
  static final Duration FIRST_RETRY = Duration.ofSeconds(30);
  static final Duration MAX_RETRY = Duration.ofHours(1);
  static final Duration LEASE = Duration.ofMinutes(2);
  private static final int BATCH = 25;

  private final AlertOutboxRepository repository;
  private final Map<String, TripAlertChannel> channels;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate newTransaction;
  private final Executor executor;
  private final Clock clock;
  private final AtomicBoolean draining = new AtomicBoolean();
  private final AtomicBoolean drainAgain = new AtomicBoolean();

  @Autowired
  public TripAlertOutbox(
      AlertOutboxRepository repository,
      List<TripAlertChannel> channels,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager) {
    this(repository, channels, objectMapper, transactionManager, backgroundExecutor(), Clock.systemUTC());
  }

  TripAlertOutbox(
      AlertOutboxRepository repository,
      List<TripAlertChannel> channels,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager,
      Executor executor,
      Clock clock) {
    this.repository = repository;
    this.channels = channels.stream()
        .collect(Collectors.toMap(TripAlertChannel::name, Function.identity(), (first, second) -> first));
    this.objectMapper = objectMapper;
    this.newTransaction = new TransactionTemplate(transactionManager);
    this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.executor = executor;
    this.clock = clock;
  }

  /**
   * Writes one outbox row per channel inside the caller's transaction (or a new one if there is
   * none), then asks the relay to send them once that transaction has committed.
   */
  @Override
  public void enqueue(Long notificationId, Kind kind, TripAlert alert, List<TripAlertChannel> targets) {
    if (targets.isEmpty()) {
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      write(notificationId, kind, alert, targets);
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          drainSoon();
        }
      });
    } else {
      newTransaction.executeWithoutResult(status -> write(notificationId, kind, alert, targets));
      drainSoon();
    }
  }

  private void write(Long notificationId, Kind kind, TripAlert alert, List<TripAlertChannel> targets) {
    Instant now = clock.instant();
    String payload = payload(alert);
    for (TripAlertChannel channel : targets) {
      String key = dedupeKey(notificationId, kind, channel.name(), now);
      // The same level change on the same channel is queued at most once, even if a re-check repeats.
      if (!repository.existsByDedupeKey(key)) {
        repository.save(new AlertOutboxEntry(key, notificationId, channel.name(), payload, now));
      }
    }
  }

  static String dedupeKey(Long notificationId, Kind kind, String channel, Instant now) {
    return switch (kind) {
      case CHANGE -> "change:%s:%s".formatted(notificationId, channel);
      // A resend is a fresh request each time (the per-alert cooldown limits how often).
      case RESEND -> "resend:%s:%s:%d".formatted(notificationId, channel, now.toEpochMilli());
    };
  }

  /** Runs the relay on the background thread, without making the caller wait. */
  public void drainSoon() {
    executor.execute(this::drain);
  }

  /** Safety net: picks up rows left behind by a restart, a crash or a retry that came due. */
  @Scheduled(
      fixedDelayString = "${travel-risk.alert-outbox.poll-interval:30s}",
      initialDelayString = "${travel-risk.alert-outbox.poll-interval:30s}")
  public void poll() {
    drain();
  }

  /** Sends every due row this instance can claim. Returns how many were sent. */
  public int drain() {
    if (!draining.compareAndSet(false, true)) {
      drainAgain.set(true);
      return 0;
    }
    int sent = 0;
    try {
      do {
        drainAgain.set(false);
        List<Long> due;
        do {
          Instant now = clock.instant();
          due = repository.findDueIds(now, PageRequest.of(0, BATCH));
          for (Long id : due) {
            if (claim(id, now) && deliver(id)) {
              sent++;
            }
          }
        } while (due.size() == BATCH);
      } while (drainAgain.get());
    } finally {
      draining.set(false);
    }
    return sent;
  }

  private boolean claim(Long id, Instant now) {
    Integer claimed = newTransaction.execute(status -> repository.claim(id, now, now.plus(LEASE)));
    return claimed != null && claimed == 1;
  }

  private boolean deliver(Long id) {
    AlertOutboxEntry entry = repository.findById(id).orElse(null);
    if (entry == null) {
      return false;
    }
    try {
      TripAlertChannel channel = channels.get(entry.getChannel());
      if (channel == null || !channel.configured()) {
        throw new IllegalStateException("Channel " + entry.getChannel() + " is not configured");
      }
      TripAlert alert = objectMapper.readValue(entry.getPayload(), TripAlert.class);
      channel.send(alert);
      update(id, row -> row.markSent(clock.instant()));
      log.info("trip_alert_sent channel={} route=\"{}\" change=\"{}\" outboxId={}",
          channel.name(), alert.route(), alert.change(), id);
      return true;
    } catch (Exception error) {
      int attempt = entry.getAttempts() + 1;
      Instant retryAt = attempt >= MAX_ATTEMPTS ? null : clock.instant().plus(backoff(attempt));
      update(id, row -> row.markFailed(error.toString(), retryAt));
      if (retryAt == null) {
        log.error("trip_alert_gave_up channel={} outboxId={} attempts={} error={}",
            entry.getChannel(), id, attempt, error.toString());
      } else {
        log.warn("trip_alert_failed channel={} outboxId={} attempt={} retryAt={} error={}",
            entry.getChannel(), id, attempt, retryAt, error.toString());
      }
      return false;
    }
  }

  private void update(Long id, java.util.function.Consumer<AlertOutboxEntry> change) {
    newTransaction.executeWithoutResult(status -> repository.findById(id).ifPresent(row -> {
      change.accept(row);
      repository.save(row);
    }));
  }

  /** 30 s, 1 min, 2 min, 4 min ... capped at an hour. */
  static Duration backoff(int attempt) {
    Duration delay = FIRST_RETRY.multipliedBy(1L << Math.min(attempt - 1, 20));
    return delay.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : delay;
  }

  private String payload(TripAlert alert) {
    try {
      return objectMapper.writeValueAsString(alert);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Could not write alert to the outbox", error);
    }
  }

  private static Executor backgroundExecutor() {
    // One daemon thread: alerts go out in order and never hold up a request or shutdown.
    return Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "trip-alert-outbox");
      thread.setDaemon(true);
      return thread;
    });
  }
}
