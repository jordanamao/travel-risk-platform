package com.travelrisk.platform.database.entities;

import com.travelrisk.platform.service.TravelRiskService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "assessment_history")
public class AssessmentHistory {
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

  protected AssessmentHistory() {}

  public AssessmentHistory(String username, TravelRiskService.Assessment assessment, String assessmentJson) {
    TravelRiskService.Input input = assessment.input();
    this.username = clean(username).isBlank() ? "anonymous" : clean(username);
    this.origin = input.origin();
    this.destination = input.destination();
    this.travelDate = LocalDate.parse(input.date());
    this.mode = input.mode();
    this.originAirport = clean(input.originAirport());
    this.destinationAirport = clean(input.destinationAirport());
    this.riskLevel = assessment.score().level();
    this.riskPoints = assessment.score().points();
    this.summary = assessment.summary();
    this.assessmentJson = assessmentJson;
    this.createdAt = Instant.now();
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

  private String clean(String value) {
    return value == null ? "" : value;
  }
}
