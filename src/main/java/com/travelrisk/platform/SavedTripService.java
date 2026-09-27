package com.travelrisk.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedTripService {
  private final SavedTripRepository repository;
  private final ObjectMapper objectMapper;

  public SavedTripService(SavedTripRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public List<SavedTripResponse> list(String username) {
    return repository.findByUsernameOrderByTravelDateAscUpdatedAtDesc(username).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional
  public SavedTripResponse save(String username, TravelRiskService.Assessment assessment) {
    TravelRiskService.Input input = assessment.input();
    String assessmentJson = writeAssessment(assessment);
    SavedTrip savedTrip = repository
        .findByUsernameAndOriginAndDestinationAndTravelDateAndModeAndOriginAirportAndDestinationAirport(
            username,
            input.origin(),
            input.destination(),
            LocalDate.parse(input.date()),
            input.mode(),
            cleanNullable(input.originAirport()),
            cleanNullable(input.destinationAirport()))
        .orElseGet(() -> new SavedTrip(username, assessment, assessmentJson));
    if (savedTrip.getId() != null) {
      savedTrip.updateFrom(assessment, assessmentJson);
    }
    return toResponse(repository.save(savedTrip));
  }

  @Transactional
  public void delete(String username, Long id) {
    SavedTrip savedTrip = repository.findByIdAndUsername(id, username)
        .orElseThrow(() -> new IllegalArgumentException("Saved trip not found"));
    repository.delete(savedTrip);
  }

  private String writeAssessment(TravelRiskService.Assessment assessment) {
    try {
      return objectMapper.writeValueAsString(assessment);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Could not save assessment snapshot", error);
    }
  }

  private String cleanNullable(String value) {
    return value == null ? "" : value;
  }

  private SavedTripResponse toResponse(SavedTrip trip) {
    Map<String, Object> assessment;
    try {
      assessment = objectMapper.readValue(trip.getAssessmentJson(), new TypeReference<>() {});
    } catch (Exception error) {
      assessment = null;
    }
    return new SavedTripResponse(
        trip.getId(),
        trip.getOrigin(),
        trip.getDestination(),
        trip.getTravelDate().toString(),
        trip.getMode(),
        trip.getOriginAirport(),
        trip.getDestinationAirport(),
        trip.getRiskLevel(),
        trip.getRiskPoints(),
        trip.getSummary(),
        trip.getCreatedAt().toString(),
        trip.getUpdatedAt().toString(),
        assessment);
  }

  public record SavedTripResponse(
      Long id,
      String origin,
      String destination,
      String date,
      String mode,
      String originAirport,
      String destinationAirport,
      String riskLevel,
      Integer riskPoints,
      String summary,
      String createdAt,
      String updatedAt,
      Map<String, Object> assessment) {}
}
