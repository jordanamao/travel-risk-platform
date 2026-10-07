package com.travelrisk.platform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fixed inputs behind "What should I do instead?": which airports count as nearby, which parts of
 * the day are compared, and how a forecast change is explained. Scoring itself stays in
 * {@link TravelRiskService} so every option is judged by the same rules as the original check.
 */
final class TripAlternatives {
  private TripAlternatives() {}

  /** Forecast values that drive the weather and wind signals, for a whole day or one window of it. */
  record Metrics(Double precipitationProbability, Double precipitation, Double windKmh, Double gustKmh) {}

  /** A part of the day compared for departure time, in each place's local time. */
  record Window(String key, String label, int fromHour, int toHour) {}

  static final List<Window> WINDOWS = List.of(
      new Window("morning", "morning (6 AM to noon)", 6, 12),
      new Window("afternoon", "afternoon (noon to 6 PM)", 12, 18),
      new Window("evening", "evening (6 PM to midnight)", 18, 24));

  // Commercial airports that serve the same metro, matching the airport choices in the trip form.
  private static final Map<String, List<String>> NEARBY_AIRPORTS = Map.ofEntries(
      Map.entry("KJFK", List.of("KEWR", "KLGA")),
      Map.entry("KLGA", List.of("KJFK", "KEWR")),
      Map.entry("KEWR", List.of("KJFK", "KLGA")),
      Map.entry("KSFO", List.of("KOAK", "KSJC")),
      Map.entry("KOAK", List.of("KSFO", "KSJC")),
      Map.entry("KSJC", List.of("KSFO", "KOAK")),
      Map.entry("KLAX", List.of("KBUR", "KLGB")),
      Map.entry("KBUR", List.of("KLAX", "KLGB")),
      Map.entry("KLGB", List.of("KLAX", "KBUR")),
      Map.entry("KORD", List.of("KMDW")),
      Map.entry("KMDW", List.of("KORD")),
      Map.entry("KDFW", List.of("KDAL")),
      Map.entry("KDAL", List.of("KDFW")),
      Map.entry("KIAH", List.of("KHOU")),
      Map.entry("KHOU", List.of("KIAH")),
      Map.entry("KMIA", List.of("KFLL")),
      Map.entry("KFLL", List.of("KMIA")),
      Map.entry("KDCA", List.of("KIAD", "KBWI")),
      Map.entry("KIAD", List.of("KDCA", "KBWI")),
      Map.entry("KBWI", List.of("KDCA", "KIAD")));

  private static final Map<String, String> AIRPORT_NAMES = Map.ofEntries(
      Map.entry("KJFK", "JFK"), Map.entry("KLGA", "LaGuardia"), Map.entry("KEWR", "Newark"),
      Map.entry("KSFO", "SFO"), Map.entry("KOAK", "Oakland"), Map.entry("KSJC", "San Jose"),
      Map.entry("KLAX", "LAX"), Map.entry("KBUR", "Burbank"), Map.entry("KLGB", "Long Beach"),
      Map.entry("KORD", "O'Hare"), Map.entry("KMDW", "Midway"),
      Map.entry("KDFW", "DFW"), Map.entry("KDAL", "Love Field"),
      Map.entry("KIAH", "Bush Intercontinental"), Map.entry("KHOU", "Hobby"),
      Map.entry("KMIA", "Miami"), Map.entry("KFLL", "Fort Lauderdale"),
      Map.entry("KDCA", "Reagan National"), Map.entry("KIAD", "Dulles"), Map.entry("KBWI", "BWI"));

  static List<String> nearbyAirports(String icao) {
    return NEARBY_AIRPORTS.getOrDefault(icao.toUpperCase(Locale.ROOT), List.of());
  }

  /** "Newark (EWR)", or just "JFK" when the name is the code. */
  static String airportName(String icao) {
    String code = iata(icao);
    String name = AIRPORT_NAMES.getOrDefault(icao, code);
    return name.equals(code) ? code : name + " (" + code + ")";
  }

  static String iata(String icao) {
    return icao.length() == 4 && icao.startsWith("K") ? icao.substring(1) : icao;
  }

  static Metrics dailyMetrics(Map<String, Object> forecast, String date) {
    Map<String, Object> daily = map(forecast.get("daily"));
    int index = list(daily.get("time")).indexOf(date);
    if (index < 0) return null;
    return new Metrics(
        at(daily.get("precipitation_probability_max"), index),
        at(daily.get("precipitation_sum"), index),
        at(daily.get("wind_speed_10m_max"), index),
        at(daily.get("wind_gusts_10m_max"), index));
  }

  /** The worst hour of each value inside the window, or null when the forecast has no hours for it. */
  static Metrics windowMetrics(Map<String, Object> forecast, String date, Window window) {
    Map<String, Object> hourly = map(forecast.get("hourly"));
    List<Object> times = list(hourly.get("time"));
    Double probability = null;
    Double precipitation = null;
    Double wind = null;
    Double gust = null;
    boolean found = false;
    for (int index = 0; index < times.size(); index += 1) {
      String time = String.valueOf(times.get(index));
      if (!time.startsWith(date + "T") || time.length() < 13) continue;
      int hour;
      try {
        hour = Integer.parseInt(time.substring(11, 13));
      } catch (NumberFormatException error) {
        continue;
      }
      if (hour < window.fromHour() || hour >= window.toHour()) continue;
      found = true;
      probability = max(probability, at(hourly.get("precipitation_probability"), index));
      precipitation = max(precipitation, at(hourly.get("precipitation"), index));
      wind = max(wind, at(hourly.get("wind_speed_10m"), index));
      gust = max(gust, at(hourly.get("wind_gusts_10m"), index));
    }
    return found ? new Metrics(probability, precipitation, wind, gust) : null;
  }

  /** "New York, NY: rain chance 85% → 20%, gusts 70 → 30 km/h" for the values that were driving risk. */
  static String forecastChange(String place, Metrics before, Metrics after, boolean hourly) {
    List<String> parts = new ArrayList<>();
    double mediumPrecip = hourly ? 2.5 : 10;
    String precipUnit = hourly ? " mm/h" : " mm";
    if (value(before.precipitationProbability()) >= 60 && value(after.precipitationProbability()) < value(before.precipitationProbability())) {
      parts.add("rain chance " + whole(before.precipitationProbability()) + "% → " + whole(after.precipitationProbability()) + "%");
    }
    if (value(before.precipitation()) >= mediumPrecip && value(after.precipitation()) < value(before.precipitation())) {
      parts.add((hourly ? "heaviest rain " : "rain ") + oneDecimal(before.precipitation()) + " → "
          + oneDecimal(after.precipitation()) + precipUnit);
    }
    if (value(before.gustKmh()) >= 55 && value(after.gustKmh()) < value(before.gustKmh())) {
      parts.add("gusts " + whole(before.gustKmh()) + " → " + whole(after.gustKmh()) + " km/h");
    } else if (value(before.windKmh()) >= 40 && value(after.windKmh()) < value(before.windKmh())) {
      parts.add("wind " + whole(before.windKmh()) + " → " + whole(after.windKmh()) + " km/h");
    }
    return parts.isEmpty() ? "" : place + ": " + String.join(", ", parts);
  }

  static String flightCategoryText(String category) {
    return switch (category) {
      case "VFR" -> "clear flying weather (VFR)";
      case "MVFR" -> "marginal flying weather (MVFR)";
      case "IFR" -> "low clouds or poor visibility (IFR)";
      case "LIFR" -> "very low clouds or visibility (LIFR)";
      default -> "unclear flying weather";
    };
  }

  private static String whole(Double value) {
    return String.valueOf(Math.round(value(value)));
  }

  private static String oneDecimal(Double value) {
    return String.format(Locale.ROOT, "%.1f", value(value));
  }

  private static double value(Double number) {
    return number == null ? 0 : number;
  }

  private static Double max(Double current, Double next) {
    if (next == null) return current;
    return current == null ? next : Math.max(current, next);
  }

  private static Double at(Object values, int index) {
    List<Object> items = list(values);
    if (index >= items.size() || items.get(index) == null) return null;
    try {
      double parsed = Double.parseDouble(String.valueOf(items.get(index)));
      return Double.isFinite(parsed) ? parsed : null;
    } catch (NumberFormatException error) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return value instanceof List<?> items ? (List<Object>) items : List.of();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
  }
}
