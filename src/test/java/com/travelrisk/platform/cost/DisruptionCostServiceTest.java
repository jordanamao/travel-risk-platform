package com.travelrisk.platform.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DisruptionCostServiceTest {
  private final DisruptionCostService service = new DisruptionCostService(DisruptionCostSettings.defaults());

  @Test
  void highRiskFlightCostsLikelihoodTimesRebookingHotelAndLostTime() {
    DisruptionCostEstimate estimate = service.estimate("High", "flight");

    assertThat(estimate.items()).extracting(DisruptionCostEstimate.Item::key)
        .containsExactly("rebooking", "hotel", "lost-time");
    assertThat(estimate.items()).extracting(DisruptionCostEstimate.Item::amount).containsExactly(250, 200, 720);
    assertThat(estimate.items().get(2).basis()).isEqualTo("8 h × $90/h");
    assertThat(estimate.ifDisrupted()).isEqualTo(1170);
    assertThat(estimate.likelihood()).isEqualTo(0.50);
    assertThat(estimate.expected()).isEqualTo(590);
    assertThat(estimate.currency()).isEqualTo("USD");
  }

  @Test
  void driveHasNoRebookingFee() {
    DisruptionCostEstimate estimate = service.estimate("medium", "drive");

    assertThat(estimate.riskLevel()).isEqualTo("Medium");
    assertThat(estimate.items()).extracting(DisruptionCostEstimate.Item::key).containsExactly("hotel", "lost-time");
    assertThat(estimate.ifDisrupted()).isEqualTo(200 + 4 * 90);
    assertThat(estimate.expected()).isEqualTo(110);
  }

  @Test
  void unscoredTripHasNoEstimateAndUnknownModeCountsAsGeneral() {
    assertThat(service.estimate(null, "flight")).isNull();
    assertThat(service.estimate("Severe", "flight")).isNull();
    assertThat(service.estimate("Low", "train").ifDisrupted()).isEqualTo(service.estimate("Low", "general").ifDisrupted());
  }

  @Test
  void companyAmountsReplaceTheDefaults() {
    DisruptionCostService custom = new DisruptionCostService(new DisruptionCostSettings(
        "SGD", Map.of("Low", 0.1, "Medium", 0.3, "High", 0.6), 300, 250, 120, Map.of("flight", 10, "drive", 5, "general", 8)));

    DisruptionCostEstimate estimate = custom.estimate("High", "flight");

    assertThat(estimate.ifDisrupted()).isEqualTo(300 + 250 + 1200);
    assertThat(estimate.expected()).isEqualTo(1050);
    assertThat(estimate.items().get(2).basis()).isEqualTo("10 h × 120 SGD/h");
  }

  @Test
  void rejectsLikelihoodOutsideZeroToOne() {
    assertThatThrownBy(() -> new DisruptionCostService(new DisruptionCostSettings(
        "USD", Map.of("Low", 0.05, "Medium", 0.2, "High", 50.0), 250, 200, 90, Map.of("flight", 8, "drive", 4, "general", 6))))
        .hasMessageContaining("likelihood.high must be between 0 and 1");
  }

  @Test
  void rollupAddsUpTripsByRiskLevelAndListsTheCostliest() {
    List<DisruptionCostService.TripCost> trips = List.of(
        trip(1L, "High", "flight"),
        trip(2L, "High", "drive"),
        trip(3L, "Low", "flight"),
        trip(4L, null, "flight"));

    DisruptionCostService.Rollup rollup = service.rollup(trips);

    assertThat(rollup.trips()).isEqualTo(3);
    assertThat(rollup.expected()).isEqualTo(590 + 280 + 60);
    assertThat(rollup.ifDisrupted()).isEqualTo(1170 + 560 + 1170);
    assertThat(rollup.byLevel()).extracting(DisruptionCostService.LevelTotal::riskLevel)
        .containsExactly("High", "Medium", "Low");
    assertThat(rollup.byLevel().getFirst()).isEqualTo(new DisruptionCostService.LevelTotal("High", 2, 870, 1730));
    assertThat(rollup.topTrips()).extracting(DisruptionCostService.TripCost::tripId).containsExactly(1L, 2L, 3L);
  }

  private DisruptionCostService.TripCost trip(Long id, String level, String mode) {
    return new DisruptionCostService.TripCost(id, "Employee " + id, "Seattle, WA", "Denver, CO", "2030-01-01", mode,
        service.estimate(level, mode));
  }
}
