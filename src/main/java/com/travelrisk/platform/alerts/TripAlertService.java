package com.travelrisk.platform.alerts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.database.entities.TripNotification;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.repository.UserProfileRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sends a saved trip's risk-change alert to the places people already look (email, Slack), on top
 * of the in-app notification. Only a change of level counts (for example Medium to High); a few
 * points inside the same level stays in the app.
 *
 * <p>Email goes to {@code TRIP_ALERT_EMAIL_TO} when it is set (one inbox for a demo or a team),
 * otherwise to the address Google sign-in gave for the trip's owner. Demo employee accounts have
 * no address, so without the override they never get mail.
 *
 * <p>Sending happens on a background thread after the database transaction commits, so a slow or
 * failing mail server never delays or breaks a trip check.
 */
@Service
public class TripAlertService {
  private static final Logger log = LoggerFactory.getLogger(TripAlertService.class);
  static final Duration RESEND_COOLDOWN = Duration.ofMinutes(2);

  private final List<TripAlertChannel> channels;
  private final UserProfileRepository profiles;
  private final ObjectMapper objectMapper;
  private final String appUrl;
  private final String emailOverride;
  private final Executor executor;
  private final Clock clock;
  private final Map<Long, Instant> lastResend = new ConcurrentHashMap<>();

  @Autowired
  public TripAlertService(
      List<TripAlertChannel> channels,
      UserProfileRepository profiles,
      ObjectMapper objectMapper,
      @Value("${travel-risk.trip-alerts.app-url:}") String appUrl,
      @Value("${travel-risk.trip-alerts.email.to:}") String emailOverride) {
    this(channels, profiles, objectMapper, appUrl, emailOverride, backgroundExecutor(), Clock.systemUTC());
  }

  TripAlertService(
      List<TripAlertChannel> channels,
      UserProfileRepository profiles,
      ObjectMapper objectMapper,
      String appUrl,
      String emailOverride,
      Executor executor,
      Clock clock) {
    this.channels = channels;
    this.profiles = profiles;
    this.objectMapper = objectMapper;
    this.appUrl = appUrl == null ? "" : appUrl.trim().replaceAll("/+$", "");
    this.emailOverride = emailOverride == null ? "" : emailOverride.trim();
    this.executor = executor;
    this.clock = clock;
  }

  /** Names of the outside channels that are switched on, e.g. ["email", "Slack"]. */
  public List<String> configuredChannels() {
    return channels.stream().filter(TripAlertChannel::configured).map(TripAlertChannel::name).toList();
  }

  /** Called by a re-check right after it saved {@code notification}: sends it out if the level changed. */
  public void onRiskChange(TripNotification notification, SavedTrip trip) {
    if (!TripAlert.isMaterial(notification.getPreviousRiskLevel(), notification.getCurrentRiskLevel())) {
      return;
    }
    dispatch(build(notification, trip));
  }

  /**
   * Sends an existing in-app alert out again on request ("Send to email"). Returns the channels it
   * went to. Repeats for the same alert are refused for a short cooldown so a button can't spam an inbox.
   */
  public List<String> resend(TripNotification notification, SavedTrip trip) {
    TripAlert alert = build(notification, trip);
    List<String> targets = targets(alert).stream().map(TripAlertChannel::name).toList();
    if (targets.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "No alert channel is set up. Add email or Slack settings to send alerts outside the app.");
    }
    Instant now = clock.instant();
    Instant previous = lastResend.get(notification.getId());
    if (previous != null && previous.plus(RESEND_COOLDOWN).isAfter(now)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "This alert was just sent. Try again in a couple of minutes.");
    }
    lastResend.put(notification.getId(), now);
    dispatch(alert);
    return targets;
  }

  private TripAlert build(TripNotification notification, SavedTrip trip) {
    String mode = trip == null ? "flight" : trip.getMode();
    String reason = trip == null ? null : mainReason(trip.getAssessmentJson());
    return TripAlert.from(notification, mode, reason, appUrl, recipient(notification.getUsername()));
  }

  private List<TripAlertChannel> targets(TripAlert alert) {
    return channels.stream().filter(channel -> channel.accepts(alert)).toList();
  }

  private void dispatch(TripAlert alert) {
    List<TripAlertChannel> targets = targets(alert);
    if (targets.isEmpty()) {
      return;
    }
    Runnable send = () -> executor.execute(() -> {
      for (TripAlertChannel channel : targets) {
        try {
          channel.send(alert);
          log.info("trip_alert_sent channel={} route=\"{}\" change=\"{}\"", channel.name(), alert.route(), alert.change());
        } catch (RuntimeException error) {
          log.warn("trip_alert_failed channel={} route=\"{}\" error={}", channel.name(), alert.route(), error.toString());
        }
      }
    });
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          send.run();
        }
      });
    } else {
      send.run();
    }
  }

  private String recipient(String username) {
    if (!emailOverride.isBlank()) {
      return emailOverride;
    }
    return profiles.findById(username).map(UserProfile::getEmail).orElse(null);
  }

  /** The highest-scoring signal's message, e.g. "Destination FAA airport status has FAA NAS ground stop at MCO." */
  String mainReason(String assessmentJson) {
    try {
      Map<String, Object> assessment = objectMapper.readValue(assessmentJson, new TypeReference<>() {});
      if (!(assessment.get("signals") instanceof List<?> signals)) {
        return null;
      }
      return signals.stream()
          .filter(Map.class::isInstance)
          .map(signal -> (Map<?, ?>) signal)
          .filter(signal -> signal.get("message") instanceof String)
          .max(Comparator.comparingInt(signal -> signal.get("points") instanceof Number points ? points.intValue() : 0))
          .map(signal -> (String) signal.get("message"))
          .orElse(null);
    } catch (Exception error) {
      return null;
    }
  }

  private static Executor backgroundExecutor() {
    // One daemon thread: alerts go out in order and never hold up a request or shutdown.
    return Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "trip-alerts");
      thread.setDaemon(true);
      return thread;
    });
  }
}
