package com.travelrisk.platform.monitoring;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ApiMonitoringService {
  private static final int MAX_EVENTS = 25;

  private final Map<String, Metric> metrics = new ConcurrentHashMap<>();
  private final ArrayDeque<ApiEvent> recentEvents = new ArrayDeque<>();
  private final long slowCallThresholdMs;

  public ApiMonitoringService(@Value("${travel-risk.monitoring.slow-call-threshold-ms:1500}") long slowCallThresholdMs) {
    this.slowCallThresholdMs = slowCallThresholdMs;
  }

  public void recordSuccess(String operation, long durationMs) {
    Metric metric = metrics.computeIfAbsent(operation, ignored -> new Metric());
    metric.calls.incrementAndGet();
    metric.totalDurationMs.addAndGet(durationMs);
    metric.maxDurationMs.updateAndGet(current -> Math.max(current, durationMs));
    if (durationMs >= slowCallThresholdMs) {
      metric.slowCalls.incrementAndGet();
      addEvent(new ApiEvent(operation, "slow", durationMs, null, Instant.now()));
    }
  }

  public void recordFailure(String operation, long durationMs, Throwable error) {
    Metric metric = metrics.computeIfAbsent(operation, ignored -> new Metric());
    metric.calls.incrementAndGet();
    metric.failures.incrementAndGet();
    metric.totalDurationMs.addAndGet(durationMs);
    metric.maxDurationMs.updateAndGet(current -> Math.max(current, durationMs));
    addEvent(new ApiEvent(operation, "failure", durationMs, error.getClass().getSimpleName(), Instant.now()));
  }

  public MonitoringSnapshot snapshot() {
    List<ApiMetric> apiMetrics = metrics.entrySet().stream()
        .map(entry -> entry.getValue().toResponse(entry.getKey()))
        .sorted(Comparator.comparing(ApiMetric::failures).reversed().thenComparing(ApiMetric::operation))
        .toList();

    List<ApiEvent> events;
    synchronized (recentEvents) {
      events = new ArrayList<>(recentEvents);
    }

    long calls = apiMetrics.stream().mapToLong(ApiMetric::calls).sum();
    long failures = apiMetrics.stream().mapToLong(ApiMetric::failures).sum();
    long slowCalls = apiMetrics.stream().mapToLong(ApiMetric::slowCalls).sum();
    return new MonitoringSnapshot(calls, failures, slowCalls, slowCallThresholdMs, apiMetrics, events);
  }

  private void addEvent(ApiEvent event) {
    synchronized (recentEvents) {
      recentEvents.addFirst(event);
      while (recentEvents.size() > MAX_EVENTS) {
        recentEvents.removeLast();
      }
    }
  }

  private static final class Metric {
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong slowCalls = new AtomicLong();
    private final AtomicLong totalDurationMs = new AtomicLong();
    private final AtomicLong maxDurationMs = new AtomicLong();

    private ApiMetric toResponse(String operation) {
      long callCount = calls.get();
      long total = totalDurationMs.get();
      long average = callCount == 0 ? 0 : Math.round((double) total / callCount);
      return new ApiMetric(
          operation,
          callCount,
          failures.get(),
          slowCalls.get(),
          average,
          maxDurationMs.get());
    }
  }

  public record MonitoringSnapshot(
      long calls,
      long failures,
      long slowCalls,
      long slowCallThresholdMs,
      List<ApiMetric> metrics,
      List<ApiEvent> recentEvents) {}

  public record ApiMetric(
      String operation,
      long calls,
      long failures,
      long slowCalls,
      long averageDurationMs,
      long maxDurationMs) {}

  public record ApiEvent(
      String operation,
      String type,
      long durationMs,
      String error,
      Instant createdAt) {}
}
