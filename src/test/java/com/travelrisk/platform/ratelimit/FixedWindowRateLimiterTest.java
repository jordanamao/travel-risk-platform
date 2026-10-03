package com.travelrisk.platform.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {
  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public java.time.ZoneId getZone() {
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

  @Test
  void allowsUpToLimitThenRejectsWithRetryAfter() {
    MutableClock clock = new MutableClock();
    var limiter = new FixedWindowRateLimiter(2, Duration.ofSeconds(60), clock);

    assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    clock.advance(Duration.ofSeconds(20));
    var rejected = limiter.tryAcquire("a");

    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(40));
  }

  @Test
  void keysAreIndependent() {
    var limiter = new FixedWindowRateLimiter(1, Duration.ofSeconds(60), new MutableClock());

    assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    assertThat(limiter.tryAcquire("b").allowed()).isTrue();
  }

  @Test
  void windowResetsAfterItExpires() {
    MutableClock clock = new MutableClock();
    var limiter = new FixedWindowRateLimiter(1, Duration.ofSeconds(60), clock);

    assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    clock.advance(Duration.ofSeconds(60));
    assertThat(limiter.tryAcquire("a").allowed()).isTrue();
  }

  @Test
  void rejectsInvalidConfiguration() {
    assertThatThrownBy(() -> new FixedWindowRateLimiter(0, Duration.ofSeconds(1), Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FixedWindowRateLimiter(1, Duration.ZERO, Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
