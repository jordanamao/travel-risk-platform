package com.travelrisk.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.TestPolicies;
import com.travelrisk.platform.cost.DisruptionCostService;
import com.travelrisk.platform.cost.DisruptionCostSettings;
import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.monitoring.ApiMonitoringService;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {
  @Mock
  private AssessmentHistoryRepository historyRepository;

  @Mock
  private ApiMonitoringService monitoringService;

  @Mock
  private SavedTripRepository savedTripRepository;

  @Mock
  private TripNotificationRepository notificationRepository;

  @Mock
  private UserProfileService userProfileService;

  private AdminDashboardService service;

  @BeforeEach
  void setUp() {
    service = new AdminDashboardService(historyRepository, monitoringService, savedTripRepository, notificationRepository,
        userProfileService, TestPolicies.defaultPolicy(), new DisruptionCostService(DisruptionCostSettings.defaults()),
        new ObjectMapper());
  }

  @Test
  void dashboardReturnsStatsAndRecentHistory() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();

    var assessment = TestAssessments.assessment("Seattle, WA", "San Francisco, CA", "2026-09-28", "flight", "High", 12);
    SavedTrip trip = new SavedTrip("employee", assessment, objectMapper.writeValueAsString(assessment));
    ReflectionTestUtils.setField(trip, "id", 7L);
    AssessmentHistory history = new AssessmentHistory("employee", assessment, objectMapper.writeValueAsString(assessment));

    when(savedTripRepository.findAllByOrderByTravelDateAscUpdatedAtDesc()).thenReturn(List.of(trip));
    when(savedTripRepository.countDistinctUsers()).thenReturn(1L);
    when(savedTripRepository.countByRiskLevelIgnoreCase("High")).thenReturn(1L);
    when(notificationRepository.countByReadAtIsNull()).thenReturn(2L);
    when(historyRepository.count()).thenReturn(3L);
    when(historyRepository.countByRiskLevelIgnoreCaseAndCreatedAtGreaterThanEqual("High", AdminDashboardService.SCORING_FIX_AT))
        .thenReturn(1L);
    when(historyRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(history));
    when(userProfileService.findAll(List.of("employee"))).thenReturn(Map.of());
    when(monitoringService.snapshot()).thenReturn(new ApiMonitoringService.MonitoringSnapshot(4, 1, 2, 1500, List.of(), List.of()));

    AdminDashboardService.AdminDashboardResponse response = service.getDashboard();

    assertThat(response.stats().savedTrips()).isEqualTo(1);
    assertThat(response.stats().employees()).isEqualTo(1);
    assertThat(response.stats().highRiskTrips()).isEqualTo(1);
    assertThat(response.stats().unreadAlerts()).isEqualTo(2);
    assertThat(response.stats().historyRecords()).isEqualTo(3);
    assertThat(response.trips()).hasSize(1);
    assertThat(response.stats().highRiskChecks()).isEqualTo(1);
    assertThat(response.trips().get(0).policy().outcome()).isEqualTo("approval_required");
    assertThat(response.history()).hasSize(1);
    assertThat(response.history().getFirst().scoredBeforeFix()).isFalse();
    assertThat(response.trips().getFirst().employee().name()).isEqualTo("employee");
    assertThat(response.trips().getFirst().cost().expected()).isEqualTo(590);
  }

  @Test
  void costAtRiskCountsOnlyUpcomingTrips() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    String upcomingDate = LocalDate.now().plusDays(3).toString();
    var upcoming = TestAssessments.assessment("Seattle, WA", "Denver, CO", upcomingDate, "flight", "High", 12);
    var past = TestAssessments.assessment("Seattle, WA", "Denver, CO", "2020-01-01", "flight", "High", 12);
    SavedTrip upcomingTrip = new SavedTrip("employee1@email.com", upcoming, objectMapper.writeValueAsString(upcoming));
    SavedTrip pastTrip = new SavedTrip("employee1@email.com", past, objectMapper.writeValueAsString(past));
    ReflectionTestUtils.setField(upcomingTrip, "id", 1L);
    ReflectionTestUtils.setField(pastTrip, "id", 2L);
    when(savedTripRepository.findAllByOrderByTravelDateAscUpdatedAtDesc()).thenReturn(List.of(pastTrip, upcomingTrip));
    when(historyRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of());
    when(userProfileService.findAll(List.of("employee1@email.com"))).thenReturn(Map.of());
    when(monitoringService.snapshot()).thenReturn(new ApiMonitoringService.MonitoringSnapshot(0, 0, 0, 1500, List.of(), List.of()));

    var costAtRisk = service.getDashboard().costAtRisk();

    assertThat(costAtRisk.trips()).isEqualTo(1);
    assertThat(costAtRisk.expected()).isEqualTo(590);
    assertThat(costAtRisk.topTrips()).extracting(trip -> trip.tripId()).containsExactly(1L);
  }

  @Test
  void historyRecordedBeforeScoringFixIsFlagged() throws Exception {
    var assessment = TestAssessments.assessment("Seattle, WA", "San Francisco, CA", "2026-09-28", "flight", "High", 60);
    AssessmentHistory old = new AssessmentHistory("employee1@email.com", assessment, "{}");
    ReflectionTestUtils.setField(old, "createdAt", AdminDashboardService.SCORING_FIX_AT.minusSeconds(60));
    when(historyRepository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(old));
    when(savedTripRepository.findAllByOrderByTravelDateAscUpdatedAtDesc()).thenReturn(List.of());
    when(monitoringService.snapshot()).thenReturn(new ApiMonitoringService.MonitoringSnapshot(0, 0, 0, 1500, List.of(), List.of()));
    when(userProfileService.findAll(List.of("employee1@email.com"))).thenReturn(Map.of());

    assertThat(service.getDashboard().history().getFirst().scoredBeforeFix()).isTrue();
  }

  @Test
  void employeeShowsProfileNameInsteadOfGoogleId() {
    UserProfile profile = new UserProfile("112955154178300265575");
    profile.update("jordan@example.com", "Jordan Mao");

    var known = AdminDashboardService.employeeFor("112955154178300265575", Map.of(profile.getUsername(), profile));
    var unknownGoogle = AdminDashboardService.employeeFor("998877665544332211", Map.of());
    var legacy = AdminDashboardService.employeeFor("employee", Map.of());
    var email = AdminDashboardService.employeeFor("employee1@email.com", Map.of());

    assertThat(known).isEqualTo(new AdminDashboardService.Employee("Jordan Mao", "jordan@example.com"));
    assertThat(unknownGoogle.name()).isEqualTo("Google user");
    assertThat(legacy).isEqualTo(new AdminDashboardService.Employee("employee", "Old demo login"));
    assertThat(email).isEqualTo(new AdminDashboardService.Employee("employee1@email.com", null));
  }
}
