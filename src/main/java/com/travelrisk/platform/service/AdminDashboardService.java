package com.travelrisk.platform.service;

import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.monitoring.ApiMonitoringService;
import com.travelrisk.platform.policy.PolicyDecision;
import com.travelrisk.platform.policy.TravelPolicyService;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {
  private final AssessmentHistoryRepository historyRepository;
  private final ApiMonitoringService monitoringService;
  private final SavedTripRepository savedTripRepository;
  private final TripNotificationRepository notificationRepository;
  private final TravelPolicyService policyService;
  private final ObjectMapper objectMapper;

  public AdminDashboardService(
      AssessmentHistoryRepository historyRepository,
      ApiMonitoringService monitoringService,
      SavedTripRepository savedTripRepository,
      TripNotificationRepository notificationRepository,
      TravelPolicyService policyService,
      ObjectMapper objectMapper) {
    this.historyRepository = historyRepository;
    this.monitoringService = monitoringService;
    this.savedTripRepository = savedTripRepository;
    this.notificationRepository = notificationRepository;
    this.policyService = policyService;
    this.objectMapper = objectMapper;
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
        trip.getUpdatedAt().toString(),
        policyService.evaluate(trip.getRiskLevel(), trip.getRiskPoints(), trip.getMode(), readSnapshot(trip)));
  }

  private Map<String, Object> readSnapshot(SavedTrip trip) {
    try {
      return objectMapper.readValue(trip.getAssessmentJson(), new TypeReference<>() {});
    } catch (Exception error) {
      return null;
    }
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
      String updatedAt,
      PolicyDecision policy) {}

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
