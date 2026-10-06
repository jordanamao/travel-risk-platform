package com.travelrisk.platform.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceHealthMonitorTest {
  private static final String FAA = "FAA NAS Status API";

  private final List<String> sent = new ArrayList<>();
  private final MutableClock clock = new MutableClock();
  private final AlertService alerts = new AlertService("https://hooks.example.test/alert", Duration.ofMinutes(15),
      (url, text) -> sent.add(text), clock);
  private final SourceHealthMonitor monitor = new SourceHealthMonitor(alerts, 2, 5000);

  @Test
  void alertsAfterRepeatedFailuresAndOnceUntilCooldown() {
    monitor.record(List.of(new SourceCheck(FAA, SourceCheck.UNAVAILABLE, 40)));
    assertThat(sent).isEmpty();

    monitor.record(List.of(new SourceCheck(FAA, SourceCheck.UNAVAILABLE, 40)));
    monitor.record(List.of(new SourceCheck(FAA, SourceCheck.UNAVAILABLE, 40)));
    assertThat(sent).containsExactly(
        "Travel Risk alert: FAA NAS Status API has been unavailable for 2 checks in a row. "
            + "Trip checks still complete but show it as unavailable.");

    clock.advance(Duration.ofMinutes(16));
    monitor.record(List.of(new SourceCheck(FAA, SourceCheck.UNAVAILABLE, 40)));
    assertThat(sent).hasSize(2);
    assertThat(monitor.snapshot().getFirst().consecutiveFailures()).isEqualTo(4);
  }

  @Test
  void resolvesOnceTheSourceRecovers() {
    monitor.record(List.of(new SourceCheck(FAA, SourceCheck.UNAVAILABLE, 40)));
    monitor.record(List.of(new SourceCheck(FAA, SourceCheck.UNAVAILABLE, 40)));
    monitor.record(List.of(new SourceCheck(FAA, SourceCheck.OK, 40)));
    monitor.record(List.of(new SourceCheck(FAA, SourceCheck.OK, 40)));

    assertThat(sent).last().isEqualTo("Travel Risk resolved: FAA NAS Status API is responding again.");
    assertThat(sent).hasSize(2);
    assertThat(alerts.isOpen("source-down:" + FAA)).isFalse();
    assertThat(monitor.snapshot().getFirst().status()).isEqualTo(SourceCheck.OK);
  }

  @Test
  void alertsOnSlowSourcesButNotUnconfiguredOnes() {
    monitor.record(List.of(
        new SourceCheck(FAA, SourceCheck.OK, 7200),
        new SourceCheck("Road511 Traffic Data API", SourceCheck.NOT_CONFIGURED, 9000)));

    assertThat(sent).containsExactly("Travel Risk alert: FAA NAS Status API took 7200 ms (alert threshold 5000 ms).");
  }

  @Test
  void logsOnlyWhenNoWebhookIsConfigured() {
    AlertService logOnly = new AlertService("", Duration.ofMinutes(15), (url, text) -> sent.add(text), clock);
    new SourceHealthMonitor(logOnly, 1, 5000).record(List.of(new SourceCheck(FAA, SourceCheck.UNAVAILABLE, 40)));

    assertThat(logOnly.isOpen("source-down:" + FAA)).isTrue();
    assertThat(sent).isEmpty();
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-06T12:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
