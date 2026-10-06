package com.travelrisk.platform.cost;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Estimates what a disruption would cost a trip: the chance of disruption at the trip's risk level
 * times the typical costs (rebooking, an extra hotel night, lost working time). Amounts come from config.
 */
@Service
public class DisruptionCostService {
  private static final List<String> RISK_LEVELS = List.of("Low", "Medium", "High");

  private final DisruptionCostSettings settings;

  @Autowired
  public DisruptionCostService(
      @Value("${travel-risk.disruption-cost.currency:USD}") String currency,
      @Value("${travel-risk.disruption-cost.likelihood.low:0.05}") double lowLikelihood,
      @Value("${travel-risk.disruption-cost.likelihood.medium:0.20}") double mediumLikelihood,
      @Value("${travel-risk.disruption-cost.likelihood.high:0.50}") double highLikelihood,
      @Value("${travel-risk.disruption-cost.rebooking-fee:250}") int rebookingFee,
      @Value("${travel-risk.disruption-cost.hotel-night:200}") int hotelNight,
      @Value("${travel-risk.disruption-cost.hourly-rate:90}") int hourlyRate,
      @Value("${travel-risk.disruption-cost.lost-hours.flight:8}") int flightHours,
      @Value("${travel-risk.disruption-cost.lost-hours.drive:4}") int driveHours,
      @Value("${travel-risk.disruption-cost.lost-hours.general:6}") int generalHours) {
    this(new DisruptionCostSettings(
        currency,
        Map.of("Low", lowLikelihood, "Medium", mediumLikelihood, "High", highLikelihood),
        rebookingFee,
        hotelNight,
        hourlyRate,
        Map.of("flight", flightHours, "drive", driveHours, "general", generalHours)));
  }

  public DisruptionCostService(DisruptionCostSettings settings) {
    validate(settings);
    this.settings = settings;
  }

  public DisruptionCostSettings settings() {
    return settings;
  }

  /** The estimate for one trip, or null when the trip has no risk level yet. */
  public DisruptionCostEstimate estimate(String riskLevel, String mode) {
    String level = canonicalLevel(riskLevel);
    if (level == null) {
      return null;
    }
    String tripMode = mode == null ? "general" : mode.trim().toLowerCase(Locale.ROOT);
    if (!settings.lostHours().containsKey(tripMode)) {
      tripMode = "general";
    }

    List<DisruptionCostEstimate.Item> items = new ArrayList<>();
    // A drive has no ticket to change, so only booked travel carries a rebooking fee.
    if (!"drive".equals(tripMode)) {
      items.add(new DisruptionCostEstimate.Item("rebooking", "Rebooking or change fee", settings.rebookingFee(),
          "Typical change fee"));
    }
    items.add(new DisruptionCostEstimate.Item("hotel", "Extra hotel night", settings.hotelNight(),
        "One unplanned night"));
    int hours = settings.lostHours().get(tripMode);
    items.add(new DisruptionCostEstimate.Item("lost-time", "Lost working time and missed meetings",
        hours * settings.hourlyRate(), hours + " h × " + money(settings.hourlyRate()) + "/h"));

    int ifDisrupted = items.stream().mapToInt(DisruptionCostEstimate.Item::amount).sum();
    double likelihood = settings.likelihood().get(level);
    int expected = (int) Math.round(likelihood * ifDisrupted / 10.0) * 10;
    return new DisruptionCostEstimate(settings.currency(), level, likelihood, ifDisrupted, expected, List.copyOf(items));
  }

  /** Adds up the estimates of many trips, so managers see the money at risk across them. */
  public Rollup rollup(List<TripCost> trips) {
    Map<String, int[]> byLevel = new LinkedHashMap<>();
    for (int index = RISK_LEVELS.size() - 1; index >= 0; index--) {
      byLevel.put(RISK_LEVELS.get(index), new int[3]);
    }
    int expected = 0;
    int ifDisrupted = 0;
    List<TripCost> estimated = trips.stream().filter(trip -> trip.estimate() != null).toList();
    for (TripCost trip : estimated) {
      DisruptionCostEstimate estimate = trip.estimate();
      expected += estimate.expected();
      ifDisrupted += estimate.ifDisrupted();
      int[] totals = byLevel.get(estimate.riskLevel());
      totals[0]++;
      totals[1] += estimate.expected();
      totals[2] += estimate.ifDisrupted();
    }
    List<LevelTotal> levels = byLevel.entrySet().stream()
        .map(entry -> new LevelTotal(entry.getKey(), entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
        .toList();
    List<TripCost> top = estimated.stream()
        .filter(trip -> trip.estimate().expected() > 0)
        .sorted(Comparator.comparingInt((TripCost trip) -> trip.estimate().expected()).reversed())
        .limit(3)
        .toList();
    return new Rollup(settings.currency(), estimated.size(), expected, ifDisrupted, levels, top, settings.likelihood());
  }

  private static String canonicalLevel(String riskLevel) {
    if (riskLevel == null) {
      return null;
    }
    return RISK_LEVELS.stream().filter(level -> level.equalsIgnoreCase(riskLevel.trim())).findFirst().orElse(null);
  }

  private String money(int amount) {
    return "USD".equals(settings.currency()) ? "$" + amount : amount + " " + settings.currency();
  }

  /** Fails startup with a readable message instead of showing nonsense amounts. */
  private static void validate(DisruptionCostSettings settings) {
    if (settings.currency() == null || settings.currency().isBlank()) {
      throw invalid("currency is required");
    }
    for (String level : RISK_LEVELS) {
      Double value = settings.likelihood().get(level);
      if (value == null || value < 0 || value > 1) {
        throw invalid("likelihood." + level.toLowerCase(Locale.ROOT) + " must be between 0 and 1");
      }
    }
    if (settings.rebookingFee() < 0 || settings.hotelNight() < 0 || settings.hourlyRate() < 0) {
      throw invalid("amounts can't be negative");
    }
    for (String mode : List.of("flight", "drive", "general")) {
      Integer hours = settings.lostHours().get(mode);
      if (hours == null || hours < 0) {
        throw invalid("lost-hours." + mode + " must be zero or more");
      }
    }
  }

  private static IllegalStateException invalid(String detail) {
    return new IllegalStateException("Disruption cost settings are not valid: " + detail + ".");
  }

  /** One trip and its estimate, as shown in a rollup. */
  public record TripCost(Long tripId, String employee, String origin, String destination, String date, String mode,
      DisruptionCostEstimate estimate) {}

  public record LevelTotal(String riskLevel, int trips, int expected, int ifDisrupted) {}

  /**
   * @param trips how many trips have an estimate
   * @param expected sum of each trip's expected cost: the cost of going ahead with every trip unchanged
   * @param ifDisrupted sum of each trip's cost if it is disrupted
   * @param byLevel totals per risk level, High first
   * @param topTrips up to three trips carrying the most expected cost
   * @param likelihood the assumed chance of disruption per risk level
   */
  public record Rollup(String currency, int trips, int expected, int ifDisrupted, List<LevelTotal> byLevel,
      List<TripCost> topTrips, Map<String, Double> likelihood) {}
}
