package com.travelrisk.platform.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-process fixed-window rate limiter. Each key may make {@code maxRequests} calls per
 * {@code window}; the window starts at the first call for that key. Expired windows are purged
 * lazily so the map does not grow without bound.
 */
public class FixedWindowRateLimiter {
  private final int maxRequests;
  private final long windowMillis;
  private final Clock clock;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private volatile long nextPurgeAt;

  public FixedWindowRateLimiter(int maxRequests, Duration window, Clock clock) {
    if (maxRequests < 1) {
      throw new IllegalArgumentException("rate limit requests must be at least 1");
    }
    if (window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("rate limit window must be positive");
    }
    this.maxRequests = maxRequests;
    this.windowMillis = window.toMillis();
    this.clock = clock;
    this.nextPurgeAt = clock.millis() + windowMillis;
  }

  public Decision tryAcquire(String key) {
    long now = clock.millis();
    purgeExpired(now);
    Window current = windows.compute(key, (k, existing) -> {
      if (existing == null || now >= existing.startMillis + windowMillis) {
        return new Window(now, 1);
      }
      return new Window(existing.startMillis, existing.count + 1);
    });
    if (current.count <= maxRequests) {
      return new Decision(true, Duration.ZERO);
    }
    long retryMillis = Math.max(1, current.startMillis + windowMillis - now);
    return new Decision(false, Duration.ofMillis(retryMillis));
  }

  private void purgeExpired(long now) {
    if (now < nextPurgeAt) {
      return;
    }
    nextPurgeAt = now + windowMillis;
    windows.entrySet().removeIf(entry -> now >= entry.getValue().startMillis + windowMillis);
  }

  public record Decision(boolean allowed, Duration retryAfter) {}

  private record Window(long startMillis, int count) {}
}
