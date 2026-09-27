package com.travelrisk.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
    name = "saved_trips",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_saved_trip_identity",
        columnNames = {"username", "origin", "destination", "travel_date", "mode", "origin_airport", "destination_airport"}))
public class SavedTrip {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String username;

  @Column(nullable = false)
  private String origin;

  @Column(nullable = false)
  private String destination;

  @Column(name = "travel_date", nullable = false)
  private LocalDate travelDate;

  @Column(nullable = false)
  private String mode;

  @Column(name = "origin_airport", nullable = false)
  private String originAirport = "";

  @Column(name = "destination_airport", nullable = false)
  private String destinationAirport = "";

  private String riskLevel;

  private Integer riskPoints;

  @Column(length = 1200)
  private String summary;

  @Column(columnDefinition = "text", nullable = false)
  private String assessmentJson;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;

  protected SavedTrip() {}

  public SavedTrip(String username, TravelRiskService.Assessment assessment, String assessmentJson) {
    this.username = username;
    updateFrom(assessment, assessmentJson);
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public void updateFrom(TravelRiskService.Assessment assessment, String assessmentJson) {
    TravelRiskService.Input input = assessment.input();
    this.origin = input.origin();
    this.destination = input.destination();
    this.travelDate = LocalDate.parse(input.date());
    this.mode = input.mode();
    this.originAirport = cleanNullable(input.originAirport());
    this.destinationAirport = cleanNullable(input.destinationAirport());
    this.riskLevel = assessment.score().level();
    this.riskPoints = assessment.score().points();
    this.summary = assessment.summary();
    this.assessmentJson = assessmentJson;
    this.updatedAt = Instant.now();
  }

  public Long getId() {
    return id;
  }

  public String getUsername() {
    return username;
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

  public String getMode() {
    return mode;
  }

  public String getOriginAirport() {
    return originAirport;
  }

  public String getDestinationAirport() {
    return destinationAirport;
  }

  public String getRiskLevel() {
    return riskLevel;
  }

  public Integer getRiskPoints() {
    return riskPoints;
  }

  public String getSummary() {
    return summary;
  }

  public String getAssessmentJson() {
    return assessmentJson;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  private String cleanNullable(String value) {
    return value == null ? "" : value;
  }
}
