package com.travelrisk.platform.monitoring;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Tracks the latest status of each external data source across trip checks and raises an alert
 * when a source keeps failing or is slow, then resolves it once the source recovers.
 */
@Service
public class SourceHealthMonitor {
  private static final Logger log = LoggerFactory.getLogger(SourceHealthMonitor.class);

  private final AlertService alertService;
  private final int failureThreshold;
  private final long slowSourceMs;
  private final Map<String, SourceHealth> health = new ConcurrentHashMap<>();

  public SourceHealthMonitor(
      AlertService alertService,
      @Value("${travel-risk.alerts.failure-threshold:2}") int failureThreshold,
      @Value("${travel-risk.alerts.slow-source-ms:5000}") long slowSourceMs) {
    this.alertService = alertService;
    this.failureThreshold = Math.max(1, failureThreshold);
    this.slowSourceMs = slowSourceMs;
  }

  public void record(List<SourceCheck> checks) {
    for (SourceCheck check : checks) {
      SourceHealth updated = health.merge(check.name(), SourceHealth.first(check), (previous, ignored) -> previous.next(check));
      if (check.unavailable()) {
        log.warn("source_unavailable source=\"{}\" durationMs={} consecutiveFailures={}",
            check.name(), check.durationMs(), updated.consecutiveFailures());
      } else if (SourceCheck.DEGRADED.equals(check.status())) {
        log.warn("source_degraded source=\"{}\" durationMs={}", check.name(), check.durationMs());
      }
      alertOnFailures(check, updated);
      alertOnSlowness(check);
    }
  }

  public List<SourceHealth> snapshot() {
    return health.values().stream().sorted(Comparator.comparing(SourceHealth::name)).toList();
  }

  private void alertOnFailures(SourceCheck check, SourceHealth updated) {
    String key = "source-down:" + check.name();
    if (updated.consecutiveFailures() >= failureThreshold) {
      alertService.raise(key, "%s has been unavailable for %s. Trip checks still complete but show it as unavailable."
          .formatted(check.name(), updated.consecutiveFailures() == 1 ? "the last check"
              : updated.consecutiveFailures() + " checks in a row"));
    } else if (!check.unavailable()) {
      alertService.resolve(key, check.name() + " is responding again.");
    }
  }

  private void alertOnSlowness(SourceCheck check) {
    if (SourceCheck.NOT_CONFIGURED.equals(check.status())) {
      return;
    }
    String key = "source-slow:" + check.name();
    if (check.durationMs() >= slowSourceMs) {
      alertService.raise(key, "%s took %d ms (alert threshold %d ms)."
          .formatted(check.name(), check.durationMs(), slowSourceMs));
    } else {
      alertService.resolve(key, "%s is back under %d ms.".formatted(check.name(), slowSourceMs));
    }
  }

  public record SourceHealth(
      String name,
      String status,
      long lastDurationMs,
      int consecutiveFailures,
      Instant lastCheckedAt,
      Instant lastFailureAt) {

    static SourceHealth first(SourceCheck check) {
      Instant now = Instant.now();
      return new SourceHealth(check.name(), check.status(), check.durationMs(),
          check.unavailable() ? 1 : 0, now, check.unavailable() ? now : null);
    }

    SourceHealth next(SourceCheck check) {
      Instant now = Instant.now();
      return new SourceHealth(name, check.status(), check.durationMs(),
          check.unavailable() ? consecutiveFailures + 1 : 0, now, check.unavailable() ? now : lastFailureAt);
    }
  }
}
