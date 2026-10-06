package com.travelrisk.platform.itinerary;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One trip read from a customer's itinerary file, before it is risk-checked.
 * Values are cleaned up here so both file formats behave the same way.
 *
 * @param line the line in the file the trip came from, for error messages
 * @param error why the trip can't be imported, or null when it can
 */
public record ItineraryRow(
    int line,
    String traveler,
    String origin,
    String destination,
    String date,
    String mode,
    String originAirport,
    String destinationAirport,
    String error) {

  private static final Pattern AIRPORT = Pattern.compile("^[A-Z0-9]{3,4}$");
  private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
      DateTimeFormatter.ISO_LOCAL_DATE,
      DateTimeFormatter.ofPattern("M/d/uuuu"),
      DateTimeFormatter.BASIC_ISO_DATE);

  /** Builds a row from raw file values, recording the first problem found instead of throwing. */
  public static ItineraryRow of(int line, String traveler, String origin, String destination, String date,
      String mode, String originAirport, String destinationAirport) {
    String cleanTraveler = clean(traveler).toLowerCase(Locale.ROOT);
    String cleanOrigin = clean(origin);
    String cleanDestination = clean(destination);
    String isoDate = parseDate(clean(date));
    String cleanMode = normalizeMode(clean(mode));
    String cleanOriginAirport = normalizeAirport(originAirport);
    String cleanDestinationAirport = normalizeAirport(destinationAirport);

    String error = null;
    if (cleanOrigin.isEmpty()) {
      error = "Origin is missing.";
    } else if (cleanDestination.isEmpty()) {
      error = "Destination is missing.";
    } else if (clean(date).isEmpty()) {
      error = "Date is missing.";
    } else if (isoDate == null) {
      error = "Date '" + clean(date) + "' isn't a date. Use YYYY-MM-DD or MM/DD/YYYY.";
    } else if (cleanMode == null) {
      error = "Trip type '" + clean(mode) + "' isn't supported. Use flight, drive or general.";
    } else if (!cleanTraveler.isEmpty() && !cleanTraveler.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
      error = "Traveler '" + clean(traveler) + "' isn't an email address.";
    }
    return new ItineraryRow(line, cleanTraveler, cleanOrigin, cleanDestination,
        isoDate == null ? clean(date) : isoDate, cleanMode == null ? clean(mode) : cleanMode,
        cleanOriginAirport, cleanDestinationAirport, error);
  }

  public static ItineraryRow invalid(int line, String error) {
    return new ItineraryRow(line, "", "", "", "", "", "", "", error);
  }

  public boolean valid() {
    return error == null;
  }

  public ItineraryRow withError(String message) {
    return new ItineraryRow(line, traveler, origin, destination, date, mode, originAirport, destinationAirport, message);
  }

  public ItineraryRow withTraveler(String value) {
    return new ItineraryRow(line, value, origin, destination, date, mode, originAirport, destinationAirport, error);
  }

  static String normalizeMode(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "", "flight", "fly", "air", "plane" -> "flight";
      case "drive", "driving", "car", "road", "rental car" -> "drive";
      case "general", "business", "business trip", "trip", "travel", "mixed", "train", "rail" -> "general";
      default -> null;
    };
  }

  /** Accepts "SFO" or "KSFO"; three-letter U.S. codes get the K prefix the risk checker's airport menus use. */
  static String normalizeAirport(String value) {
    String code = clean(value).toUpperCase(Locale.ROOT);
    if (!AIRPORT.matcher(code).matches()) {
      return "";
    }
    return code.length() == 3 && code.chars().allMatch(Character::isLetter) ? "K" + code : code;
  }

  private static String parseDate(String value) {
    for (DateTimeFormatter format : DATE_FORMATS) {
      try {
        return LocalDate.parse(value, format).toString();
      } catch (DateTimeParseException ignored) {
        // try the next format
      }
    }
    return null;
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
