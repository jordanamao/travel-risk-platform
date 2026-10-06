package com.travelrisk.platform.service;

import com.travelrisk.platform.web.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.database.entities.TripNotification;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedTripService {
  private final SavedTripRepository repository;
  private final TripNotificationRepository notificationRepository;
  private final TripNotificationService notificationService;
  private final TravelRiskService travelRiskService;
  private final ObjectMapper objectMapper;

  public SavedTripService(
      SavedTripRepository repository,
      TripNotificationRepository notificationRepository,
      TripNotificationService notificationService,
      TravelRiskService travelRiskService,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.notificationRepository = notificationRepository;
    this.notificationService = notificationService;
    this.travelRiskService = travelRiskService;
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
        .orElseThrow(() -> new ResourceNotFoundException("Saved trip not found."));
    notificationRepository.deleteByUsernameAndSavedTripId(username, id);
    repository.delete(savedTrip);
  }

  @Transactional
  public AlertCheckResponse checkAlerts(String username) {
    List<SavedTrip> trips = repository.findByUsernameOrderByTravelDateAscUpdatedAtDesc(username);
    int changedCount = 0;
    java.util.ArrayList<TripNotificationService.TripNotificationResponse> notifications = new java.util.ArrayList<>();

    for (SavedTrip trip : trips) {
      TravelRiskService.Assessment assessment = travelRiskService.analyze(
          trip.getOrigin(),
          trip.getDestination(),
          trip.getTravelDate().toString(),
          trip.getMode(),
          trip.getOriginAirport(),
          trip.getDestinationAirport());
      if (!riskChanged(trip, assessment)) {
        continue;
      }

      TripNotification notification = notificationRepository.save(new TripNotification(trip, assessment));
      notifications.add(notificationService.toResponse(notification));
      trip.updateFrom(assessment, writeAssessment(assessment));
      repository.save(trip);
      changedCount++;
    }

    return new AlertCheckResponse(trips.size(), changedCount, notifications);
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

  private boolean riskChanged(SavedTrip trip, TravelRiskService.Assessment assessment) {
    return !java.util.Objects.equals(trip.getRiskLevel(), assessment.score().level())
        || !java.util.Objects.equals(trip.getRiskPoints(), assessment.score().points());
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

  public record AlertCheckResponse(
      int checkedTrips,
      int changedTrips,
      List<TripNotificationService.TripNotificationResponse> notifications) {}
}
