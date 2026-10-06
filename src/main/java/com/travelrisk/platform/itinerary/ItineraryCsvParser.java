package com.travelrisk.platform.itinerary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads trips from a CSV export, such as a travel agency's booking report.
 * The header row is required; column names are matched loosely ("Travel Date", "travel_date" and "date" all work)
 * because every customer's export names them differently.
 */
public final class ItineraryCsvParser {
  static final String EXPECTED_HEADERS = "traveler_email, origin, destination, date, trip_type, origin_airport, destination_airport";

  private static final Map<String, List<String>> ALIASES = Map.of(
      "traveler", List.of("traveler_email", "traveler", "email", "employee", "employee_email", "traveller_email"),
      "origin", List.of("origin", "from", "origin_city", "departure_city"),
      "destination", List.of("destination", "to", "destination_city", "arrival_city"),
      "date", List.of("date", "travel_date", "departure_date", "start_date"),
      "mode", List.of("trip_type", "mode", "type", "travel_mode"),
      "origin_airport", List.of("origin_airport", "from_airport", "departure_airport"),
      "destination_airport", List.of("destination_airport", "to_airport", "arrival_airport"));

  private ItineraryCsvParser() {}

  public static List<ItineraryRow> parse(String content) {
    List<List<String>> records = readRecords(content).stream()
        .dropWhile(ItineraryCsvParser::blank)
        .toList();
    if (records.isEmpty()) {
      throw new IllegalArgumentException("The CSV file is empty. Expected a header row: " + EXPECTED_HEADERS + ".");
    }

    Map<String, Integer> columns = mapColumns(records.get(0).subList(1, records.get(0).size()));
    List<String> missing = new ArrayList<>();
    for (String required : List.of("origin", "destination", "date")) {
      if (!columns.containsKey(required)) {
        missing.add(required);
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("The CSV file is missing the " + String.join(", ", missing)
          + " column" + (missing.size() > 1 ? "s" : "") + ". Expected a header row: " + EXPECTED_HEADERS + ".");
    }

    List<ItineraryRow> rows = new ArrayList<>();
    for (List<String> record : records.subList(1, records.size())) {
      int line = Integer.parseInt(record.get(0));
      if (blank(record)) {
        continue;
      }
      List<String> values = record.subList(1, record.size());
      rows.add(ItineraryRow.of(line,
          value(values, columns, "traveler"),
          value(values, columns, "origin"),
          value(values, columns, "destination"),
          value(values, columns, "date"),
          value(values, columns, "mode"),
          value(values, columns, "origin_airport"),
          value(values, columns, "destination_airport")));
    }
    return rows;
  }

  private static boolean blank(List<String> record) {
    return record.subList(1, record.size()).stream().allMatch(String::isBlank);
  }

  private static Map<String, Integer> mapColumns(List<String> header) {
    Map<String, Integer> columns = new HashMap<>();
    for (int index = 0; index < header.size(); index++) {
      String name = header.get(index).trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
      for (Map.Entry<String, List<String>> alias : ALIASES.entrySet()) {
        if (alias.getValue().contains(name)) {
          columns.putIfAbsent(alias.getKey(), index);
        }
      }
    }
    return columns;
  }

  private static String value(List<String> values, Map<String, Integer> columns, String field) {
    Integer index = columns.get(field);
    return index == null || index >= values.size() ? "" : values.get(index);
  }

  /**
   * Splits RFC 4180 CSV into records. Quoted fields may contain commas ("Denver, CO"), doubled quotes and line breaks.
   * The first value of each record is the line number it started on.
   */
  static List<List<String>> readRecords(String content) {
    String text = content.startsWith("﻿") ? content.substring(1) : content;
    List<List<String>> records = new ArrayList<>();
    List<String> record = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    int line = 1;
    int recordLine = 1;

    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      if (quoted) {
        if (current == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
          field.append('"');
          index++;
        } else if (current == '"') {
          quoted = false;
        } else {
          if (current == '\n') {
            line++;
          }
          field.append(current);
        }
      } else if (current == '"') {
        quoted = true;
      } else if (current == ',') {
        record.add(field.toString());
        field.setLength(0);
      } else if (current == '\n' || current == '\r') {
        if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
          index++;
        }
        record.add(field.toString());
        field.setLength(0);
        records.add(withLine(recordLine, record));
        record = new ArrayList<>();
        line++;
        recordLine = line;
      } else {
        field.append(current);
      }
    }
    if (quoted) {
      throw new IllegalArgumentException("The CSV file has an unclosed quote starting on line " + recordLine + ".");
    }
    if (field.length() > 0 || !record.isEmpty()) {
      record.add(field.toString());
      records.add(withLine(recordLine, record));
    }
    return records;
  }

  private static List<String> withLine(int line, List<String> values) {
    List<String> result = new ArrayList<>(values.size() + 1);
    result.add(String.valueOf(line));
    result.addAll(values);
    return result;
  }
}
