package com.travelrisk.platform.service;

import com.travelrisk.platform.web.ResourceNotFoundException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.TestPolicies;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SavedTripServiceTest {
  @Mock
  private SavedTripRepository repository;

  @Mock
  private TripNotificationRepository notificationRepository;

  @Mock
  private TripNotificationService notificationService;

  @Mock
  private TravelRiskService travelRiskService;

  private SavedTripService service;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    service = new SavedTripService(repository, notificationRepository, notificationService, travelRiskService,
        TestPolicies.defaultPolicy(), objectMapper);
  }

  @Test
  void saveUpdatesExistingTripForSameRouteAndDate() {
    var original = TestAssessments.assessment("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "Medium", 5);
    var updated = TestAssessments.assessment("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "High", 12);
    SavedTrip existing = new SavedTrip("employee", original, write(objectMapper, original));
    ReflectionTestUtils.setField(existing, "id", 42L);

    when(repository.findByUsernameAndOriginAndDestinationAndTravelDateAndModeAndOriginAirportAndDestinationAirport(
        "employee", "New York, NY", "San Francisco, CA", LocalDate.parse("2026-09-28"), "flight", "", ""))
        .thenReturn(Optional.of(existing));
    when(repository.save(any(SavedTrip.class))).thenAnswer(invocation -> invocation.getArgument(0));

    SavedTripService.SavedTripResponse response = service.save("employee", updated);

    assertThat(response.id()).isEqualTo(42L);
    assertThat(response.riskLevel()).isEqualTo("High");
    assertThat(response.riskPoints()).isEqualTo(12);
  }

  @Test
  void deleteThrowsWhenSavedTripIsMissing() {
    when(repository.findByIdAndUsername(99L, "employee")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete("employee", 99L))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessage("Saved trip not found.");
  }

  private static String write(ObjectMapper objectMapper, TravelRiskService.Assessment assessment) {
    try {
      return objectMapper.writeValueAsString(assessment);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
