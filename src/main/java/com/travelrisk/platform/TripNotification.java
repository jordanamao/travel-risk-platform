package com.travelrisk.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "trip_notifications")
public class TripNotification {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String username;

  @Column(name = "saved_trip_id", nullable = false)
  private Long savedTripId;

  @Column(nullable = false)
  private String origin;

  @Column(nullable = false)
  private String destination;

  @Column(name = "travel_date", nullable = false)
  private LocalDate travelDate;

  private String previousRiskLevel;

  private String currentRiskLevel;

  private Integer previousRiskPoints;

  private Integer currentRiskPoints;

  @Column(length = 1200, nullable = false)
  private String message;

  private Instant readAt;

  @Column(nullable = false)
  private Instant createdAt;

  protected TripNotification() {}

  public TripNotification(SavedTrip trip, TravelRiskService.Assessment assessment) {
    this.username = trip.getUsername();
    this.savedTripId = trip.getId();
    this.origin = trip.getOrigin();
    this.destination = trip.getDestination();
    this.travelDate = trip.getTravelDate();
    this.previousRiskLevel = trip.getRiskLevel();
    this.currentRiskLevel = assessment.score().level();
    this.previousRiskPoints = trip.getRiskPoints();
    this.currentRiskPoints = assessment.score().points();
    this.message = "%s to %s on %s changed from %s (%d pts) to %s (%d pts).".formatted(
        origin,
        destination,
        travelDate,
        safeLevel(previousRiskLevel),
        previousRiskPoints == null ? 0 : previousRiskPoints,
        safeLevel(currentRiskLevel),
        currentRiskPoints == null ? 0 : currentRiskPoints);
    this.createdAt = Instant.now();
  }

  public void markRead() {
    this.readAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public String getUsername() {
    return username;
  }

  public Long getSavedTripId() {
    return savedTripId;
  }

  public String getOrigin() {
    return origin;
  }

  public String getDestination() {
    return destination;
  }

  public LocalDate getTravelDate() {
    return travelDate;
  }

  public String getPreviousRiskLevel() {
    return previousRiskLevel;
  }

  public String getCurrentRiskLevel() {
    return currentRiskLevel;
  }

  public Integer getPreviousRiskPoints() {
    return previousRiskPoints;
  }

  public Integer getCurrentRiskPoints() {
    return currentRiskPoints;
  }

  public String getMessage() {
    return message;
  }

  public Instant getReadAt() {
    return readAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  private String safeLevel(String level) {
    return level == null || level.isBlank() ? "Unknown" : level;
  }
}
