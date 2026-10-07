package com.travelrisk.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.FrequentRoute;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.UserProfileRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskMemoryServiceTest {
  private static final String USER = "employee1@email.com";
  private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

  private final AssessmentHistoryRepository history = mock(AssessmentHistoryRepository.class);
  private final SavedTripRepository trips = mock(SavedTripRepository.class);
  private final UserProfileRepository profiles = mock(UserProfileRepository.class);
  private final List<AssessmentHistory> pastChecks = new ArrayList<>();
  private final List<SavedTrip> savedTrips = new ArrayList<>();
  private RiskMemoryService service;

  @BeforeEach
  void setUp() {
    service = new RiskMemoryService(history, trips, profiles, Clock.fixed(NOW, ZoneOffset.UTC));
    when(history.findTop200ByUsernameAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(eq(USER), any()))
        .thenReturn(pastChecks);
    when(trips.findByUsernameOrderByTravelDateAscUpdatedAtDesc(USER)).thenReturn(savedTrips);
  }

  @Test
  void cautiousEmployeeIsFlaggedOnATripTheCompanyRatesLow() {
    tolerance("cautious");

    var personal = service.personalize(USER, trip("Dallas, TX", "Orlando, FL", "Low", 4));

    assertThat(personal.status()).isEqualTo("flagged");
    assertThat(personal.flagAt()).isEqualTo(3);
    assertThat(personal.headline()).isEqualTo("Above your comfort line");
    assertThat(personal.detail()).contains("Cautious").contains("even though the company rates it Low");
    assertThat(personal.notes()).containsExactly("First time you've checked this route in the last 90 days.");
  }

  @Test
  void aRouteThatWasRiskyBeforeIsWatchedInEitherDirection() {
    tolerance("balanced");
    check("Orlando, FL", "Dallas, TX", "High", 11, 4);
    check("Dallas, TX", "Orlando, FL", "Low", 1, 9);
    check("Dallas, TX", "Orlando, FL", "Medium", 6, 12);
    check("Boston, MA", "Washington, DC", "High", 12, 1);

    var personal = service.personalize(USER, trip("Dallas, TX", "Orlando, FL", "Low", 2));

    assertThat(personal.status()).isEqualTo("watch");
    assertThat(personal.headline()).isEqualTo("Risky for you before");
    assertThat(personal.route().checks()).isEqualTo(3);
    assertThat(personal.route().riskyChecks()).isEqualTo(2);
    assertThat(personal.notes()).first().asString()
        .isEqualTo("2 of your 3 past checks on this route came back Medium or High (last High on Oct 3).");
  }

  @Test
  void flexibleEmployeeWithACleanRecordIsWithinTheirLine() {
    tolerance("flexible");
    UserProfile profile = profiles.findById(USER).orElseThrow();
    profile.updateSettings(null, null, "flexible", List.of(new FrequentRoute("New York, NY", "Chicago, IL", "flight")),
        true, true, "any");
    check("New York, NY", "Chicago, IL", "Low", 0, 3);
    check("New York, NY", "Chicago, IL", "Low", 1, 8);

    var personal = service.personalize(USER, trip("New York, NY", "Chicago, IL", "Medium", 7));

    assertThat(personal.status()).isEqualTo("clear");
    assertThat(personal.detail()).isEqualTo("7 points is below your Flexible flag line (10 points).");
    assertThat(personal.notes()).containsExactly(
        "Your 2 past checks on this route were all Low.", "One of your frequent routes.");
  }

  @Test
  void otherSavedTripsOnTheRouteCountButTheTripItselfDoesNot() throws Exception {
    var self = trip("Dallas, TX", "Orlando, FL", "Low", 2);
    var later = TestAssessments.assessment("Dallas, TX", "Orlando, FL", "2026-10-15", "flight", "High", 12);
    savedTrips.add(new SavedTrip(USER, self, "{}"));
    savedTrips.add(new SavedTrip(USER, later, "{}"));

    var personal = service.personalize(USER, self);

    assertThat(personal.status()).isEqualTo("watch");
    assertThat(personal.notes()).contains("You have 1 other saved trip on this route, currently Medium or High.");
  }

  @Test
  void signedOutChecksAreNotPersonalized() {
    assertThat(service.personalize("anonymous", trip("Dallas, TX", "Orlando, FL", "High", 12))).isNull();
  }

  @Test
  void summaryRanksRiskyRoutesAndSuggestsRepeatedOnesNotYetPinned() {
    check("Dallas, TX", "Orlando, FL", "High", 12, 2);
    check("Dallas, TX", "Orlando, FL", "Medium", 6, 5);
    check("New York, NY", "Chicago, IL", "Low", 0, 3);
    check("New York, NY", "Chicago, IL", "Low", 0, 6);
    check("New York, NY", "Chicago, IL", "Low", 0, 7);
    check("Seattle, WA", "Portland, OR", "Low", 0, 1);

    var summary = service.summary(USER, List.of(new FrequentRoute("Orlando, FL", "Dallas, TX", "flight")));

    assertThat(summary.checks()).isEqualTo(6);
    assertThat(summary.routes()).extracting(RiskMemoryService.RouteStat::origin)
        .containsExactly("Dallas, TX", "New York, NY", "Seattle, WA");
    assertThat(summary.routes().getFirst().riskyChecks()).isEqualTo(2);
    assertThat(summary.suggestedRoutes()).containsExactly(new FrequentRoute("New York, NY", "Chicago, IL", "flight"));
  }

  private void tolerance(String value) {
    UserProfile profile = new UserProfile(USER);
    profile.updateSettings(null, null, value, List.of(), true, true, "any");
    when(profiles.findById(USER)).thenReturn(Optional.of(profile));
  }

  private void check(String origin, String destination, String level, int points, int daysAgo) {
    pastChecks.add(new AssessmentHistory(USER, TestAssessments.assessment(origin, destination, "2026-10-01", "flight",
        level, points), "{}", NOW.minus(Duration.ofDays(daysAgo))));
    pastChecks.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
  }

  private static TravelRiskService.Assessment trip(String origin, String destination, String level, int points) {
    return TestAssessments.assessment(origin, destination, "2026-10-10", "flight", level, points);
  }
}
