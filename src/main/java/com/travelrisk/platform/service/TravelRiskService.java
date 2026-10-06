package com.travelrisk.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.monitoring.SourceCheck;
import com.travelrisk.platform.monitoring.SourceHealthMonitor;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@Service
public class TravelRiskService {
  private static final Pattern AIRPORT_CODE = Pattern.compile("^[A-Z0-9]{3,4}$");
  private static final String UNAVAILABLE_STATUS = "Temporarily unavailable";
  private static final String NOT_CONFIGURED_STATUS = "Not configured";

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final String userAgent;
  private final String openAiApiKey;
  private final String openAiModel;
  private final String road511ApiKey;
  private final Consumer<List<SourceCheck>> sourceCheckListener;

  /** Every external source a check consults, in the order results show them. */
  public static final List<Source> SOURCES = List.of(
      new Source("OpenStreetMap Nominatim", "Geocodes user-entered route locations", "https://nominatim.openstreetmap.org/"),
      new Source("Open-Meteo Forecast API", "Hourly and daily weather forecast for origin, midpoint, and destination", "https://open-meteo.com/"),
      new Source("National Weather Service API", "Active alerts and official point forecasts", "https://www.weather.gov/documentation/services-web-api"),
      new Source("Aviation Weather Center API", "METAR airport weather observations near the route endpoints", "https://aviationweather.gov/data/api/"),
      new Source("FAA NAS Status API", "Live airport ground stops, delay programs, arrival/departure delays, and airport closures", "https://nasstatus.faa.gov/api/airport-status-information"),
      new Source("Road511 Traffic Data API", "Live traffic incidents and closures, where available", "https://www.road511.com/docs.html"));

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

  @Autowired
  public TravelRiskService(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      @Value("${travel-risk.user-agent}") String userAgent,
      @Value("${travel-risk.openai.api-key}") String openAiApiKey,
      @Value("${travel-risk.openai.model}") String openAiModel,
      @Value("${travel-risk.road511.api-key:}") String road511ApiKey,
      SourceHealthMonitor sourceHealthMonitor) {
    this(restClientBuilder, objectMapper, userAgent, openAiApiKey, openAiModel, road511ApiKey, sourceHealthMonitor::record);
  }

  public TravelRiskService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper, String userAgent,
      String openAiApiKey, String openAiModel, String road511ApiKey) {
    this(restClientBuilder, objectMapper, userAgent, openAiApiKey, openAiModel, road511ApiKey, checks -> {});
  }

  TravelRiskService(RestClient.Builder restClientBuilder, ObjectMapper objectMapper, String userAgent,
      String openAiApiKey, String openAiModel, String road511ApiKey, Consumer<List<SourceCheck>> sourceCheckListener) {
    this.restClient = restClientBuilder.defaultHeader(HttpHeaders.USER_AGENT, userAgent).build();
    this.objectMapper = objectMapper;
    this.userAgent = userAgent;
    this.openAiApiKey = openAiApiKey;
    this.openAiModel = openAiModel;
    this.road511ApiKey = road511ApiKey;
    this.sourceCheckListener = sourceCheckListener;
  }

  @Cacheable(value = "tripAssessments", key = "{#rawOrigin, #rawDestination, #rawDate, #rawMode, #rawOriginAirport, #rawDestinationAirport}",
      unless = "#result.degraded()")
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

    List<CompletableFuture<TimedBundle>> bundleFutures = List.of(
        bundleFuture(() -> getOpenMeteo(originGeo, date, "Origin forecast"),
            "Open-Meteo Forecast API", "Origin forecast", originGeo.label()),
        bundleFuture(() -> getOpenMeteo(destinationGeo, date, "Destination forecast"),
            "Open-Meteo Forecast API", "Destination forecast", destinationGeo.label()),
        bundleFuture(() -> getOpenMeteo(midpoint, date, "Route midpoint forecast"),
            "Open-Meteo Forecast API", "Route midpoint forecast", midpoint.label()),
        bundleFuture(() -> getNwsBundle(originGeo, "Origin NWS"),
            "National Weather Service API", "Origin NWS", originGeo.label()),
        bundleFuture(() -> getNwsBundle(destinationGeo, "Destination NWS"),
            "National Weather Service API", "Destination NWS", destinationGeo.label()),
        bundleFuture(() -> getAviationBundle(originGeo, destinationGeo, originAirport, destinationAirport),
            "Aviation Weather Center API", "Airport weather", originGeo.label() + " and " + destinationGeo.label()),
        bundleFuture(() -> getFaaNasBundle(originGeo, destinationGeo, originAirport, destinationAirport),
            "FAA NAS Status API", "Airport delay and closure status", originGeo.label() + " and " + destinationGeo.label()),
        bundleFuture(() -> getRoad511Bundle(originGeo, destinationGeo),
            "Road511 Traffic Data API", "Road closures", originGeo.label() + " and " + destinationGeo.label()));

    List<Evidence> evidence = new ArrayList<>();
    List<Signal> signals = new ArrayList<>();
    List<TimedBundle> results = bundleFutures.stream().map(CompletableFuture::join).toList();
    for (TimedBundle result : results) {
      evidence.addAll(result.bundle().evidence());
      signals.addAll(result.bundle().signals());
    }

    if ("flight".equals(mode)) {
      signals.removeIf(signal -> "road-closure".equals(signal.type()));
    }
    List<SourceCheck> dataSources = new ArrayList<>(summarizeSources(results));
    Score score = withSourceCoverage(scoreSignals(signals, mode), dataSources);
    long synthesisStart = System.nanoTime();
    Synthesis synthesis = synthesize(new SynthesisContext(originGeo, destinationGeo, date, mode, score, signals, evidence));
    dataSources.add(synthesisCheck(synthesis, elapsedMs(synthesisStart)));
    sourceCheckListener.accept(dataSources);

    return new Assessment(
        new Input(origin, destination, date, mode, originAirport, destinationAirport),
        new Route(originGeo, destinationGeo, midpoint),
        score,
        synthesis.summary(),
        synthesis.recommendation(),
        withUnavailableNote(synthesis.uncertainty(), dataSources),
        synthesis.ai(),
        signals,
        evidence,
        dataSources,
        SOURCES);
  }

  @CachePut(value = "tripAssessments", key = "{#rawOrigin, #rawDestination, #rawDate, #rawMode, #rawOriginAirport, #rawDestinationAirport}",
      unless = "#result.degraded()")
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
          "National Weather Service data could not be reached during this check.",
          orderedMap("location", point.label(), "status", UNAVAILABLE_STATUS), "https://api.weather.gov/"));
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

  private Bundle getFaaNasBundle(GeoPoint origin, GeoPoint destination, String originAirport, String destinationAirport) {
    List<Evidence> evidence = new ArrayList<>();
    List<Signal> signals = new ArrayList<>();
    Map<String, String> airports = orderedStringMap(
        "Origin FAA airport status", airportIata(firstNonBlank(originAirport, defaultAirportFor(origin))),
        "Destination FAA airport status", airportIata(firstNonBlank(destinationAirport, defaultAirportFor(destination))));
    String url = "https://nasstatus.faa.gov/api/airport-status-information";
    try {
      Document document = parseXml(getString(url));
      String updated = text(document.getDocumentElement(), "Update_Time");
      for (Map.Entry<String, String> airport : airports.entrySet()) {
        if (airport.getValue().isBlank()) {
          evidence.add(new Evidence("FAA NAS Status API", airport.getKey(), "unknown",
              "FAA NAS status skipped because no airport code was available.", Map.of("location", airport.getKey()), url));
          continue;
        }
        List<FaaEvent> events = faaEventsForAirport(document, airport.getValue());
        if (events.isEmpty()) {
          evidence.add(new Evidence("FAA NAS Status API", airport.getKey(), "low",
              airport.getValue() + ": no active FAA NAS delay, ground stop, or closure event.",
              orderedMap("airport", airport.getValue(), "updated", updated), url));
          continue;
        }
        for (FaaEvent event : events) {
          evidence.add(new Evidence("FAA NAS Status API", airport.getKey(), event.severity(),
              airport.getValue() + " " + event.kind() + ": " + event.summary(),
              orderedMap("airport", airport.getValue(), "eventType", event.kind(), "details", event.details(), "updated", updated), url));
          signals.add(new Signal("faa-airport-status", event.severity(),
              airport.getKey() + " has FAA NAS " + event.kind().toLowerCase(Locale.ROOT) + " at " + airport.getValue() + ".",
              event.summary()));
        }
      }
    } catch (Exception error) {
      evidence.add(new Evidence("FAA NAS Status API", "Airport delay and closure status", "unknown",
          "FAA airport delay and closure data could not be reached during this check.",
          orderedMap("status", UNAVAILABLE_STATUS, "nextStep", "Recheck this source before departure."), url));
    }
    return new Bundle(evidence, signals);
  }

  private Bundle getRoad511Bundle(GeoPoint origin, GeoPoint destination) {
    String jurisdiction = roadJurisdiction(origin, destination);
    if (road511ApiKey == null || road511ApiKey.isBlank()) {
      return new Bundle(List.of(new Evidence("Road511 Traffic Data API", "Road closures", "unknown",
          "Road closure data is not connected for this deployment.",
          orderedMap("jurisdiction", jurisdiction, "status", NOT_CONFIGURED_STATUS),
          "https://www.road511.com/docs.html")), List.of());
    }
    String url = UriComponentsBuilder.fromUriString("https://api.road511.com/api/v1/events")
        .queryParam("jurisdiction", jurisdiction)
        .queryParam("limit", "10")
        .toUriString();
    try {
      Map<String, Object> data = restClient.get()
          .uri(url)
          .accept(MediaType.APPLICATION_JSON)
          .header("X-API-Key", road511ApiKey)
          .retrieve()
          .body(new ParameterizedTypeReference<>() {});
      List<Evidence> evidence = new ArrayList<>();
      List<Signal> signals = new ArrayList<>();
      List<Map<String, Object>> events = listOfMaps(data == null ? null : data.get("data"));
      if (events.isEmpty()) {
        evidence.add(new Evidence("Road511 Traffic Data API", "Road closures", "low",
            "No live Road511 incidents or closures returned for " + jurisdiction + ".",
            orderedMap("jurisdiction", jurisdiction), url));
      }
      List<String> riskyTitles = new ArrayList<>();
      String worstSeverity = "low";
      Set<String> seenEvents = new HashSet<>();
      for (Map<String, Object> event : events) {
        String type = string(event.get("type"));
        String title = firstNonBlank(string(event.get("title")), string(event.get("description")), "Road event");
        String eventKey = firstNonBlank(string(event.get("id")), title + "|" + type + "|" + string(event.get("description")));
        if (!seenEvents.add(eventKey)) continue;
        String severity = roadSeverity(type, string(event.get("severity")), title);
        evidence.add(new Evidence("Road511 Traffic Data API", "Road closures", severity,
            title, event, url));
        if (List.of("medium", "high").contains(severity)) {
          riskyTitles.add(title);
          if (severityRank(severity) > severityRank(worstSeverity)) worstSeverity = severity;
        }
      }
      if (!riskyTitles.isEmpty()) {
        signals.add(roadClosureSignal(jurisdiction, worstSeverity, riskyTitles));
      }
      return new Bundle(evidence, signals);
    } catch (Exception error) {
      return new Bundle(List.of(new Evidence("Road511 Traffic Data API", "Road closures", "unknown",
          "Road closure data could not be reached during this check.",
          orderedMap("jurisdiction", jurisdiction, "status", UNAVAILABLE_STATUS, "nextStep", "Recheck route conditions before departure."), url)), List.of());
    }
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
          "Airport weather observations could not be reached during this check.",
          orderedMap("location", point.label(), "status", UNAVAILABLE_STATUS), url));
    }
    return new Bundle(evidence, signals);
  }

  private List<FaaEvent> faaEventsForAirport(Document document, String airport) {
    List<FaaEvent> events = new ArrayList<>();
    collectFaaEvents(document, events, "Program", airport, "Ground stop", "high", "Reason", "End_Time");
    collectFaaEvents(document, events, "Ground_Delay", airport, "Ground delay program", "high", "Reason", "Avg", "Max");
    collectFaaEvents(document, events, "Delay", airport, "Arrival/departure delay", "medium", "Reason");
    collectFaaEvents(document, events, "Airport", airport, "Airport closure", "high", "Reason", "Start", "Reopen");
    return events;
  }

  private void collectFaaEvents(Document document, List<FaaEvent> events, String tag, String airport, String kind,
      String severity, String... detailTags) {
    NodeList nodes = document.getElementsByTagName(tag);
    for (int index = 0; index < nodes.getLength(); index += 1) {
      if (!(nodes.item(index) instanceof Element element)) continue;
      if (!airport.equalsIgnoreCase(text(element, "ARPT"))) continue;
      Map<String, Object> details = new LinkedHashMap<>();
      for (String detailTag : detailTags) {
        String value = text(element, detailTag);
        if (!value.isBlank()) details.put(detailTag, value);
      }
      NodeList arrivalDeparture = element.getElementsByTagName("Arrival_Departure");
      if (arrivalDeparture.getLength() > 0 && arrivalDeparture.item(0) instanceof Element delay) {
        details.put("type", delay.getAttribute("Type"));
        details.put("min", text(delay, "Min"));
        details.put("max", text(delay, "Max"));
        details.put("trend", text(delay, "Trend"));
      }
      events.add(new FaaEvent(kind, severity, faaSummary(kind, details), details));
    }
  }

  private String faaSummary(String kind, Map<String, Object> details) {
    String reason = firstNonBlank(string(details.get("Reason")), string(details.get("reason")), "active event");
    String avg = string(details.get("Avg"));
    String max = firstNonBlank(string(details.get("Max")), string(details.get("max")));
    String end = string(details.get("End_Time"));
    String reopen = string(details.get("Reopen"));
    if (!avg.isBlank() || !max.isBlank()) return kind + " due to " + reason + ", average " + firstNonBlank(avg, "unknown") + ", max " + firstNonBlank(max, "unknown");
    if (!end.isBlank()) return kind + " due to " + reason + ", expected until " + end;
    if (!reopen.isBlank()) return kind + " due to " + reason + ", reopen " + reopen;
    return kind + " due to " + reason;
  }

  private String defaultAirportFor(GeoPoint point) {
    String label = point.label().toLowerCase(Locale.ROOT);
    if (label.contains("new york")) return "KJFK";
    if (label.contains("san francisco")) return "KSFO";
    if (label.contains("seattle")) return "KSEA";
    if (label.contains("dallas")) return "KDFW";
    if (label.contains("chicago")) return "KORD";
    if (label.contains("los angeles")) return "KLAX";
    if (label.contains("atlanta")) return "KATL";
    if (label.contains("boston")) return "KBOS";
    if (label.contains("denver")) return "KDEN";
    if (label.contains("miami")) return "KMIA";
    if (label.contains("washington")) return "KDCA";
    if (label.contains("houston")) return "KIAH";
    if (label.contains("phoenix")) return "KPHX";
    if (label.contains("las vegas")) return "KLAS";
    if (label.contains("orlando")) return "KMCO";
    if (label.contains("philadelphia")) return "KPHL";
    if (label.contains("minneapolis")) return "KMSP";
    if (label.contains("charlotte")) return "KCLT";
    if (label.contains("portland")) return "KPDX";
    if (label.contains("austin")) return "KAUS";
    return "";
  }

  private String airportIata(String airport) {
    String cleaned = cleanAirport(airport);
    if (cleaned.length() == 4 && cleaned.startsWith("K")) return cleaned.substring(1);
    return cleaned.length() == 3 ? cleaned : "";
  }

  private String roadJurisdiction(GeoPoint origin, GeoPoint destination) {
    String label = (origin.label() + " " + destination.label()).toLowerCase(Locale.ROOT);
    if (label.contains("california") || label.contains(", ca")) return "CA";
    if (label.contains("washington") || label.contains(", wa")) return "WA";
    if (label.contains("texas") || label.contains(", tx")) return "TX";
    if (label.contains("illinois") || label.contains(", il")) return "IL";
    if (label.contains("new york") || label.contains(", ny")) return "NY";
    if (label.contains("colorado") || label.contains(", co")) return "CO";
    if (label.contains("florida") || label.contains(", fl")) return "FL";
    if (label.contains("georgia") || label.contains(", ga")) return "GA";
    if (label.contains("massachusetts") || label.contains(", ma")) return "MA";
    if (label.contains("arizona") || label.contains(", az")) return "AZ";
    if (label.contains("nevada") || label.contains(", nv")) return "NV";
    if (label.contains("pennsylvania") || label.contains(", pa")) return "PA";
    if (label.contains("minnesota") || label.contains(", mn")) return "MN";
    if (label.contains("north carolina") || label.contains(", nc")) return "NC";
    if (label.contains("oregon") || label.contains(", or")) return "OR";
    return "CA";
  }

  // Road511 returns statewide events, so several closures are one road-conditions signal, not one signal each.
  static Signal roadClosureSignal(String jurisdiction, String severity, List<String> titles) {
    long distinct = titles.stream().distinct().count();
    String count = distinct == 1 ? "1 road closure or incident" : distinct + " road closures or incidents";
    return new Signal("road-closure", severity,
        count + " reported in " + jurisdiction + " right now.",
        String.join("; ", titles.stream().distinct().limit(3).toList()));
  }

  // Only full closures or major incidents are high; work zones and lane closures slow a trip but rarely stop it.
  static String roadSeverity(String type, String severity, String title) {
    String normalized = (type + " " + severity + " " + title).toLowerCase(Locale.ROOT);
    if (normalized.contains("full") || normalized.contains("critical") || normalized.contains("major")) return "high";
    if (normalized.contains("closure") || normalized.contains("incident") || normalized.contains("moderate")) return "medium";
    return "low";
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
        .filter(distinctBy(Signal::type))
        .limit(3)
        .toList();
    String confirm = confirmationText(topSignals);
    String recommendation = switch (context.score().level()) {
      case "High" -> "Consider alternate timing or routing, and confirm " + confirm + " before departure.";
      case "Medium" -> "Proceed with caution, build in extra time, and recheck " + confirm + " closer to departure.";
      default -> "Trip risk appears manageable based on currently available evidence; still recheck conditions before leaving.";
    };
    String summary = topSignals.isEmpty()
        ? context.score().level() + " disruption risk. No major risk signals were found in the current data sources."
        : context.score().level() + " disruption risk, driven by " + readableDriverText(topSignals) + ".";
    return new Synthesis(summary, recommendation,
        "Booked itinerary status, airline operations, and company policy are not checked.",
        Map.of("used", false, "provider", "local fallback"));
  }

  private static <T> Predicate<T> distinctBy(Function<T, Object> key) {
    Set<Object> seen = new HashSet<>();
    return item -> seen.add(key.apply(item));
  }

  private String confirmationText(List<Signal> signals) {
    List<String> checks = signals.stream().map(signal -> switch (signal.type()) {
      case "aviation-weather", "faa-airport-status" -> "flight and airport status";
      case "road-closure" -> "road conditions on your route";
      case "official-alert" -> "official weather alerts";
      default -> "the latest forecast";
    }).distinct().toList();
    if (checks.isEmpty()) return "conditions";
    if (checks.size() == 1) return checks.getFirst();
    return String.join(", ", checks.subList(0, checks.size() - 1)) + " and " + checks.getLast();
  }

  private String readableDriverText(List<Signal> signals) {
    List<String> labels = signals.stream().map(signal -> {
      if ("weather".equals(signal.type())) return "heavy precipitation forecast at the origin";
      if ("wind".equals(signal.type())) return "potentially disruptive wind";
      if ("aviation-weather".equals(signal.type())) return "airport weather conditions";
      if ("faa-airport-status".equals(signal.type())) return "live FAA airport delay or closure status";
      if ("road-closure".equals(signal.type())) return "live road closure or traffic incident data";
      if ("official-alert".equals(signal.type())) return "an active official weather alert";
      return signal.message().toLowerCase(Locale.ROOT);
    }).distinct().toList();
    if (labels.size() <= 1) return labels.isEmpty() ? "current weather conditions" : labels.getFirst();
    if (labels.size() == 2) return labels.get(0) + " and " + labels.get(1);
    return String.join(", ", labels.subList(0, labels.size() - 1)) + ", and " + labels.getLast();
  }

  private Bundle safeBundle(BundleLoader loader, String source, String label, String location) {
    try {
      return loader.load();
    } catch (Exception error) {
      return new Bundle(List.of(new Evidence(source, label, "unknown", label + " data unavailable",
          orderedMap("location", location, "status", UNAVAILABLE_STATUS), "")), List.of());
    }
  }

  private CompletableFuture<TimedBundle> bundleFuture(BundleLoader loader, String source, String label, String location) {
    return CompletableFuture.supplyAsync(() -> {
      long start = System.nanoTime();
      Bundle bundle = safeBundle(loader, source, label, location);
      return new TimedBundle(source, bundle, bundleStatus(bundle), elapsedMs(start));
    });
  }

  // Source clients never throw: a failed call becomes evidence whose details say it was unavailable.
  private static String bundleStatus(Bundle bundle) {
    List<String> statuses = bundle.evidence().stream()
        .map(item -> string(map(item.details()).get("status")))
        .toList();
    if (!statuses.isEmpty() && statuses.stream().allMatch(NOT_CONFIGURED_STATUS::equals)) return SourceCheck.NOT_CONFIGURED;
    long failed = statuses.stream().filter(UNAVAILABLE_STATUS::equals).count();
    if (failed == 0) return SourceCheck.OK;
    return failed == statuses.size() ? SourceCheck.UNAVAILABLE : SourceCheck.DEGRADED;
  }

  // One row per source; a source queried for several points is unavailable only if every call failed.
  static List<SourceCheck> summarizeSources(List<TimedBundle> results) {
    Map<String, List<TimedBundle>> bySource = new LinkedHashMap<>();
    results.forEach(result -> bySource.computeIfAbsent(result.source(), ignored -> new ArrayList<>()).add(result));
    return bySource.entrySet().stream().map(entry -> {
      Set<String> statuses = new HashSet<>(entry.getValue().stream().map(TimedBundle::status).toList());
      String status = statuses.size() == 1 ? statuses.iterator().next() : SourceCheck.DEGRADED;
      long durationMs = entry.getValue().stream().mapToLong(TimedBundle::durationMs).max().orElse(0);
      return new SourceCheck(entry.getKey(), status, durationMs);
    }).toList();
  }

  private SourceCheck synthesisCheck(Synthesis synthesis, long durationMs) {
    String status = openAiApiKey == null || openAiApiKey.isBlank() ? SourceCheck.NOT_CONFIGURED
        : Boolean.TRUE.equals(synthesis.ai().get("used")) ? SourceCheck.OK : SourceCheck.UNAVAILABLE;
    return new SourceCheck("OpenAI Responses API", status, durationMs);
  }

  // A clean result with missing sources is not a confident all-clear.
  private static Score withSourceCoverage(Score score, List<SourceCheck> dataSources) {
    boolean missing = dataSources.stream().anyMatch(SourceCheck::unavailable);
    return missing ? new Score(score.points(), score.level(), "low") : score;
  }

  static String withUnavailableNote(String uncertainty, List<SourceCheck> dataSources) {
    List<String> unavailable = dataSources.stream()
        .filter(SourceCheck::unavailable)
        .map(check -> check.name().replaceAll("\\s+API$", ""))
        .toList();
    if (unavailable.isEmpty()) return uncertainty;
    return uncertainty + " Not reachable during this check: " + String.join(", ", unavailable)
        + ". Recheck before departure.";
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
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

  private String getString(String url) {
    return restClient.get()
        .uri(url)
        .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.TEXT_PLAIN)
        .header(HttpHeaders.USER_AGENT, userAgent)
        .retrieve()
        .body(String.class);
  }

  private static Document parseXml(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    } catch (Exception error) {
      throw new IllegalStateException("Unable to parse FAA NAS XML feed", error);
    }
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

  private static String text(Element element, String tagName) {
    NodeList nodes = element.getElementsByTagName(tagName);
    if (nodes.getLength() == 0 || nodes.item(0) == null) return "";
    return firstNonBlank(nodes.item(0).getTextContent()).trim();
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

  private static Map<String, String> orderedStringMap(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length - 1; index += 2) {
      map.put(pairs[index], pairs[index + 1] == null ? "" : pairs[index + 1]);
    }
    return map;
  }

  @FunctionalInterface
  private interface BundleLoader {
    Bundle load();
  }

  private record Bundle(List<Evidence> evidence, List<Signal> signals) {}

  record TimedBundle(String source, Bundle bundle, String status, long durationMs) {
    TimedBundle(String source, String status, long durationMs) {
      this(source, new Bundle(List.of(), List.of()), status, durationMs);
    }
  }

  private record FaaEvent(String kind, String severity, String summary, Map<String, Object> details) {}

  private record SynthesisContext(GeoPoint origin, GeoPoint destination, String date, String mode, Score score,
      List<Signal> signals, List<Evidence> evidence) {}

  public record Assessment(Input input, Route route, Score score, String summary, String recommendation,
      String uncertainty, Map<String, Object> ai, List<Signal> signals, List<Evidence> evidence,
      List<SourceCheck> dataSources, List<Source> sources) {

    /** True when a data source could not be reached, so the result should not be cached. */
    public boolean degraded() {
      return dataSources != null && dataSources.stream().anyMatch(SourceCheck::unavailable);
    }
  }

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
