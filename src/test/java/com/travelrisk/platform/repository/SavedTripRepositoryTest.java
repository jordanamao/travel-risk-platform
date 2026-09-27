package com.travelrisk.platform.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.database.entities.SavedTrip;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SavedTripRepositoryTest {
  @Autowired
  private SavedTripRepository repository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void savesAndFindsTripByIdentity() throws Exception {
    var assessment = TestAssessments.assessment("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "High", 12);
    SavedTrip saved = repository.save(new SavedTrip("employee", assessment, objectMapper.writeValueAsString(assessment)));

    var found = repository.findByUsernameAndOriginAndDestinationAndTravelDateAndModeAndOriginAirportAndDestinationAirport(
        "employee",
        "New York, NY",
        "San Francisco, CA",
        LocalDate.parse("2026-09-28"),
        "flight",
        "",
        "");

    assertThat(saved.getId()).isNotNull();
    assertThat(found).isPresent();
    assertThat(found.get().getRiskLevel()).isEqualTo("High");
    assertThat(repository.countDistinctUsers()).isEqualTo(1);
    assertThat(repository.countByRiskLevelIgnoreCase("high")).isEqualTo(1);
  }
}
