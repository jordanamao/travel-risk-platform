package com.travelrisk.platform.repository;

import com.travelrisk.platform.database.entities.SavedTrip;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SavedTripRepository extends JpaRepository<SavedTrip, Long> {
  List<SavedTrip> findByUsernameOrderByTravelDateAscUpdatedAtDesc(String username);

  List<SavedTrip> findAllByOrderByTravelDateAscUpdatedAtDesc();

  List<SavedTrip> findByAssessmentJsonContaining(String text);

  long countByRiskLevelIgnoreCase(String riskLevel);

  @Query("select count(distinct trip.username) from SavedTrip trip")
  long countDistinctUsers();

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
