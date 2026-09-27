package com.travelrisk.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class TravelRiskService {
  private static final Pattern AIRPORT_CODE = Pattern.compile("^[A-Z0-9]{3,4}$");

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final String userAgent;
  private final String openAiApiKey;
  private final String openAiModel;

  private final Map<String, GeoPoint> knownLocations = Map.ofEntries(
      city("new york, ny", "New York, NY, United States", 40.7128, -74.0060),
      city("san francisco, ca", "San Francisco, CA, United States", 37.7749, -122.4194),
      city("seattle, wa", "Seattle, WA, United States", 47.6062, -122.3321),
      city("dallas, tx", "Dallas, TX, United States", 32.7767, -96.7970),
      city("chicago, il", "Chicago, IL, United States", 41.8781, -87.6298),
      city("los angeles, ca", "Los Angeles, CA, United States", 34.0522, -118.2437),
      city("atlanta, ga", "Atlanta, GA, United States", 33.7490, -84.3880),
      city("boston, ma", "Boston, MA, United States", 42.3601, -71.0589),
      city("denver, co", "Denver, CO, United States", 39.7392, -104.9903),
      city("miami, fl", "Miami, FL, United States", 25.7617, -80.1918),
      city("washington, dc", "Washington, DC, United States", 38.9072, -77.0369),
      city("houston, tx", "Houston, TX, United States", 29.7604, -95.3698),
      city("phoenix, az", "Phoenix, AZ, United States", 33.4484, -112.0740),
      city("las vegas, nv", "Las Vegas, NV, United States", 36.1699, -115.1398),
      city("orlando, fl", "Orlando, FL, United States", 28.5383, -81.3792),
      city("philadelphia, pa", "Philadelphia, PA, United States", 39.9526, -75.1652),
      city("minneapolis, mn", "Minneapolis, MN, United States", 44.9778, -93.2650),
      city("charlotte, nc", "Charlotte, NC, United States", 35.2271, -80.8431),
      city("portland, or", "Portland, OR, United States", 45.5152, -122.6784),
      city("austin, tx", "Austin, TX, United States", 30.2672, -97.7431));

  public TravelRiskService(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      @Value("${travel-risk.user-agent}") String userAgent,
      @Value("${travel-risk.openai.api-key}") String openAiApiKey,
      @Value("${travel-risk.openai.model}") String openAiModel) {
    this.restClient = restClientBuilder.defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
    this.objectMapper = objectMapper;
    this.userAgent = userAgent;
    this.openAiApiKey = openAiApiKey;
    this.openAiModel = openAiModel;
  }

  @Cacheable(value = "tripAssessments", key = "{#rawOrigin, #rawDestination, #rawDate, #rawMode, #rawOriginAirport, #rawDestinationAirport}", sync = true)
  public Assessment analyze(String rawOrigin, String rawDestination, String rawDate, String rawMode,
      String rawOriginAirport, String rawDestinationAirport) {
    String origin = clean(rawOrigin);
    String destination = clean(rawDestination);
    String date = clean(rawDate);
    String mode = clean(rawMode).isBlank() ? "flight" : clean(rawMode);
    String originAirport = cleanAirport(rawOriginAirport);
    String destinationAirport = cleanAirport(rawDestinationAirport);

    if (origin.isBlank() || destination.isBlank() || date.isBlank()) {
      throw new IllegalArgumentException("origin, destination, and date are required");
    }
    validateTravelDate(date);

    GeoPoint originGeo = geocode(origin);
    GeoPoint destinationGeo = geocode(destination);
    GeoPoint midpoint = new GeoPoint("Route midpoint",
        (originGeo.lat() + destinationGeo.lat()) / 2,
        (originGeo.lon() + destinationGeo.lon()) / 2,
        "Calculated route midpoint");

    Bundle originWeather = safeBundle(
        () -> getOpenMeteo(originGeo, date, "Origin forecast"),
        "Open-Meteo Forecast API",
        "Origin forecast",
        originGeo.label());
    Bundle destinationWeather = safeBundle(
        () -> getOpenMeteo(destinationGeo, date, "Destination forecast"),
        "Open-Meteo Forecast API",
        "Destination forecast",
        destinationGeo.label());
    Bundle midpointWeather = safeBundle(
        () -> getOpenMeteo(midpoint, date, "Route midpoint forecast"),
        "Open-Meteo Forecast API",
        "Route midpoint forecast",
        midpoint.label());
    Bundle originNws = safeBundle(
        () -> getNwsBundle(originGeo, "Origin NWS"),
        "National Weather Service API",
        "Origin NWS",
        originGeo.label());
    Bundle destinationNws = safeBundle(
        () -> getNwsBundle(destinationGeo, "Destination NWS"),
        "National Weather Service API",
        "Destination NWS",
        destinationGeo.label());
    Bundle aviation = safeBundle(
        () -> getAviationBundle(originGeo, destinationGeo, originAirport, destinationAirport),
        "Aviation Weather Center API",
        "Airport weather",
        originGeo.label() + " and " + destinationGeo.label());

    List<Evidence> evidence = new ArrayList<>();
    List<Signal> signals = new ArrayList<>();
    for (Bundle bundle : List.of(originWeather, destinationWeather, midpointWeather, originNws, destinationNws, aviation)) {
      evidence.addAll(bundle.evidence());
      signals.addAll(bundle.signals());
    }

    Score score = scoreSignals(signals, mode);
    Synthesis synthesis = synthesize(new SynthesisContext(originGeo, destinationGeo, date, mode, score, signals, evidence));

    return new Assessment(
        new Input(origin, destination, date, mode, originAirport, destinationAirport),
        new Route(originGeo, destinationGeo, midpoint),
        score,
        synthesis.summary(),
        synthesis.recommendation(),
        synthesis.uncertainty(),
        synthesis.ai(),
        signals,
        evidence,
        List.of(
            new Source("OpenStreetMap Nominatim", "Geocodes user-entered route locations", "https://nominatim.openstreetmap.org/"),
            new Source("Open-Meteo Forecast API", "Hourly and daily weather forecast for origin, midpoint, and destination", "https://open-meteo.com/"),
            new Source("National Weather Service API", "Active alerts and official point forecasts", "https://www.weather.gov/documentation/services-web-api"),
            new Source("Aviation Weather Center API", "METAR airport weather observations near the route endpoints", "https://aviationweather.gov/data/api/")));
  }

  @CachePut(value = "tripAssessments", key = "{#rawOrigin, #rawDestination, #rawDate, #rawMode, #rawOriginAirport, #rawDestinationAirport}")
  public Assessment refreshAssessment(String rawOrigin, String rawDestination, String rawDate, String rawMode,
      String rawOriginAirport, String rawDestinationAirport) {
    return analyze(rawOrigin, rawDestination, rawDate, rawMode, rawOriginAirport, rawDestinationAirport);
  }

  @CacheEvict(value = "tripAssessments", key = "{#rawOrigin, #rawDestination, #rawDate, #rawMode, #rawOriginAirport, #rawDestinationAirport}")
  public void evictAssessment(String rawOrigin, String rawDestination, String rawDate, String rawMode,
      String rawOriginAirport, String rawDestinationAirport) {
  }

  private GeoPoint geocode(String query) {
    GeoPoint known = knownLocations.get(query.toLowerCase(Locale.ROOT));
    if (known != null) {
      return known;
    }

    String url = UriComponentsBuilder.fromUriString("https://nominatim.openstreetmap.org/search")
        .queryParam("q", query)
        .queryParam("format", "jsonv2")
        .queryParam("addressdetails", "1")
        .queryParam("countrycodes", "us")
        .queryParam("limit", "1")
        .toUriString();
    List<Map<String, Object>> rows = getList(url);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("Could not geocode location: " + query);
    }
    Map<String, Object> item = rows.getFirst();
    return new GeoPoint(
        string(item.get("display_name")),
        number(item.get("lat")),
        number(item.get("lon")),
        "OpenStreetMap Nominatim");
  }

  private Bundle getOpenMeteo(GeoPoint point, String date, String label) {
    String url = UriComponentsBuilder.fromUriString("https://api.open-meteo.com/v1/forecast")
        .queryParam("latitude", point.lat())
        .queryParam("longitude", point.lon())
        .queryParam("timezone", "auto")
        .queryParam("start_date", date)
        .queryParam("end_date", date)
        .queryParam("hourly", "temperature_2m,precipitation_probability,precipitation,wind_speed_10m,wind_gusts_10m,visibility")
        .queryParam("daily", "temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max")
        .toUriString();
    Map<String, Object> data = getMap(url);
    Map<String, Object> daily = map(data.get("daily"));
    List<Evidence> evidence = new ArrayList<>();
    List<Signal> signals = new ArrayList<>();

    Double precipProbability = firstNumber(daily.get("precipitation_probability_max"));
    Double precipSum = firstNumber(daily.get("precipitation_sum"));
    Double windMax = firstNumber(daily.get("wind_speed_10m_max"));
    Double gustMax = firstNumber(daily.get("wind_gusts_10m_max"));
    Double tempMax = firstNumber(daily.get("temperature_2m_max"));
    Double tempMin = firstNumber(daily.get("temperature_2m_min"));

    evidence.add(new Evidence(
        "Open-Meteo Forecast API",
        label,
        severityFromWeather(precipProbability, precipSum, windMax, gustMax),
        "%s: %s-%s C, %s%% precipitation risk, %s mm precipitation, max wind %s km/h"
            .formatted(label, displayValue(tempMin), displayValue(tempMax), displayValue(precipProbability, "?", 0), displayValue(precipSum), displayValue(windMax)),
        orderedMap(
            "location", point.label(),
            "date", date,
            "precipitationProbabilityPercent", precipProbability,
            "precipitationMm", precipSum,
            "maxWindKmh", windMax,
            "maxGustKmh", gustMax,
            "maxTempC", tempMax,
            "minTempC", tempMin),
        url));

    if (value(precipProbability) >= 60 || value(precipSum) >= 10) {
      signals.add(new Signal("weather", value(precipProbability) >= 80 || value(precipSum) >= 25 ? "high" : "medium",
          label + " has elevated precipitation risk.", "Open-Meteo daily forecast"));
    }
    if (value(windMax) >= 40 || value(gustMax) >= 55) {
      signals.add(new Signal("wind", value(windMax) >= 55 || value(gustMax) >= 75 ? "high" : "medium",
          label + " has potentially disruptive wind.", "Open-Meteo wind forecast"));
    }
    return new Bundle(evidence, signals);
  }

  private Bundle getNwsBundle(GeoPoint point, String label) {
    List<Evidence> evidence = new ArrayList<>();
    List<Signal> signals = new ArrayList<>();
    try {
      String pointUrl = "https://api.weather.gov/points/%.4f,%.4f".formatted(point.lat(), point.lon());
      Map<String, Object> pointData = getMap(pointUrl);
      String forecastUrl = string(map(pointData.get("properties")).get("forecast"));
      if (!forecastUrl.isBlank()) {
        Map<String, Object> forecast = getMap(forecastUrl);
        List<Map<String, Object>> periods = listOfMaps(map(forecast.get("properties")).get("periods"));
        List<Map<String, Object>> nextPeriods = periods.stream().limit(4).map(period -> orderedMap(
            "name", period.get("name"),
            "shortForecast", period.get("shortForecast"),
            "detailedForecast", period.get("detailedForecast"),
            "windSpeed", period.get("windSpeed"),
            "probabilityOfPrecipitation", map(period.get("probabilityOfPrecipitation")).get("value"))).toList();
        if (!nextPeriods.isEmpty()) {
          evidence.add(new Evidence("National Weather Service API", label + " forecast", "info",
              label + ": " + string(nextPeriods.getFirst().get("shortForecast")), nextPeriods, forecastUrl));
        }
      }

      String alertsUrl = UriComponentsBuilder.fromUriString("https://api.weather.gov/alerts/active")
          .queryParam("point", point.lat() + "," + point.lon())
          .toUriString();
      Map<String, Object> alerts = getMap(alertsUrl);
      for (Map<String, Object> alert : listOfMaps(alerts.get("features")).stream().limit(5).toList()) {
        Map<String, Object> props = map(alert.get("properties"));
        String severity = nwsSeverity(string(props.get("severity")), string(props.get("urgency")));
        String headline = firstNonBlank(string(props.get("headline")), string(props.get("event")), "Active weather alert");
        evidence.add(new Evidence(
            "National Weather Service API",
            label + " alert",
            severity,
            headline,
            orderedMap("event", props.get("event"), "severity", props.get("severity"), "urgency", props.get("urgency"),
                "areas", props.get("areaDesc"), "instruction", props.get("instruction")),
            firstNonBlank(string(props.get("uri")), alertsUrl)));
        if (!isStaleNwsAlert(props)) {
          signals.add(new Signal("official-alert", severity,
              label + " has active NWS alert: " + firstNonBlank(string(props.get("event")), string(props.get("headline"))),
              headline));
        }
      }
    } catch (Exception error) {
      evidence.add(new Evidence("National Weather Service API", label, "unknown",
          "NWS data unavailable for " + point.label(), Map.of("error", error.getMessage()), "https://api.weather.gov/"));
    }
    return new Bundle(evidence, signals);
  }

  private Bundle getAviationBundle(GeoPoint origin, GeoPoint destination, String originAirport, String destinationAirport) {
    Bundle originData = getNearestMetars(origin, "Origin airport weather", originAirport);
    Bundle destinationData = getNearestMetars(destination, "Destination airport weather", destinationAirport);
    List<Evidence> evidence = new ArrayList<>(originData.evidence());
    evidence.addAll(destinationData.evidence());
    List<Signal> signals = new ArrayList<>(originData.signals());
    signals.addAll(destinationData.signals());
    return new Bundle(evidence, signals);
  }

  private Bundle getNearestMetars(GeoPoint point, String label, String preferredIcao) {
    List<Evidence> evidence = new ArrayList<>();
    List<Signal> signals = new ArrayList<>();
    double latDelta = 1.5;
    double lonDelta = 1.5;
    String bbox = "%s,%s,%s,%s".formatted(point.lat() - latDelta, point.lon() - lonDelta, point.lat() + latDelta, point.lon() + lonDelta);
    String url = UriComponentsBuilder.fromUriString("https://aviationweather.gov/api/data/metar")
        .queryParam("bbox", bbox)
        .queryParam("format", "json")
        .toUriString();
    try {
      List<Map<String, Object>> rows = getList(url);
      List<Map<String, Object>> nearest = rows.stream()
          .filter(row -> Double.isFinite(number(row.get("lat"))) && Double.isFinite(number(row.get("lon"))))
          .peek(row -> row.put("distanceKm", haversineKm(point.lat(), point.lon(), number(row.get("lat")), number(row.get("lon")))))
          .sorted(Comparator.comparingDouble(row -> number(row.get("distanceKm"))))
          .limit(3)
          .toList();
      if (!preferredIcao.isBlank()) {
        List<Map<String, Object>> preferred = rows.stream()
            .filter(row -> preferredIcao.equalsIgnoreCase(string(row.get("icaoId"))))
            .filter(row -> Double.isFinite(number(row.get("lat"))) && Double.isFinite(number(row.get("lon"))))
            .peek(row -> row.put("distanceKm", haversineKm(point.lat(), point.lon(), number(row.get("lat")), number(row.get("lon")))))
            .limit(1)
            .toList();
        if (!preferred.isEmpty()) nearest = preferred;
      }

      for (Map<String, Object> metar : nearest) {
        String severity = severityFromFlightCategory(string(metar.get("fltCat")), metar.get("wspd"), metar.get("wgst"), metar.get("visib"));
        String station = firstNonBlank(string(metar.get("icaoId")), "Airport");
        evidence.add(new Evidence(
            "Aviation Weather Center API",
            label,
            severity,
            station + " " + firstNonBlank(string(metar.get("fltCat")), "weather") + ": " + firstNonBlank(string(metar.get("rawOb")), "METAR observation"),
            orderedMap("station", metar.get("icaoId"), "name", metar.get("name"), "flightCategory", metar.get("fltCat"),
                "windKt", metar.get("wspd"), "gustKt", metar.get("wgst"), "visibilitySm", metar.get("visib"),
                "weather", metar.get("wxString"), "distanceKm", Math.round(number(metar.get("distanceKm")))),
            url));
        if (List.of("medium", "high").contains(severity)) {
          signals.add(new Signal("aviation-weather", severity,
              label + " near " + station + ": " + firstNonBlank(string(metar.get("fltCat")), "weather") + " conditions.",
              firstNonBlank(string(metar.get("rawOb")), station + " METAR")));
        }
      }

      if (nearest.isEmpty()) {
        evidence.add(new Evidence("Aviation Weather Center API", label, "unknown",
            "No nearby METAR stations returned for " + point.label(), Map.of("bbox", bbox), url));
      }
    } catch (Exception error) {
      evidence.add(new Evidence("Aviation Weather Center API", label, "unknown",
          "Aviation weather unavailable near " + point.label(), Map.of("error", error.getMessage()), url));
    }
    return new Bundle(evidence, signals);
  }

  private Score scoreSignals(List<Signal> signals, String mode) {
    Map<String, Integer> weights = Map.of("low", 1, "info", 0, "unknown", 0, "medium", 3, "high", 6);
    int points = 0;
    for (Signal signal : signals) {
      int basePoints = weights.getOrDefault(signal.severity(), 0);
      int modeBonus = "flight".equals(mode) && "aviation-weather".equals(signal.type()) ? 1 : 0;
      signal.points(basePoints + modeBonus);
      points += signal.points();
    }
    String level = points >= 10 ? "High" : points >= 5 ? "Medium" : "Low";
    String confidence = signals.size() >= 4 ? "medium-high" : signals.size() >= 2 ? "medium" : "low-medium";
    return new Score(points, level, confidence);
  }

  private Synthesis synthesize(SynthesisContext context) {
    if (openAiApiKey != null && !openAiApiKey.isBlank()) {
      try {
        Map<String, Object> response = restClient.post()
            .uri("https://api.openai.com/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
            .body(openAiPayload(context))
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
        String text = firstNonBlank(string(response == null ? "" : response.get("output_text")), extractOutputText(response));
        Map<String, Object> parsed = objectMapper.readValue(text, new TypeReference<>() {});
        return new Synthesis(
            string(parsed.get("summary")),
            string(parsed.get("recommendation")),
            string(parsed.get("uncertainty")),
            Map.of("used", true, "provider", "OpenAI Responses API"));
      } catch (Exception error) {
        Synthesis fallback = localSynthesis(context);
        return fallback.withAi(Map.of("used", false, "provider", "local fallback", "error", error.getMessage()));
      }
    }
    return localSynthesis(context).withAi(Map.of("used", false, "provider", "local fallback", "reason", "OPENAI_API_KEY not set"));
  }

  private Map<String, Object> openAiPayload(SynthesisContext context) {
    List<Map<String, Object>> evidence = context.evidence().stream()
        .map(item -> orderedMap("source", item.source(), "severity", item.severity(), "headline", item.headline()))
        .toList();
    return orderedMap(
        "model", openAiModel,
        "instructions", "You are a travel operations risk analyst. Summarize travel disruption risk using only the supplied evidence. Be concise, mention uncertainty, and recommend practical action.",
        "input", objectToJson(orderedMap(
            "route", orderedMap("origin", context.origin().label(), "destination", context.destination().label(), "date", context.date(), "mode", context.mode()),
            "score", context.score(),
            "signals", context.signals(),
            "evidence", evidence)),
        "max_output_tokens", 350,
        "text", orderedMap("format", orderedMap(
            "type", "json_schema",
            "name", "travel_risk_summary",
            "schema", orderedMap(
                "type", "object",
                "additionalProperties", false,
                "properties", orderedMap(
                    "summary", Map.of("type", "string"),
                    "recommendation", Map.of("type", "string"),
                    "uncertainty", Map.of("type", "string")),
                "required", List.of("summary", "recommendation", "uncertainty")))));
  }

  private Synthesis localSynthesis(SynthesisContext context) {
    List<Signal> topSignals = context.signals().stream()
        .sorted((a, b) -> Integer.compare(severityRank(b.severity()), severityRank(a.severity())))
        .limit(3)
        .toList();
    String recommendation = switch (context.score().level()) {
      case "High" -> "Consider alternate timing or routing, monitor official alerts closely, and confirm flight or road status before departure.";
      case "Medium" -> "Proceed with caution, build in extra time, and recheck conditions closer to departure.";
      default -> "Trip risk appears manageable based on currently available evidence; still recheck conditions before leaving.";
    };
    String summary = topSignals.isEmpty()
        ? context.score().level() + " disruption risk. No major risk signals were found in the current data sources."
        : context.score().level() + " disruption risk, driven by " + readableDriverText(topSignals) + ".";
    return new Synthesis(summary, recommendation,
        "This platform combines live weather and aviation signals. It does not include airline-specific operations, booked flight status, road closures, or private corporate policies.",
        Map.of("used", false, "provider", "local fallback"));
  }

  private String readableDriverText(List<Signal> signals) {
    List<String> labels = signals.stream().map(signal -> {
      if ("weather".equals(signal.type())) return "heavy precipitation forecast at the origin";
      if ("wind".equals(signal.type())) return "potentially disruptive wind";
      if ("aviation-weather".equals(signal.type())) return "airport weather conditions";
      if ("official-alert".equals(signal.type())) return "an active official weather alert";
      return signal.message().toLowerCase(Locale.ROOT);
    }).toList();
    if (labels.size() <= 1) return labels.isEmpty() ? "current weather conditions" : labels.getFirst();
    if (labels.size() == 2) return labels.get(0) + " and " + labels.get(1);
    return String.join(", ", labels.subList(0, labels.size() - 1)) + ", and " + labels.getLast();
  }

  private Bundle safeBundle(BundleLoader loader, String source, String label, String location) {
    try {
      return loader.load();
    } catch (Exception error) {
      return new Bundle(List.of(new Evidence(source, label, "unknown", label + " data unavailable",
          Map.of("location", location, "reason", error.getMessage()), "")), List.of());
    }
  }

  private Map<String, Object> getMap(String url) {
    return restClient.get()
        .uri(url)
        .accept(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.USER_AGENT, userAgent)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }

  private List<Map<String, Object>> getList(String url) {
    return restClient.get()
        .uri(url)
        .accept(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.USER_AGENT, userAgent)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
  }

  private void validateTravelDate(String date) {
    LocalDate requested;
    try {
      requested = LocalDate.parse(date);
    } catch (Exception error) {
      throw new IllegalArgumentException("Travel date must use YYYY-MM-DD format.");
    }
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate maxForecastDate = today.plusDays(15);
    if (requested.isBefore(today)) {
      throw new IllegalArgumentException("Travel date must be today or later.");
    }
    if (requested.isAfter(maxForecastDate)) {
      throw new IllegalArgumentException("Travel date is too far out for the live forecast window. This platform can assess trips through "
          + maxForecastDate + " because the weather API only provides forecast data for about the next 15 days.");
    }
  }

  private static Map.Entry<String, GeoPoint> city(String key, String label, double lat, double lon) {
    return Map.entry(key, new GeoPoint(label, lat, lon, "Built-in city coordinates"));
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static String cleanAirport(String value) {
    String cleaned = clean(value).toUpperCase(Locale.ROOT);
    return AIRPORT_CODE.matcher(cleaned).matches() ? cleaned : "";
  }

  private static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> raw) {
      Map<String, Object> result = new LinkedHashMap<>();
      raw.forEach((key, item) -> result.put(String.valueOf(key), item));
      return result;
    }
    return Map.of();
  }

  private static List<Map<String, Object>> listOfMaps(Object value) {
    if (!(value instanceof List<?> list)) return List.of();
    return list.stream().map(TravelRiskService::map).filter(item -> !item.isEmpty()).toList();
  }

  private static Double firstNumber(Object value) {
    if (value instanceof List<?> list && !list.isEmpty()) return finiteNumber(list.getFirst());
    return finiteNumber(value);
  }

  private static double number(Object value) {
    Double parsed = finiteNumber(value);
    return parsed == null ? Double.NaN : parsed;
  }

  private static Double finiteNumber(Object value) {
    if (value == null) return null;
    try {
      double parsed = Double.parseDouble(String.valueOf(value).replace("+", ""));
      return Double.isFinite(parsed) ? parsed : null;
    } catch (NumberFormatException error) {
      return null;
    }
  }

  private static double value(Double number) {
    return number == null ? 0 : number;
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return "";
  }

  private static String displayValue(Object value) {
    return displayValue(value, "?", null);
  }

  private static String displayValue(Object value, String fallback, Integer precision) {
    if (value == null) return fallback;
    if (precision != null && value instanceof Number number) {
      return String.format(Locale.ROOT, "%." + precision + "f", number.doubleValue());
    }
    return String.valueOf(value);
  }

  private static String severityFromWeather(Double precip, Double precipAmount, Double wind, Double gust) {
    if (value(precip) >= 80 || value(precipAmount) >= 25 || value(wind) >= 55 || value(gust) >= 75) return "high";
    if (value(precip) >= 60 || value(precipAmount) >= 10 || value(wind) >= 40 || value(gust) >= 55) return "medium";
    return "low";
  }

  private static String nwsSeverity(String severity, String urgency) {
    if (List.of("Extreme", "Severe").contains(severity) || "Immediate".equals(urgency)) return "high";
    if ("Moderate".equals(severity) || "Expected".equals(urgency)) return "medium";
    return "low";
  }

  private static boolean isStaleNwsAlert(Map<String, Object> props) {
    String text = (string(props.get("headline")) + " " + string(props.get("description")) + " " + string(props.get("instruction"))).toLowerCase(Locale.ROOT);
    return "Past".equals(props.get("urgency")) || text.contains("has been replaced") || text.contains("expired");
  }

  private static String severityFromFlightCategory(String category, Object wind, Object gust, Object visibility) {
    double vis = number(visibility);
    if ("LIFR".equals(category) || "IFR".equals(category) || number(gust) >= 35 || number(wind) >= 30 || vis < 3) return "high";
    if ("MVFR".equals(category) || number(gust) >= 25 || number(wind) >= 20 || vis < 6) return "medium";
    return "low";
  }

  private static int severityRank(String severity) {
    return switch (severity) {
      case "high" -> 3;
      case "medium" -> 2;
      case "low" -> 1;
      default -> 0;
    };
  }

  private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
    double radius = 6371;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  private String extractOutputText(Map<String, Object> data) {
    if (data == null) return "";
    List<String> parts = new ArrayList<>();
    for (Map<String, Object> outputItem : listOfMaps(data.get("output"))) {
      for (Map<String, Object> content : listOfMaps(outputItem.get("content"))) {
        if ("output_text".equals(content.get("type")) && content.get("text") != null) {
          parts.add(String.valueOf(content.get("text")));
        }
      }
    }
    return String.join("\n", parts);
  }

  private String objectToJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception error) {
      return "{}";
    }
  }

  private static Map<String, Object> orderedMap(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length - 1; index += 2) {
      if (pairs[index + 1] != null) map.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return map;
  }

  @FunctionalInterface
  private interface BundleLoader {
    Bundle load();
  }

  private record Bundle(List<Evidence> evidence, List<Signal> signals) {}

  private record SynthesisContext(GeoPoint origin, GeoPoint destination, String date, String mode, Score score,
      List<Signal> signals, List<Evidence> evidence) {}

  public record Assessment(Input input, Route route, Score score, String summary, String recommendation,
      String uncertainty, Map<String, Object> ai, List<Signal> signals, List<Evidence> evidence, List<Source> sources) {}

  public record Input(String origin, String destination, String date, String mode, String originAirport, String destinationAirport) {}

  public record Route(GeoPoint origin, GeoPoint destination, GeoPoint midpoint) {}

  public record GeoPoint(String label, double lat, double lon, String source) {}

  public record Score(int points, String level, String confidence) {}

  public record Evidence(String source, String label, String severity, String headline, Object details, String url) {}

  public record Source(String name, String purpose, String url) {}

  public static final class Signal {
    private final String type;
    private final String severity;
    private final String message;
    private final String evidence;
    private int points;

    public Signal(String type, String severity, String message, String evidence) {
      this.type = type;
      this.severity = severity;
      this.message = message;
      this.evidence = evidence;
    }

    public String type() {
      return type;
    }

    public String getType() {
      return type;
    }

    public String severity() {
      return severity;
    }

    public String getSeverity() {
      return severity;
    }

    public String message() {
      return message;
    }

    public String getMessage() {
      return message;
    }

    public String evidence() {
      return evidence;
    }

    public String getEvidence() {
      return evidence;
    }

    public int points() {
      return points;
    }

    public int getPoints() {
      return points;
    }

    public void points(int points) {
      this.points = points;
    }
  }

  private record Synthesis(String summary, String recommendation, String uncertainty, Map<String, Object> ai) {
    Synthesis withAi(Map<String, Object> nextAi) {
      return new Synthesis(summary, recommendation, uncertainty, nextAi);
    }
  }
}
