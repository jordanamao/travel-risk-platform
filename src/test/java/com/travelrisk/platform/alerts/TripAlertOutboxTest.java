package com.travelrisk.platform.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.database.entities.AlertOutboxEntry;
import com.travelrisk.platform.database.entities.AlertOutboxEntry.Status;
import com.travelrisk.platform.repository.AlertOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The outbox against a real (H2) database: atomic with the caller's transaction, retried, claimed once. */
@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:travelrisk-alert-outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        // Keep the app's own relay out of the way; these tests drive their own.
        "travel-risk.alert-outbox.poll-interval=1h"
    })
class TripAlertOutboxTest {
  @Autowired
  private AlertOutboxRepository repository;

  @Autowired
  private PlatformTransactionManager transactionManager;

  @Autowired
  private ObjectMapper objectMapper;

  private final MutableClock clock = new MutableClock();
  private final List<Runnable> scheduled = new ArrayList<>();
  private final RecordingChannel email = new RecordingChannel("email");
  private final RecordingChannel slack = new RecordingChannel("Slack");
  private TripAlertOutbox outbox;

  @BeforeEach
  void setUp() {
    repository.deleteAll();
    outbox = new TripAlertOutbox(repository, List.of(email, slack), objectMapper, transactionManager, scheduled::add, clock);
  }

  @Test
  void rowsCommitWithTheCallersTransactionAndTheRelaySendsThemAfterwards() {
    inTransaction(() -> outbox.enqueue(41L, TripAlertQueue.Kind.CHANGE, alert(), List.of(email, slack)));

    assertThat(repository.findByNotificationIdOrderByIdAsc(41L))
        .extracting(AlertOutboxEntry::getChannel, AlertOutboxEntry::getStatus)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("email", Status.PENDING),
            org.assertj.core.groups.Tuple.tuple("Slack", Status.PENDING));
    assertThat(scheduled).hasSize(1);

    scheduled.get(0).run();

    assertThat(email.sent).extracting(TripAlert::route).containsExactly("Dallas, TX to Orlando, FL");
    assertThat(slack.sent).hasSize(1);
    assertThat(repository.findByNotificationIdOrderByIdAsc(41L)).allSatisfy(row -> {
      assertThat(row.getStatus()).isEqualTo(Status.SENT);
      assertThat(row.getSentAt()).isNotNull();
    });
  }

  @Test
  void aRolledBackTransactionLeavesNoAlertBehind() {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      outbox.enqueue(42L, TripAlertQueue.Kind.CHANGE, alert(), List.of(email));
      status.setRollbackOnly();
    });

    assertThat(repository.count()).isZero();
    assertThat(scheduled).isEmpty();
    assertThat(outbox.drain()).isZero();
    assertThat(email.sent).isEmpty();
  }

  @Test
  void theSameChangeIsQueuedOnlyOncePerChannel() {
    inTransaction(() -> outbox.enqueue(43L, TripAlertQueue.Kind.CHANGE, alert(), List.of(email)));
    inTransaction(() -> outbox.enqueue(43L, TripAlertQueue.Kind.CHANGE, alert(), List.of(email)));

    assertThat(repository.findByNotificationIdOrderByIdAsc(43L)).hasSize(1);
    outbox.drain();
    assertThat(email.sent).hasSize(1);
  }

  @Test
  void aFailedSendIsRetriedWithBackoffWithoutHoldingUpOtherChannels() {
    email.failuresLeft = 1;
    inTransaction(() -> outbox.enqueue(44L, TripAlertQueue.Kind.CHANGE, alert(), List.of(email, slack)));

    outbox.drain();

    AlertOutboxEntry emailRow = row(44L, "email");
    assertThat(emailRow.getStatus()).isEqualTo(Status.PENDING);
    assertThat(emailRow.getAttempts()).isEqualTo(1);
    assertThat(emailRow.getLastError()).contains("mail server down");
    assertThat(emailRow.getNextAttemptAt()).isEqualTo(clock.instant().plus(TripAlertOutbox.FIRST_RETRY));
    assertThat(row(44L, "Slack").getStatus()).isEqualTo(Status.SENT);

    // Not due yet: nothing happens.
    assertThat(outbox.drain()).isZero();

    clock.advance(TripAlertOutbox.FIRST_RETRY);
    assertThat(outbox.drain()).isEqualTo(1);
    assertThat(row(44L, "email").getStatus()).isEqualTo(Status.SENT);
    assertThat(email.sent).hasSize(1);
  }

  @Test
  void afterTheLastAttemptTheRowIsParkedAsFailed() {
    email.failuresLeft = Integer.MAX_VALUE;
    inTransaction(() -> outbox.enqueue(45L, TripAlertQueue.Kind.CHANGE, alert(), List.of(email)));

    for (int attempt = 1; attempt <= TripAlertOutbox.MAX_ATTEMPTS; attempt++) {
      outbox.drain();
      clock.advance(TripAlertOutbox.MAX_RETRY);
    }

    AlertOutboxEntry row = row(45L, "email");
    assertThat(row.getStatus()).isEqualTo(Status.FAILED);
    assertThat(row.getAttempts()).isEqualTo(TripAlertOutbox.MAX_ATTEMPTS);
    assertThat(outbox.drain()).isZero();
  }

  @Test
  void onlyOneWorkerCanClaimARowAndADeadWorkersRowIsTakenBackAfterItsLease() {
    inTransaction(() -> outbox.enqueue(46L, TripAlertQueue.Kind.CHANGE, alert(), List.of(email)));
    Long id = row(46L, "email").getId();
    Instant now = clock.instant();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    // Two instances race for the same row: only the first conditional update matches.
    Integer first = tx.execute(status -> repository.claim(id, now, now.plus(TripAlertOutbox.LEASE)));
    Integer second = tx.execute(status -> repository.claim(id, now, now.plus(TripAlertOutbox.LEASE)));
    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();

    // The worker that took it "crashed" before sending. Nothing is due until the lease runs out ...
    assertThat(outbox.drain()).isZero();
    clock.advance(TripAlertOutbox.LEASE);
    // ... then another worker takes it over and sends it.
    assertThat(outbox.drain()).isEqualTo(1);
    assertThat(email.sent).hasSize(1);
  }

  @Test
  void backoffDoublesAndIsCapped() {
    assertThat(TripAlertOutbox.backoff(1)).isEqualTo(Duration.ofSeconds(30));
    assertThat(TripAlertOutbox.backoff(2)).isEqualTo(Duration.ofMinutes(1));
    assertThat(TripAlertOutbox.backoff(4)).isEqualTo(Duration.ofMinutes(4));
    assertThat(TripAlertOutbox.backoff(30)).isEqualTo(TripAlertOutbox.MAX_RETRY);
  }

  private AlertOutboxEntry row(Long notificationId, String channel) {
    return repository.findByNotificationIdOrderByIdAsc(notificationId).stream()
        .filter(row -> row.getChannel().equals(channel))
        .findFirst()
        .orElseThrow();
  }

  private void inTransaction(Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
  }

  private static TripAlert alert() {
    return new TripAlert("employee1@email.com", "Dallas, TX", "Orlando, FL", LocalDate.parse("2026-10-09"), "flight",
        "Medium", "High", 5, 12, "Ground stop at MCO.", "https://travel-risk.example.test", "me@example.test");
  }

  private static final class RecordingChannel implements TripAlertChannel {
    private final String name;
    final List<TripAlert> sent = new CopyOnWriteArrayList<>();
    int failuresLeft;

    RecordingChannel(String name) {
      this.name = name;
    }

    public String name() { return name; }
    public boolean configured() { return true; }

    public void send(TripAlert alert) {
      if (failuresLeft > 0) {
        failuresLeft--;
        throw new IllegalStateException("mail server down");
      }
      sent.add(alert);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-06T12:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
