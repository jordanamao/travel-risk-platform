package com.travelrisk.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.TestPolicies;
import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.monitoring.ApiMonitoringService;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.util.List;
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

  private AdminDashboardService service;

  @BeforeEach
  void setUp() {
    service = new AdminDashboardService(historyRepository, monitoringService, savedTripRepository, notificationRepository,
        TestPolicies.defaultPolicy(), new ObjectMapper());
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
    when(historyRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(history));
    when(monitoringService.snapshot()).thenReturn(new ApiMonitoringService.MonitoringSnapshot(4, 1, 2, 1500, List.of(), List.of()));

    AdminDashboardService.AdminDashboardResponse response = service.getDashboard();

    assertThat(response.stats().savedTrips()).isEqualTo(1);
    assertThat(response.stats().employees()).isEqualTo(1);
    assertThat(response.stats().highRiskTrips()).isEqualTo(1);
    assertThat(response.stats().unreadAlerts()).isEqualTo(2);
    assertThat(response.stats().historyRecords()).isEqualTo(3);
    assertThat(response.trips()).hasSize(1);
    assertThat(response.trips().get(0).policy().outcome()).isEqualTo("approval_required");
    assertThat(response.history()).hasSize(1);
  }
}
