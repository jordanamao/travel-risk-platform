package com.travelrisk.platform.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.FrequentRoute;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.database.entities.TripNotification;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.monitoring.SourceCheck;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.TripNotificationRepository;
import com.travelrisk.platform.repository.UserProfileRepository;
import com.travelrisk.platform.service.TravelRiskService;
import com.travelrisk.platform.service.TravelRiskService.Assessment;
import com.travelrisk.platform.service.TravelRiskService.Evidence;
import com.travelrisk.platform.service.TravelRiskService.GeoPoint;
import com.travelrisk.platform.service.TravelRiskService.Signal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a believable set of employees, saved trips (Low, Medium and High), check history and one
 * unread risk-change alert, so the public demo never opens on empty tables. Demo employees also get
 * a profile (home city, frequent routes, risk tolerance) and a few older checks, so the result page's
 * "For you" memory has something to remember.
 *
 * <p>Off unless {@code DEMO_DATA_ENABLED=true} or the {@code demo} profile is active. On each start
 * it replaces only rows it created earlier (tagged by {@link #MARKER} in the stored assessment) and
 * dates trips relative to today, so they stay inside the 15-day forecast window. Rows employees
 * created themselves, demo trips they re-checked, and profiles that already exist are never touched.
 */
@Component
@ConditionalOnProperty(name = "travel-risk.demo-data.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {
  static final String MARKER = "demo seed data";
  private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final SavedTripRepository tripRepository;
  private final AssessmentHistoryRepository historyRepository;
  private final TripNotificationRepository notificationRepository;
  private final UserProfileRepository profileRepository;
  private final ObjectMapper objectMapper;

  public DemoDataSeeder(
      SavedTripRepository tripRepository,
      AssessmentHistoryRepository historyRepository,
      TripNotificationRepository notificationRepository,
      UserProfileRepository profileRepository,
      ObjectMapper objectMapper) {
    this.tripRepository = tripRepository;
    this.historyRepository = historyRepository;
    this.notificationRepository = notificationRepository;
    this.profileRepository = profileRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    removePreviousSeed();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Instant now = Instant.now();
    int trips = 0;

    for (DemoTrip trip : demoTrips()) {
      Assessment assessment = trip.assessment(today);
      if (alreadySaved(trip.username(), assessment)) continue;
      SavedTrip saved;
      if (trip.previous() != null) {
        // Seed the earlier, lower score first so the risk-change alert reads like a real re-check.
        Assessment earlier = trip.previous().assessment(today);
        saved = tripRepository.save(new SavedTrip(trip.username(), earlier, json(earlier)));
        historyRepository.save(new AssessmentHistory(trip.username(), earlier, json(earlier), now.minus(Duration.ofDays(2))));
        notificationRepository.save(new TripNotification(saved, assessment));
        saved.updateFrom(assessment, json(assessment));
      } else {
        saved = new SavedTrip(trip.username(), assessment, json(assessment));
      }
      tripRepository.save(saved);
      historyRepository.save(new AssessmentHistory(trip.username(), assessment, json(assessment),
          now.minus(Duration.ofMinutes(37L * (trips + 1)))));
      trips++;
    }

    Assessment adHoc = new DemoTrip("employee5@email.com", "Chicago, IL", "Boston, MA", 1, "flight", "KORD", "KBOS",
        List.of(), null).assessment(today);
    historyRepository.save(new AssessmentHistory("employee5@email.com", adHoc, json(adHoc), now.minus(Duration.ofHours(6))));

    for (PastCheck check : pastChecks()) {
      Assessment past = check.trip().assessment(today.minusDays(check.daysAgo()));
      historyRepository.save(new AssessmentHistory(check.trip().username(), past, json(past),
          now.minus(Duration.ofDays(check.daysAgo()))));
    }
    int profiles = 0;
    for (DemoProfile demo : demoProfiles()) {
      if (profileRepository.existsById(demo.username())) continue;
      UserProfile profile = new UserProfile(demo.username());
      profile.updateSettings(demo.homeCity(), demo.preferredMode(), demo.riskTolerance(), demo.frequentRoutes(),
          true, true, demo.alertMinLevel());
      profileRepository.save(profile);
      profiles++;
    }
    log.info("demo_data_seeded savedTrips={} profiles={}", trips, profiles);
  }

  /** Older checks that give demo employees a track record on their usual routes. */
  static List<PastCheck> pastChecks() {
    DemoSignal stormAtMco = new DemoSignal("official-alert", "high", "Destination NWS alert",
        "Destination NWS has active NWS alert: Severe Thunderstorm Warning",
        "Severe Thunderstorm Warning for Orange County, FL");
    DemoSignal ifrAtMco = new DemoSignal("aviation-weather", "medium", "Destination airport weather",
        "Destination airport weather near KMCO: MVFR conditions.", "KMCO 2053Z 22015G25KT 4SM TSRA BKN015");
    return List.of(
        new PastCheck(new DemoTrip("employee1@email.com", "Dallas, TX", "Orlando, FL", 0, "flight", "KDFW", "KMCO",
            List.of(stormAtMco, ifrAtMco), null), 19),
        new PastCheck(new DemoTrip("employee1@email.com", "Orlando, FL", "Dallas, TX", 0, "flight", "KMCO", "KDFW",
            List.of(ifrAtMco), null), 16),
        new PastCheck(new DemoTrip("employee1@email.com", "New York, NY", "Chicago, IL", 0, "flight", "KJFK", "KORD",
            List.of(), null), 24),
        new PastCheck(new DemoTrip("employee1@email.com", "New York, NY", "Chicago, IL", 0, "flight", "KJFK", "KORD",
            List.of(), null), 9),
        new PastCheck(new DemoTrip("employee2@email.com", "Boston, MA", "Washington, DC", 0, "flight", "KBOS", "KDCA",
            List.of(), null), 14));
  }

  static List<DemoProfile> demoProfiles() {
    return List.of(
        new DemoProfile("employee1@email.com", "Dallas, TX", "flight", "cautious", "any", List.of(
            new FrequentRoute("Dallas, TX", "Orlando, FL", "flight"),
            new FrequentRoute("New York, NY", "Chicago, IL", "flight"))),
        new DemoProfile("employee2@email.com", "Boston, MA", "flight", "balanced", "any", List.of(
            new FrequentRoute("Boston, MA", "Washington, DC", "flight"))),
        new DemoProfile("employee3@email.com", "Miami, FL", "flight", "flexible", "high", List.of(
            new FrequentRoute("Miami, FL", "Atlanta, GA", "flight"))));
  }

  record PastCheck(DemoTrip trip, int daysAgo) {}

  record DemoProfile(String username, String homeCity, String preferredMode, String riskTolerance,
      String alertMinLevel, List<FrequentRoute> frequentRoutes) {}

  private void removePreviousSeed() {
    for (SavedTrip trip : tripRepository.findByAssessmentJsonContaining(MARKER)) {
      notificationRepository.deleteByUsernameAndSavedTripId(trip.getUsername(), trip.getId());
      tripRepository.delete(trip);
    }
    historyRepository.deleteAll(historyRepository.findByAssessmentJsonContaining(MARKER));
    tripRepository.flush();
  }

  private boolean alreadySaved(String username, Assessment assessment) {
    TravelRiskService.Input input = assessment.input();
    return tripRepository.findByUsernameAndOriginAndDestinationAndTravelDateAndModeAndOriginAirportAndDestinationAirport(
        username, input.origin(), input.destination(), LocalDate.parse(input.date()), input.mode(),
        input.originAirport(), input.destinationAirport()).isPresent();
  }

  private String json(Assessment assessment) {
    try {
      return objectMapper.writeValueAsString(assessment);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Could not write demo assessment", error);
    }
  }

  static List<DemoTrip> demoTrips() {
    return List.of(
        new DemoTrip("employee1@email.com", "New York, NY", "Chicago, IL", 3, "flight", "KJFK", "KORD", List.of(), null),
        new DemoTrip("employee1@email.com", "Dallas, TX", "Orlando, FL", 5, "flight", "KDFW", "KMCO", List.of(
            new DemoSignal("faa-airport-status", "high", "Destination FAA airport status",
                "Destination FAA airport status has FAA NAS ground stop at MCO.",
                "Ground stop due to thunderstorms, expected until 2300 UTC"),
            new DemoSignal("aviation-weather", "high", "Destination airport weather",
                "Destination airport weather near KMCO: IFR conditions.",
                "KMCO 1853Z 24018G32KT 2SM +TSRA BR BKN008 OVC020CB")),
            new DemoTrip("employee1@email.com", "Dallas, TX", "Orlando, FL", 5, "flight", "KDFW", "KMCO", List.of(
                new DemoSignal("aviation-weather", "medium", "Destination airport weather",
                    "Destination airport weather near KMCO: MVFR conditions.",
                    "KMCO 1453Z 20012KT 5SM -RA BKN025"),
                new DemoSignal("weather", "medium", "Destination forecast",
                    "Destination forecast has elevated precipitation risk.", "Open-Meteo daily forecast")), null)),
        new DemoTrip("employee1@email.com", "Seattle, WA", "Portland, OR", 2, "driving", "", "", List.of(
            new DemoSignal("weather", "medium", "Origin forecast",
                "Origin forecast has elevated precipitation risk.", "Open-Meteo daily forecast"),
            new DemoSignal("road-closure", "medium", "Road closures",
                "2 road closures or incidents reported in WA right now.",
                "Lane closure - I-5 near Tacoma; Incident - SR-14 at Vancouver")), null),
        new DemoTrip("employee2@email.com", "Boston, MA", "Washington, DC", 4, "flight", "KBOS", "KDCA", List.of(
            new DemoSignal("wind", "medium", "Origin forecast",
                "Origin forecast has potentially disruptive wind.", "Open-Meteo wind forecast"),
            new DemoSignal("aviation-weather", "medium", "Origin airport weather",
                "Origin airport weather near KBOS: MVFR conditions.",
                "KBOS 1554Z 03022G31KT 4SM -RA BKN018")), null),
        new DemoTrip("employee2@email.com", "Denver, CO", "Phoenix, AZ", 8, "flight", "KDEN", "KPHX", List.of(), null),
        new DemoTrip("employee3@email.com", "Miami, FL", "Atlanta, GA", 6, "flight", "KMIA", "KATL", List.of(
            new DemoSignal("official-alert", "high", "Origin NWS alert",
                "Origin NWS has active NWS alert: Tropical Storm Warning",
                "Tropical Storm Warning issued for Miami-Dade County"),
            new DemoSignal("weather", "high", "Origin forecast",
                "Origin forecast has elevated precipitation risk.", "Open-Meteo daily forecast"),
            new DemoSignal("wind", "medium", "Origin forecast",
                "Origin forecast has potentially disruptive wind.", "Open-Meteo wind forecast")), null),
        new DemoTrip("employee3@email.com", "Los Angeles, CA", "Las Vegas, NV", 10, "driving", "", "", List.of(), null),
        new DemoTrip("employee4@email.com", "San Francisco, CA", "Seattle, WA", 7, "flight", "KSFO", "KSEA", List.of(
            new DemoSignal("aviation-weather", "medium", "Destination airport weather",
                "Destination airport weather near KSEA: MVFR conditions.",
                "KSEA 1653Z 19014KT 4SM -RA OVC015"),
            new DemoSignal("official-alert", "medium", "Destination NWS alert",
                "Destination NWS has active NWS alert: Wind Advisory", "Wind Advisory for Seattle metro")), null));
  }

  record DemoSignal(String type, String severity, String label, String message, String evidence) {}

  record DemoTrip(String username, String origin, String destination, int daysAhead, String mode,
      String originAirport, String destinationAirport, List<DemoSignal> signals, DemoTrip previous) {

    Assessment assessment(LocalDate today) {
      String date = today.plusDays(daysAhead).toString();
      GeoPoint from = point(origin);
      GeoPoint to = point(destination);
      GeoPoint midpoint = new GeoPoint("Route midpoint", (from.lat() + to.lat()) / 2, (from.lon() + to.lon()) / 2,
          "Calculated route midpoint");

      List<Signal> scored = new ArrayList<>();
      List<Evidence> evidence = new ArrayList<>(baselineEvidence(from, to, date));
      int points = 0;
      for (DemoSignal demo : signals) {
        Signal signal = new Signal(demo.type(), demo.severity(), demo.message(), demo.evidence());
        int base = "high".equals(demo.severity()) ? 6 : "medium".equals(demo.severity()) ? 3 : 1;
        signal.points(base + ("flight".equals(mode) && "aviation-weather".equals(demo.type()) ? 1 : 0));
        points += signal.points();
        scored.add(signal);
        evidence.add(new Evidence(sourceFor(demo.type()), demo.label(), demo.severity(), demo.evidence(),
            evidenceDetails(demo), ""));
      }
      String level = points >= 10 ? "High" : points >= 5 ? "Medium" : "Low";
      String confidence = scored.size() >= 4 ? "medium-high" : scored.size() >= 2 ? "medium" : "low-medium";

      return new Assessment(
          new TravelRiskService.Input(origin, destination, date, mode, originAirport, destinationAirport),
          new TravelRiskService.Route(from, to, midpoint),
          new TravelRiskService.Score(points, level, confidence),
          summary(level),
          recommendation(level),
          "Booked itinerary status, airline operations, and company policy are not checked.",
          Map.of("used", false, "provider", MARKER),
          scored,
          evidence,
          TravelRiskService.SOURCES.stream().skip(1)
              .map(source -> new SourceCheck(source.name(),
                  source.name().startsWith("Road511") && "flight".equals(mode) ? SourceCheck.NOT_CONFIGURED : SourceCheck.OK,
                  180 + source.name().length() * 9L))
              .toList(),
          TravelRiskService.SOURCES);
    }

    private String summary(String level) {
      if (signals.isEmpty()) return level + " disruption risk. No major risk signals were found in the current data sources.";
      List<String> drivers = signals.stream().map(signal -> switch (signal.type()) {
        case "weather" -> "heavy precipitation in the forecast";
        case "wind" -> "potentially disruptive wind";
        case "aviation-weather" -> "airport weather conditions";
        case "faa-airport-status" -> "live FAA airport delay or closure status";
        case "road-closure" -> "live road closure or traffic incident data";
        default -> "an active official weather alert";
      }).distinct().toList();
      String joined = drivers.size() == 1 ? drivers.getFirst()
          : String.join(", ", drivers.subList(0, drivers.size() - 1)) + " and " + drivers.getLast();
      return level + " disruption risk, driven by " + joined + ".";
    }

    private String recommendation(String level) {
      return switch (level) {
        case "High" -> "Consider alternate timing or routing, and confirm flight and airport status before departure.";
        case "Medium" -> "Proceed with caution, build in extra time, and recheck the latest forecast closer to departure.";
        default -> "Trip risk appears manageable based on currently available evidence; still recheck conditions before leaving.";
      };
    }

    private List<Evidence> baselineEvidence(GeoPoint from, GeoPoint to, String date) {
      List<Evidence> evidence = new ArrayList<>();
      for (GeoPoint point : List.of(from, to)) {
        String label = point == from ? "Origin forecast" : "Destination forecast";
        evidence.add(new Evidence("Open-Meteo Forecast API", label, "low",
            label + ": 14-22 C, 20% precipitation risk, 0.4 mm precipitation, max wind 18 km/h",
            details("location", point.label(), "date", date, "precipitationProbabilityPercent", 20, "maxWindKmh", 18),
            "https://open-meteo.com/"));
      }
      if ("flight".equals(mode)) {
        evidence.add(new Evidence("FAA NAS Status API", "Origin FAA airport status", "low",
            originAirport.substring(1) + ": no active FAA NAS delay, ground stop, or closure event.",
            details("airport", originAirport.substring(1)), "https://nasstatus.faa.gov/api/airport-status-information"));
      }
      return evidence;
    }
  }

  private static String sourceFor(String signalType) {
    return switch (signalType) {
      case "weather", "wind" -> "Open-Meteo Forecast API";
      case "official-alert" -> "National Weather Service API";
      case "aviation-weather" -> "Aviation Weather Center API";
      case "faa-airport-status" -> "FAA NAS Status API";
      default -> "Road511 Traffic Data API";
    };
  }

  // Same detail keys the live clients write, so the result page summarizes seeded evidence correctly.
  private static Map<String, Object> evidenceDetails(DemoSignal signal) {
    String[] words = signal.message().replace(".", "").split(" ");
    return switch (signal.type()) {
      case "aviation-weather" -> details("station", signal.evidence().split(" ")[0],
          "flightCategory", words[words.length - 2]);
      case "faa-airport-status" -> details("airport", words[words.length - 1], "eventType", "Ground stop",
          "details", signal.evidence());
      case "road-closure" -> details("jurisdiction", "WA", "description", signal.evidence());
      default -> details("status", "Active");
    };
  }

  private static Map<String, Object> details(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length - 1; index += 2) {
      map.put(String.valueOf(pairs[index]), pairs[index + 1]);
    }
    return map;
  }

  private static GeoPoint point(String city) {
    double[] coordinates = switch (city) {
      case "New York, NY" -> new double[] {40.7128, -74.0060};
      case "Chicago, IL" -> new double[] {41.8781, -87.6298};
      case "Dallas, TX" -> new double[] {32.7767, -96.7970};
      case "Orlando, FL" -> new double[] {28.5383, -81.3792};
      case "Seattle, WA" -> new double[] {47.6062, -122.3321};
      case "Portland, OR" -> new double[] {45.5152, -122.6784};
      case "Boston, MA" -> new double[] {42.3601, -71.0589};
      case "Washington, DC" -> new double[] {38.9072, -77.0369};
      case "Denver, CO" -> new double[] {39.7392, -104.9903};
      case "Phoenix, AZ" -> new double[] {33.4484, -112.0740};
      case "Miami, FL" -> new double[] {25.7617, -80.1918};
      case "Atlanta, GA" -> new double[] {33.7490, -84.3880};
      case "Los Angeles, CA" -> new double[] {34.0522, -118.2437};
      case "Las Vegas, NV" -> new double[] {36.1699, -115.1398};
      case "San Francisco, CA" -> new double[] {37.7749, -122.4194};
      default -> throw new IllegalArgumentException("No demo coordinates for " + city);
    };
    return new GeoPoint(city + ", United States", coordinates[0], coordinates[1], "Built-in city coordinates");
  }
}
