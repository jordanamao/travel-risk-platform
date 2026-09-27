package com.travelrisk.platform;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedTripRepository extends JpaRepository<SavedTrip, Long> {
  List<SavedTrip> findByUsernameOrderByTravelDateAscUpdatedAtDesc(String username);

  Optional<SavedTrip> findByIdAndUsername(Long id, String username);

  Optional<SavedTrip> findByUsernameAndOriginAndDestinationAndTravelDateAndModeAndOriginAirportAndDestinationAirport(
      String username,
      String origin,
      String destination,
      java.time.LocalDate travelDate,
      String mode,
      String originAirport,
      String destinationAirport);
}
