package com.travelrisk.platform.service;

import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.monitoring.ApiMonitoringService;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {
  /**
   * Road closures were counted once per event until the scoring fix merged at this time,
   * so history rows recorded earlier can show inflated High scores.
   */
  static final Instant SCORING_FIX_AT = Instant.parse("2026-10-06T06:25:02Z");

  private final AssessmentHistoryRepository historyRepository;
  private final ApiMonitoringService monitoringService;
  private final SavedTripRepository savedTripRepository;
  private final TripNotificationRepository notificationRepository;
  private final UserProfileService userProfileService;

  public AdminDashboardService(
      AssessmentHistoryRepository historyRepository,
      ApiMonitoringService monitoringService,
      SavedTripRepository savedTripRepository,
      TripNotificationRepository notificationRepository,
      UserProfileService userProfileService) {
    this.userProfileService = userProfileService;
    this.historyRepository = historyRepository;
    this.monitoringService = monitoringService;
    this.savedTripRepository = savedTripRepository;
    this.notificationRepository = notificationRepository;
  }

  @Transactional(readOnly = true)
  public AdminDashboardResponse getDashboard() {
    List<SavedTrip> trips = savedTripRepository.findAllByOrderByTravelDateAscUpdatedAtDesc();
    List<AssessmentHistory> history = historyRepository.findTop50ByOrderByCreatedAtDesc();
    AdminStats stats = new AdminStats(
        trips.size(),
        savedTripRepository.countDistinctUsers(),
        savedTripRepository.countByRiskLevelIgnoreCase("High"),
        notificationRepository.countByReadAtIsNull(),
        historyRepository.count(),
        historyRepository.countByRiskLevelIgnoreCaseAndCreatedAtGreaterThanEqual("High", SCORING_FIX_AT));
    ApiMonitoringService.MonitoringSnapshot monitoring = monitoringService.snapshot();
    Map<String, UserProfile> profiles = userProfileService.findAll(
        Stream.concat(trips.stream().map(SavedTrip::getUsername), history.stream().map(AssessmentHistory::getUsername))
            .distinct()
            .toList());
    return new AdminDashboardResponse(
        stats,
        trips.stream().map(trip -> toResponse(trip, profiles)).toList(),
        history.stream().map(record -> toResponse(record, profiles)).toList(),
        monitoring);
  }

  @Transactional
  public long clearAssessmentHistory() {
    long count = historyRepository.count();
    historyRepository.deleteAllInBatch();
    return count;
  }

  private AdminTripResponse toResponse(SavedTrip trip, Map<String, UserProfile> profiles) {
    return new AdminTripResponse(
        trip.getId(),
        trip.getUsername(),
        employeeFor(trip.getUsername(), profiles),
        trip.getOrigin(),
        trip.getDestination(),
        trip.getTravelDate().toString(),
        trip.getMode(),
        trip.getRiskLevel(),
        trip.getRiskPoints(),
        trip.getSummary(),
        trip.getUpdatedAt().toString());
  }

  private AssessmentHistoryResponse toResponse(AssessmentHistory history, Map<String, UserProfile> profiles) {
    return new AssessmentHistoryResponse(
        history.getId(),
        history.getUsername(),
        employeeFor(history.getUsername(), profiles),
        history.getOrigin(),
        history.getDestination(),
        history.getTravelDate().toString(),
        history.getMode(),
        history.getRiskLevel(),
        history.getRiskPoints(),
        history.getSummary(),
        history.getCreatedAt().toString(),
        history.getCreatedAt().isBefore(SCORING_FIX_AT));
  }

  /** Who an account key belongs to, in words an admin can read. */
  static Employee employeeFor(String username, Map<String, UserProfile> profiles) {
    UserProfile profile = profiles.get(username);
    if (profile != null && (profile.getDisplayName() != null || profile.getEmail() != null)) {
      String name = profile.getDisplayName() != null ? profile.getDisplayName() : profile.getEmail();
      String detail = profile.getDisplayName() != null ? profile.getEmail() : null;
      return new Employee(name, detail);
    }
    if (username != null && username.matches("\\d{10,}")) {
      return new Employee("Google user", "Shows name after next sign-in");
    }
    if (username != null && !username.contains("@") && !"anonymous".equals(username)) {
      return new Employee(username, "Old demo login");
    }
    return new Employee(username, null);
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
      long historyRecords,
      long highRiskChecks) {}

  public record Employee(String name, String detail) {}

  public record AdminTripResponse(
      Long id,
      String username,
      Employee employee,
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
      Employee employee,
      String origin,
      String destination,
      String date,
      String mode,
      String riskLevel,
      Integer riskPoints,
      String summary,
      String createdAt,
      boolean scoredBeforeFix) {}
}
