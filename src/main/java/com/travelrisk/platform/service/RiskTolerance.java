package com.travelrisk.platform.service;

import java.util.Arrays;
import java.util.Locale;

/**
 * How much disruption risk an employee is comfortable with. It never changes the company score
 * (High stays High for policy, alerts and the admin view); it moves the line above which a trip is
 * flagged for that employee personally.
 */
public enum RiskTolerance {
  CAUTIOUS("Cautious", 3, "Flag any trip from 3 points, including busy Low-risk days."),
  BALANCED("Balanced", 5, "Flag Medium and High trips, the same line the company uses."),
  FLEXIBLE("Flexible", 10, "Only flag High-risk trips.");

  private final String label;
  private final int flagAt;
  private final String description;

  RiskTolerance(String label, int flagAt, String description) {
    this.label = label;
    this.flagAt = flagAt;
    this.description = description;
  }

  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  public String label() {
    return label;
  }

  /** Risk points at or above which a trip is flagged for this employee. */
  public int flagAt() {
    return flagAt;
  }

  public String description() {
    return description;
  }

  /** Unknown or blank values fall back to Balanced, the company default. */
  public static RiskTolerance from(String value) {
    return Arrays.stream(values())
        .filter(tolerance -> tolerance.id().equalsIgnoreCase(value == null ? "" : value.trim()))
        .findFirst()
        .orElse(BALANCED);
  }

  static boolean isKnown(String value) {
    return value != null && Arrays.stream(values()).anyMatch(tolerance -> tolerance.id().equalsIgnoreCase(value.trim()));
  }
}
