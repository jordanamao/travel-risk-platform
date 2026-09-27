package com.travelrisk.platform;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripNotificationRepository extends JpaRepository<TripNotification, Long> {
  List<TripNotification> findByUsernameOrderByCreatedAtDesc(String username);

  Optional<TripNotification> findByIdAndUsername(Long id, String username);

  void deleteByUsernameAndSavedTripId(String username, Long savedTripId);
}
