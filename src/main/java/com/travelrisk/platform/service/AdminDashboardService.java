package com.travelrisk.platform.service;

import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.monitoring.ApiMonitoringService;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
        monitoring,
        false);
  }

  /** Read-only view for the public demo admin: only trips and history of the given demo users. */
  @Transactional(readOnly = true)
  public AdminDashboardResponse getDemoDashboard(Predicate<String> isDemoUser) {
    List<SavedTrip> trips = savedTripRepository.findAllByOrderByTravelDateAscUpdatedAtDesc().stream()
        .filter(trip -> isDemoUser.test(trip.getUsername()))
        .toList();
    List<AssessmentHistory> history = historyRepository.findAll().stream()
        .filter(item -> isDemoUser.test(item.getUsername()))
        .sorted(Comparator.comparing(AssessmentHistory::getCreatedAt).reversed())
        .toList();
    Set<Long> tripIds = trips.stream().map(SavedTrip::getId).collect(Collectors.toSet());
    long unread = notificationRepository.findAll().stream()
        .filter(alert -> alert.getReadAt() == null && tripIds.contains(alert.getSavedTripId()))
        .count();
    AdminStats stats = new AdminStats(
        trips.size(),
        trips.stream().map(SavedTrip::getUsername).distinct().count(),
        trips.stream().filter(trip -> "High".equalsIgnoreCase(trip.getRiskLevel())).count(),
        unread,
        history.size());
    return new AdminDashboardResponse(
        stats,
        trips.stream().map(this::toResponse).toList(),
        history.stream().limit(10).map(this::toResponse).toList(),
        monitoringService.snapshot(),
        true);
  }

  @Transactional
  public long clearAssessmentHistory() {
    long count = historyRepository.count();
    historyRepository.deleteAllInBatch();
    return count;
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
      ApiMonitoringService.MonitoringSnapshot monitoring,
      boolean readOnly) {}

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
