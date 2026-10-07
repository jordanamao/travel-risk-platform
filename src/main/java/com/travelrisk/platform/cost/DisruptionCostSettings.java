package com.travelrisk.platform.cost;

import java.util.Map;

/**
 * The default amounts behind a disruption cost estimate. Each one is a plain number in config
 * (see {@code travel-risk.disruption-cost.*}) so a company can swap in its own figures.
 *
 * @param currency ISO currency code the amounts are in
 * @param likelihood assumed chance that a trip is disrupted, by risk level (0 to 1)
 * @param rebookingFee typical airline change or rebooking fee
 * @param hotelNight typical cost of an unplanned extra hotel night
 * @param hourlyRate loaded cost of one hour of an employee's working time
 * @param lostHours working hours lost to a disruption (missed meeting, waiting, re-planning), by trip mode
 */
public record DisruptionCostSettings(
    String currency,
    Map<String, Double> likelihood,
    int rebookingFee,
    int hotelNight,
    int hourlyRate,
    Map<String, Integer> lostHours) {

  public static DisruptionCostSettings defaults() {
    return new DisruptionCostSettings(
        "USD",
        Map.of("Low", 0.05, "Medium", 0.20, "High", 0.50),
        250,
        200,
        90,
        Map.of("flight", 8, "drive", 4, "general", 6));
  }
}
