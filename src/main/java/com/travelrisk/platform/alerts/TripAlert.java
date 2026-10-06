package com.travelrisk.platform.alerts;

import com.travelrisk.platform.database.entities.TripNotification;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A saved trip whose risk level moved, in the shape every outside channel (email, Slack) needs.
 * Self-contained so it can be sent on a background thread after the database work is done.
 */
public record TripAlert(
    String username,
    String origin,
    String destination,
    LocalDate travelDate,
    String mode,
    String previousLevel,
    String currentLevel,
    Integer previousPoints,
    Integer currentPoints,
    String reason,
    String appUrl,
    String recipientEmail) {

  private static final List<String> LEVELS = List.of("Low", "Medium", "High");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

  static TripAlert from(
      TripNotification notification, String mode, String reason, String appUrl, String recipientEmail) {
    return new TripAlert(
        notification.getUsername(),
        notification.getOrigin(),
        notification.getDestination(),
        notification.getTravelDate(),
        mode,
        notification.getPreviousRiskLevel(),
        notification.getCurrentRiskLevel(),
        notification.getPreviousRiskPoints(),
        notification.getCurrentRiskPoints(),
        reason,
        appUrl,
        recipientEmail);
  }

  /** A change of level (Low, Medium, High) is material; a few points inside the same level is not. */
  public static boolean isMaterial(String previousLevel, String currentLevel) {
    return LEVELS.contains(previousLevel) && LEVELS.contains(currentLevel) && !previousLevel.equals(currentLevel);
  }

  public boolean escalated() {
    return LEVELS.indexOf(currentLevel) > LEVELS.indexOf(previousLevel);
  }

  public String route() {
    return origin + " to " + destination;
  }

  public String friendlyDate() {
    return travelDate.format(DATE);
  }

  public String tripType() {
    return "driving".equalsIgnoreCase(mode) ? "Drive" : "Flight";
  }

  /** e.g. "Risk up: Dallas, TX to Orlando, FL is now High". */
  public String subject() {
    return "Risk %s: %s is now %s".formatted(escalated() ? "up" : "down", route(), currentLevel);
  }

  public String change() {
    return "%s (%d pts) to %s (%d pts)".formatted(
        previousLevel, previousPoints == null ? 0 : previousPoints,
        currentLevel, currentPoints == null ? 0 : currentPoints);
  }
}
