package com.travelrisk.platform.service;

import com.travelrisk.platform.web.ResourceNotFoundException;
import com.travelrisk.platform.database.entities.TripNotification;
import com.travelrisk.platform.repository.TripNotificationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripNotificationService {
  private final TripNotificationRepository repository;

  public TripNotificationService(TripNotificationRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public List<TripNotificationResponse> list(String username) {
    return repository.findByUsernameOrderByCreatedAtDesc(username).stream()
        .map(this::toResponse)
        .toList();
  }

  @Transactional
  public TripNotificationResponse markRead(String username, Long id) {
    TripNotification notification = repository.findByIdAndUsername(id, username)
        .orElseThrow(() -> new ResourceNotFoundException("Notification not found."));
    notification.markRead();
    return toResponse(repository.save(notification));
  }

  TripNotificationResponse toResponse(TripNotification notification) {
    return new TripNotificationResponse(
        notification.getId(),
        notification.getSavedTripId(),
        notification.getOrigin(),
        notification.getDestination(),
        notification.getTravelDate().toString(),
        notification.getPreviousRiskLevel(),
        notification.getCurrentRiskLevel(),
        notification.getPreviousRiskPoints(),
        notification.getCurrentRiskPoints(),
        notification.getMessage(),
        notification.getReadAt() != null,
        notification.getCreatedAt().toString());
  }

  public record TripNotificationResponse(
      Long id,
      Long savedTripId,
      String origin,
      String destination,
      String date,
      String previousRiskLevel,
      String currentRiskLevel,
      Integer previousRiskPoints,
      Integer currentRiskPoints,
      String message,
      boolean read,
      String createdAt) {}
}
