package com.travelrisk.platform.service;

import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.monitoring.ApiMonitoringService;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {
  private final AssessmentHistoryRepository historyRepository;
  private final ApiMonitoringService monitoringService;
  private final SavedTripRepository savedTripRepository;
  private final TripNotificationRepository notificationRepository;

  public AdminDashboardService(
      AssessmentHistoryRepository historyRepository,
      ApiMonitoringService monitoringService,
      SavedTripRepository savedTripRepository,
      TripNotificationRepository notificationRepository) {
    this.historyRepository = historyRepository;
    this.monitoringService = monitoringService;
    this.savedTripRepository = savedTripRepository;
    this.notificationRepository = notificationRepository;
  }

  @Transactional(readOnly = true)
  public AdminDashboardResponse getDashboard() {
    List<SavedTrip> trips = savedTripRepository.findAllByOrderByTravelDateAscUpdatedAtDesc();
    List<AssessmentHistory> history = historyRepository.findTop10ByOrderByCreatedAtDesc();
    AdminStats stats = new AdminStats(
        trips.size(),
        savedTripRepository.countDistinctUsers(),
        savedTripRepository.countByRiskLevelIgnoreCase("High"),
        notificationRepository.countByReadAtIsNull(),
        historyRepository.count());
    ApiMonitoringService.MonitoringSnapshot monitoring = monitoringService.snapshot();
    return new AdminDashboardResponse(
        stats,
        trips.stream().map(this::toResponse).toList(),
        history.stream().map(this::toResponse).toList(),
        monitoring);
  }

  private AdminTripResponse toResponse(SavedTrip trip) {
    return new AdminTripResponse(
        trip.getId(),
        trip.getUsername(),
        trip.getOrigin(),
        trip.getDestination(),
        trip.getTravelDate().toString(),
        trip.getMode(),
        trip.getRiskLevel(),
        trip.getRiskPoints(),
        trip.getSummary(),
        trip.getUpdatedAt().toString());
  }

  private AssessmentHistoryResponse toResponse(AssessmentHistory history) {
    return new AssessmentHistoryResponse(
        history.getId(),
        history.getUsername(),
        history.getOrigin(),
        history.getDestination(),
        history.getTravelDate().toString(),
        history.getMode(),
        history.getRiskLevel(),
        history.getRiskPoints(),
        history.getSummary(),
        history.getCreatedAt().toString());
  }

  public record AdminDashboardResponse(
      AdminStats stats,
      List<AdminTripResponse> trips,
      List<AssessmentHistoryResponse> history,
      ApiMonitoringService.MonitoringSnapshot monitoring) {}

  public record AdminStats(
      long savedTrips,
      long employees,
      long highRiskTrips,
      long unreadAlerts,
      long historyRecords) {}

  public record AdminTripResponse(
      Long id,
      String username,
      String origin,
      String destination,
      String date,
      String mode,
      String riskLevel,
      Integer riskPoints,
      String summary,
      String updatedAt) {}

  public record AssessmentHistoryResponse(
      Long id,
      String username,
      String origin,
      String destination,
      String date,
      String mode,
      String riskLevel,
      Integer riskPoints,
      String summary,
      String createdAt) {}
}
