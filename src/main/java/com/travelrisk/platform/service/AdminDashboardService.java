package com.travelrisk.platform.service;

import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {
  private final SavedTripRepository savedTripRepository;
  private final TripNotificationRepository notificationRepository;

  public AdminDashboardService(
      SavedTripRepository savedTripRepository,
      TripNotificationRepository notificationRepository) {
    this.savedTripRepository = savedTripRepository;
    this.notificationRepository = notificationRepository;
  }

  @Transactional(readOnly = true)
  public AdminDashboardResponse getDashboard() {
    List<SavedTrip> trips = savedTripRepository.findAllByOrderByTravelDateAscUpdatedAtDesc();
    AdminStats stats = new AdminStats(
        trips.size(),
        savedTripRepository.countDistinctUsers(),
        savedTripRepository.countByRiskLevelIgnoreCase("High"),
        notificationRepository.countByReadAtIsNull());
    return new AdminDashboardResponse(stats, trips.stream().map(this::toResponse).toList());
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

  public record AdminDashboardResponse(
      AdminStats stats,
      List<AdminTripResponse> trips) {}

  public record AdminStats(
      long savedTrips,
      long employees,
      long highRiskTrips,
      long unreadAlerts) {}

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
}
