package com.travelrisk.platform.itinerary;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Sample itinerary files dated from today, so a downloaded sample always falls inside the 15-day forecast window.
 */
public final class ItinerarySamples {
  private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

  private ItinerarySamples() {}

  private record Trip(int traveler, String origin, String originAirport, String destination,
      String destinationAirport, int daysOut, String mode) {}

  private static final List<Trip> TRIPS = List.of(
      new Trip(0, "New York, NY", "JFK", "Chicago, IL", "ORD", 2, "flight"),
      new Trip(1, "Denver, CO", "DEN", "Seattle, WA", "SEA", 3, "flight"),
      new Trip(0, "Boston, MA", "", "Washington, DC", "", 5, "drive"),
      new Trip(2, "Dallas, TX", "", "Austin, TX", "", 7, "drive"),
      new Trip(1, "Miami, FL", "", "Atlanta, GA", "", 9, "general"));

  /**
   * @param travelers emails to spread the sample trips across; pass one email to give every trip to one person
   */
  public static String csv(LocalDate today, List<String> travelers) {
    StringBuilder csv = new StringBuilder("traveler_email,origin,destination,date,trip_type,origin_airport,destination_airport\n");
    for (Trip trip : TRIPS) {
      csv.append(traveler(travelers, trip)).append(',')
          .append('"').append(trip.origin()).append("\",")
          .append('"').append(trip.destination()).append("\",")
          .append(today.plusDays(trip.daysOut())).append(',')
          .append(trip.mode()).append(',')
          .append(trip.originAirport()).append(',')
          .append(trip.destinationAirport()).append('\n');
    }
    return csv.toString();
  }

  public static String ics(LocalDate today, List<String> travelers) {
    StringBuilder ics = new StringBuilder()
        .append("BEGIN:VCALENDAR\r\n")
        .append("VERSION:2.0\r\n")
        .append("PRODID:-//Travel Risk Platform//Sample itinerary//EN\r\n");
    int index = 0;
    for (Trip trip : TRIPS) {
      LocalDate date = today.plusDays(trip.daysOut());
      ics.append("BEGIN:VEVENT\r\n")
          .append("UID:sample-trip-").append(++index).append("@travel-risk-platform\r\n")
          .append("DTSTAMP:").append(today.format(ICS_DATE)).append("T000000Z\r\n")
          .append("DTSTART;VALUE=DATE:").append(date.format(ICS_DATE)).append("\r\n")
          .append("SUMMARY:").append(escape(summary(trip))).append("\r\n")
          .append("ATTENDEE;CN=Traveler:mailto:").append(traveler(travelers, trip)).append("\r\n")
          .append("END:VEVENT\r\n");
    }
    return ics.append("END:VCALENDAR\r\n").toString();
  }

  private static String summary(Trip trip) {
    String label = switch (trip.mode()) {
      case "drive" -> "Drive";
      case "general" -> "Business trip";
      default -> "Flight";
    };
    return label + ": " + place(trip.origin(), trip.originAirport()) + " to " + place(trip.destination(), trip.destinationAirport());
  }

  private static String place(String city, String airport) {
    return airport.isEmpty() ? city : city + " (" + airport + ")";
  }

  private static String traveler(List<String> travelers, Trip trip) {
    return travelers.get(trip.traveler() % travelers.size());
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;");
  }
}
