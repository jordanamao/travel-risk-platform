package com.travelrisk.platform.alerts;

import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.service.SavedTripService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Re-checks every upcoming saved trip on a timer ({@code TRIP_RECHECK_INTERVAL}, e.g. {@code 6h}),
 * so risk-change alerts reach email/Slack without anyone opening the app. Off when the interval is blank.
 */
@Configuration
@EnableScheduling
@ConditionalOnExpression("'${travel-risk.trip-recheck.interval:}'.trim() != ''")
public class TripRecheckScheduler {
  private static final Logger log = LoggerFactory.getLogger(TripRecheckScheduler.class);

  private final SavedTripRepository trips;
  private final SavedTripService savedTrips;

  public TripRecheckScheduler(SavedTripRepository trips, SavedTripService savedTrips) {
    this.trips = trips;
    this.savedTrips = savedTrips;
  }

  // The first run waits a full interval so a fresh deploy (and its demo seed) settles first.
  @Scheduled(
      fixedDelayString = "${travel-risk.trip-recheck.interval}",
      initialDelayString = "${travel-risk.trip-recheck.interval}")
  public void recheckSavedTrips() {
    int users = 0;
    int changed = 0;
    for (String username : trips.findUsernamesWithTripsFrom(LocalDate.now(ZoneOffset.UTC))) {
      try {
        changed += savedTrips.checkAlerts(username).changedTrips();
        users++;
      } catch (RuntimeException error) {
        log.warn("trip_recheck_failed user={} error={}", username, error.toString());
      }
    }
    log.info("trip_recheck_done users={} changedTrips={}", users, changed);
  }
}
