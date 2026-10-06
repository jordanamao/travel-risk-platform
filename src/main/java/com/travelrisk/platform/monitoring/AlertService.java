package com.travelrisk.platform.monitoring;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Raises operational alerts. Every alert is logged on one greppable line ({@code ALERT ...} or
 * {@code ALERT_RESOLVED ...}) so a log drain can page on it. When a webhook URL is configured the
 * same text is also posted as {@code {"text": "..."}}, which Slack incoming webhooks accept.
 * Repeats of an open alert are suppressed for the cooldown so a flapping source can't spam.
 */
@Service
public class AlertService {
  private static final Logger log = LoggerFactory.getLogger(AlertService.class);

  private final String webhookUrl;
  private final Duration cooldown;
  private final AlertNotifier notifier;
  private final Clock clock;
  private final Map<String, Instant> openAlerts = new ConcurrentHashMap<>();

  @Autowired
  public AlertService(
      RestClient.Builder restClientBuilder,
      @Value("${travel-risk.alerts.webhook-url:}") String webhookUrl,
      @Value("${travel-risk.alerts.cooldown:15m}") String cooldown) {
    this(webhookUrl, DurationStyle.detectAndParse(cooldown), webhookNotifier(restClientBuilder.build()), Clock.systemUTC());
  }

  AlertService(String webhookUrl, Duration cooldown, AlertNotifier notifier, Clock clock) {
    this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    this.cooldown = cooldown;
    this.notifier = notifier;
    this.clock = clock;
  }

  /** Opens (or repeats, once per cooldown) the alert identified by {@code key}. */
  public void raise(String key, String message) {
    Instant now = clock.instant();
    Instant lastSent = openAlerts.get(key);
    if (lastSent != null && lastSent.plus(cooldown).isAfter(now)) {
      return;
    }
    openAlerts.put(key, now);
    log.error("ALERT key={} message=\"{}\"", key, message);
    send("Travel Risk alert: " + message);
  }

  /** Closes the alert identified by {@code key}, if it was open. */
  public void resolve(String key, String message) {
    if (openAlerts.remove(key) == null) {
      return;
    }
    log.warn("ALERT_RESOLVED key={} message=\"{}\"", key, message);
    send("Travel Risk resolved: " + message);
  }

  public boolean isOpen(String key) {
    return openAlerts.containsKey(key);
  }

  public boolean webhookEnabled() {
    return !webhookUrl.isBlank();
  }

  private void send(String text) {
    if (!webhookEnabled()) {
      return;
    }
    try {
      notifier.send(webhookUrl, text);
    } catch (RuntimeException error) {
      log.warn("alert_webhook_failed error={}", error.toString());
    }
  }

  private static AlertNotifier webhookNotifier(RestClient restClient) {
    // One background thread: a slow webhook never delays a trip check.
    ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "alert-webhook");
      thread.setDaemon(true);
      return thread;
    });
    return (url, text) -> executor.execute(() -> {
      try {
        restClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("text", text))
            .retrieve()
            .toBodilessEntity();
      } catch (RuntimeException error) {
        log.warn("alert_webhook_failed error={}", error.toString());
      }
    });
  }

  @FunctionalInterface
  interface AlertNotifier {
    void send(String webhookUrl, String text);
  }
}
