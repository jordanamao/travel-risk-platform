package com.travelrisk.platform.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import com.travelrisk.platform.repository.UserProfileRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:travelrisk-demo-seed;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "travel-risk.demo-data.enabled=true"
})
class DemoDataSeederTest {
  @Autowired
  private DemoDataSeeder seeder;

  @Autowired
  private SavedTripRepository trips;

  @Autowired
  private AssessmentHistoryRepository history;

  @Autowired
  private TripNotificationRepository notifications;

  @Autowired
  private UserProfileRepository profiles;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void seedsTripsAtEveryRiskLevelAndReseedsWithoutDuplicatesOrTouchingRealTrips() throws Exception {
    var own = TestAssessments.assessment("Austin, TX", "Houston, TX", "2026-10-09", "driving", "Low", 0);
    trips.save(new SavedTrip("employee1@email.com", own, objectMapper.writeValueAsString(own)));
    UserProfile edited = new UserProfile("employee2@email.com");
    edited.updateSettings("Denver, CO", "drive", "flexible", List.of(), false, true, "high");
    profiles.save(edited);

    seeder.run(new DefaultApplicationArguments());
    seeder.run(new DefaultApplicationArguments());

    Map<String, Long> byLevel = trips.findAll().stream()
        .filter(trip -> !"Austin, TX".equals(trip.getOrigin()))
        .collect(Collectors.groupingBy(SavedTrip::getRiskLevel, Collectors.counting()));
    assertThat(byLevel).containsEntry("Low", 3L).containsEntry("Medium", 3L).containsEntry("High", 2L);
    assertThat(trips.countDistinctUsers()).isEqualTo(4);
    assertThat(trips.findByUsernameOrderByTravelDateAscUpdatedAtDesc("employee1@email.com"))
        .extracting(SavedTrip::getOrigin).contains("Austin, TX");
    // 8 trips + 1 earlier score + 1 ad hoc check + 5 older checks on usual routes.
    assertThat(history.count()).isEqualTo(15);
    assertThat(profiles.findById("employee1@email.com")).hasValueSatisfying(profile -> {
      assertThat(profile.getRiskTolerance()).isEqualTo("cautious");
      assertThat(profile.getFrequentRoutes()).hasSize(2);
    });
    // A profile the employee already saved is left alone.
    assertThat(profiles.findById("employee2@email.com")).hasValueSatisfying(profile -> {
      assertThat(profile.getHomeCity()).isEqualTo("Denver, CO");
      assertThat(profile.isAlertEmail()).isFalse();
    });
    assertThat(notifications.findByUsernameOrderByCreatedAtDesc("employee1@email.com"))
        .singleElement()
        .satisfies(alert -> {
          assertThat(alert.getPreviousRiskLevel()).isEqualTo("Medium");
          assertThat(alert.getCurrentRiskLevel()).isEqualTo("High");
          assertThat(alert.getReadAt()).isNull();
        });
  }
}
