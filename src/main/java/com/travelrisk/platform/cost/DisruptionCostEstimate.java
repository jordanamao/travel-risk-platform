package com.travelrisk.platform.cost;

import java.util.List;

/**
 * What a disruption could cost one trip. Every amount is an estimate built from configured defaults.
 *
 * @param currency ISO currency code
 * @param riskLevel the trip's risk level the likelihood was taken from
 * @param likelihood assumed chance of disruption at that risk level (0 to 1)
 * @param ifDisrupted total of the cost items if the trip is disrupted
 * @param expected likelihood × ifDisrupted, rounded to the nearest 10: the cost of going ahead unchanged
 * @param items what makes up {@code ifDisrupted}
 */
public record DisruptionCostEstimate(
    String currency,
    String riskLevel,
    double likelihood,
    int ifDisrupted,
    int expected,
    List<Item> items) {

  /**
   * @param key stable id, e.g. "rebooking"
   * @param label what the cost is, e.g. "Rebooking or change fee"
   * @param amount cost if the trip is disrupted
   * @param basis how the amount was worked out, e.g. "8 h × $90/h"
   */
  public record Item(String key, String label, int amount, String basis) {}
}
