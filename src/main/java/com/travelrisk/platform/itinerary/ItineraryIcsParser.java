package com.travelrisk.platform.itinerary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads trips from an iCalendar (.ics) file, such as a calendar export of booked travel.
 * Each VEVENT becomes one trip:
 * <ul>
 *   <li>DTSTART gives the travel date.</li>
 *   <li>SUMMARY gives the route, e.g. {@code Flight: New York, NY (JFK) to Chicago, IL (ORD)}.
 *       The word before the colon is the trip type; airport codes in brackets are optional.</li>
 *   <li>The first ATTENDEE with a mailto: address is the traveler.</li>
 *   <li>X-TRAVEL-ORIGIN, X-TRAVEL-DESTINATION and X-TRAVEL-MODE override the SUMMARY when present.</li>
 * </ul>
 */
public final class ItineraryIcsParser {
  private static final Pattern ROUTE = Pattern.compile("^(?:from\\s+)?(.+?)\\s+to\\s+(.+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern PLACE_WITH_AIRPORT = Pattern.compile("^(.*?)\\s*\\(([A-Za-z0-9]{3,4})\\)$");
  private static final Pattern MODE_PREFIX = Pattern.compile("^([A-Za-z ]{2,20}):\\s*(.+)$");

  private ItineraryIcsParser() {}

  public static List<ItineraryRow> parse(String content) {
    List<Line> lines = unfold(content);
    if (lines.stream().noneMatch(line -> line.name().equals("BEGIN") && line.value().equalsIgnoreCase("VCALENDAR"))) {
      throw new IllegalArgumentException("The calendar file is not valid. It should start with BEGIN:VCALENDAR.");
    }

    List<ItineraryRow> rows = new ArrayList<>();
    Map<String, String> event = null;
    int eventLine = 0;
    for (Line line : lines) {
      if (line.name().equals("BEGIN") && line.value().equalsIgnoreCase("VEVENT")) {
        event = new HashMap<>();
        eventLine = line.number();
      } else if (line.name().equals("END") && line.value().equalsIgnoreCase("VEVENT") && event != null) {
        rows.add(toRow(eventLine, event));
        event = null;
      } else if (event != null) {
        if (line.name().equals("ATTENDEE")) {
          if (!event.containsKey("ATTENDEE") && line.value().toLowerCase(Locale.ROOT).startsWith("mailto:")) {
            event.put("ATTENDEE", line.value().substring("mailto:".length()));
          }
        } else {
          event.putIfAbsent(line.name(), line.value());
        }
      }
    }
    return rows;
  }

  private static ItineraryRow toRow(int line, Map<String, String> event) {
    String summary = unescape(event.getOrDefault("SUMMARY", "")).trim();
    String mode = "";
    String route = summary;
    Matcher prefix = MODE_PREFIX.matcher(summary);
    if (prefix.matches()) {
      mode = prefix.group(1).trim();
      route = prefix.group(2).trim();
    }

    String origin = "";
    String destination = "";
    Matcher parts = ROUTE.matcher(route);
    if (parts.matches()) {
      origin = parts.group(1);
      destination = parts.group(2);
    }
    origin = unescape(event.getOrDefault("X-TRAVEL-ORIGIN", origin));
    destination = unescape(event.getOrDefault("X-TRAVEL-DESTINATION", destination));
    mode = unescape(event.getOrDefault("X-TRAVEL-MODE", mode));

    if (origin.isBlank() || destination.isBlank()) {
      return ItineraryRow.invalid(line, "Couldn't read a route from the event title '" + summary
          + "'. Use a title like 'Flight: New York, NY to Chicago, IL'.");
    }

    String[] originParts = splitAirport(origin);
    String[] destinationParts = splitAirport(destination);
    String start = event.getOrDefault("DTSTART", "");
    String date = start.length() >= 8 ? start.substring(0, 8) : start;
    return ItineraryRow.of(line, event.getOrDefault("ATTENDEE", ""), originParts[0], destinationParts[0], date, mode,
        originParts[1], destinationParts[1]);
  }

  /** "Chicago, IL (ORD)" becomes ["Chicago, IL", "ORD"]. */
  private static String[] splitAirport(String place) {
    Matcher matcher = PLACE_WITH_AIRPORT.matcher(place.trim());
    return matcher.matches() ? new String[] {matcher.group(1), matcher.group(2)} : new String[] {place.trim(), ""};
  }

  /** Joins folded lines (continuations start with a space or tab) and splits each into name and value. */
  private static List<Line> unfold(String content) {
    String text = content.startsWith("﻿") ? content.substring(1) : content;
    String[] physical = text.split("\r\n|\r|\n", -1);
    List<Line> lines = new ArrayList<>();
    StringBuilder current = null;
    int currentNumber = 0;
    for (int index = 0; index < physical.length; index++) {
      String raw = physical[index];
      if (current != null && !raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
        current.append(raw, 1, raw.length());
        continue;
      }
      if (current != null) {
        lines.add(Line.parse(currentNumber, current.toString()));
      }
      current = new StringBuilder(raw);
      currentNumber = index + 1;
    }
    if (current != null) {
      lines.add(Line.parse(currentNumber, current.toString()));
    }
    lines.removeIf(line -> line.name().isEmpty());
    return lines;
  }

  private static String unescape(String value) {
    return value.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
  }

  private record Line(int number, String name, String value) {
    /** NAME;PARAM="quoted:value":VALUE becomes name NAME and value VALUE. */
    static Line parse(int number, String text) {
      boolean quoted = false;
      for (int index = 0; index < text.length(); index++) {
        char current = text.charAt(index);
        if (current == '"') {
          quoted = !quoted;
        } else if (current == ':' && !quoted) {
          String name = text.substring(0, index);
          int params = name.indexOf(';');
          name = (params >= 0 ? name.substring(0, params) : name).trim().toUpperCase(Locale.ROOT);
          return new Line(number, name, text.substring(index + 1).trim());
        }
      }
      return new Line(number, "", "");
    }
  }
}
