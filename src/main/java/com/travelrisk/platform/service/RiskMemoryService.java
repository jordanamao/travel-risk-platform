package com.travelrisk.platform.service;

import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.FrequentRoute;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import com.travelrisk.platform.repository.UserProfileRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal risk memory: what an employee's own past checks, saved trips and risk tolerance say
 * about the trip in front of them. The company score is never changed; this adds a "For you" layer
 * on top of it.
 *
 * <p>A route matches in either direction (Dallas to Orlando and Orlando to Dallas share airports
 * and weather), and only the last {@link #WINDOW} of checks count, so a bad week long ago fades out.
 */
@Service
public class RiskMemoryService {
  static final Duration WINDOW = Duration.ofDays(90);
  private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US);

  private final AssessmentHistoryRepository history;
  private final SavedTripRepository trips;
  private final UserProfileRepository profiles;
  private final Clock clock;

  @Autowired
  public RiskMemoryService(AssessmentHistoryRepository history, SavedTripRepository trips, UserProfileRepository profiles) {
    this(history, trips, profiles, Clock.systemUTC());
  }

  RiskMemoryService(AssessmentHistoryRepository history, SavedTripRepository trips, UserProfileRepository profiles,
      Clock clock) {
    this.history = history;
    this.trips = trips;
    this.profiles = profiles;
    this.clock = clock;
  }

  /**
   * Reads the employee's memory for this trip's route. Call it before the current check is
   * recorded, so "past checks" means checks before this one.
   */
  @Transactional(readOnly = true)
  public Personalization personalize(String username, TravelRiskService.Assessment assessment) {
    if (username == null || username.isBlank() || "anonymous".equals(username)) {
      return null;
    }
    TravelRiskService.Input input = assessment.input();
    String key = routeKey(input.origin(), input.destination());
    UserProfile profile = profiles.findById(username).orElse(null);
    RiskTolerance tolerance = RiskTolerance.from(profile == null ? null : profile.getRiskTolerance());

    List<AssessmentHistory> pastChecks = recentChecks(username).stream()
        .filter(check -> key.equals(routeKey(check.getOrigin(), check.getDestination())))
        .toList();
    List<AssessmentHistory> riskyChecks = pastChecks.stream().filter(check -> risky(check.getRiskLevel())).toList();
    List<SavedTrip> otherTrips = trips.findByUsernameOrderByTravelDateAscUpdatedAtDesc(username).stream()
        .filter(trip -> key.equals(routeKey(trip.getOrigin(), trip.getDestination())))
        .filter(trip -> !(trip.getTravelDate().toString().equals(input.date()) && trip.getMode().equals(input.mode())))
        .toList();
    int riskyTrips = (int) otherTrips.stream().filter(trip -> risky(trip.getRiskLevel())).count();
    boolean frequent = profile != null && profile.getFrequentRoutes().stream()
        .anyMatch(route -> key.equals(routeKey(route.origin(), route.destination())));

    AssessmentHistory lastRisky = riskyChecks.isEmpty() ? null : riskyChecks.getFirst();
    RouteMemory memory = new RouteMemory(
        pastChecks.size(),
        riskyChecks.size(),
        lastRisky == null ? null : lastRisky.getRiskLevel(),
        lastRisky == null ? null : shortDate(lastRisky.getCreatedAt()),
        otherTrips.size(),
        riskyTrips);

    int points = assessment.score().points();
    String status;
    String headline;
    String detail;
    if (points >= tolerance.flagAt()) {
      status = "flagged";
      headline = "Above your comfort line";
      detail = "This trip scores %d %s. Your %s setting flags trips from %d points%s."
          .formatted(points, plural(points, "point"), tolerance.label(), tolerance.flagAt(),
              "High".equals(assessment.score().level()) ? "" : ", even though the company rates it " + assessment.score().level());
    } else if (memory.riskyChecks() > 0 || riskyTrips > 0) {
      status = "watch";
      headline = "Risky for you before";
      detail = "It scores %d %s now, below your %s flag line (%d points), but this route has been Medium or High for you before. Recheck closer to departure."
          .formatted(points, plural(points, "point"), tolerance.label(), tolerance.flagAt());
    } else {
      status = "clear";
      headline = "Within your comfort line";
      detail = "%d %s is below your %s flag line (%d points)."
          .formatted(points, plural(points, "point"), tolerance.label(), tolerance.flagAt());
    }

    return new Personalization(tolerance.id(), tolerance.label(), tolerance.flagAt(), points, status, headline, detail,
        notes(memory, frequent), memory, frequent);
  }

  /** The profile page's view of the memory: recent activity, routes that stand out, and routes worth pinning. */
  @Transactional(readOnly = true)
  public MemorySummary summary(String username, List<FrequentRoute> pinned) {
    List<AssessmentHistory> checks = recentChecks(username);
    Map<String, List<AssessmentHistory>> byRoute = new LinkedHashMap<>();
    for (AssessmentHistory check : checks) {
      byRoute.computeIfAbsent(routeKey(check.getOrigin(), check.getDestination()), ignored -> new ArrayList<>()).add(check);
    }
    List<RouteStat> routes = byRoute.values().stream()
        .map(RiskMemoryService::routeStat)
        .sorted(Comparator.comparingInt(RouteStat::riskyChecks).reversed()
            .thenComparing(Comparator.comparingInt(RouteStat::checks).reversed()))
        .limit(5)
        .toList();
    List<String> pinnedKeys = pinned.stream().map(route -> routeKey(route.origin(), route.destination())).toList();
    List<FrequentRoute> suggestions = byRoute.entrySet().stream()
        .filter(entry -> entry.getValue().size() >= 2 && !pinnedKeys.contains(entry.getKey()))
        .sorted(Comparator.comparingInt((Map.Entry<String, List<AssessmentHistory>> entry) -> entry.getValue().size()).reversed())
        .limit(3)
        .map(entry -> {
          AssessmentHistory latest = entry.getValue().getFirst();
          return new FrequentRoute(latest.getOrigin(), latest.getDestination(), latest.getMode());
        })
        .toList();
    int savedTrips = trips.findByUsernameOrderByTravelDateAscUpdatedAtDesc(username).size();
    return new MemorySummary(checks.size(), savedTrips, (int) WINDOW.toDays(), routes, suggestions);
  }

  private List<AssessmentHistory> recentChecks(String username) {
    return history.findTop200ByUsernameAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        username, clock.instant().minus(WINDOW));
  }

  private static RouteStat routeStat(List<AssessmentHistory> checks) {
    AssessmentHistory latest = checks.getFirst();
    List<AssessmentHistory> risky = checks.stream().filter(check -> risky(check.getRiskLevel())).toList();
    return new RouteStat(latest.getOrigin(), latest.getDestination(), latest.getMode(), checks.size(), risky.size(),
        latest.getRiskLevel(), shortDate(latest.getCreatedAt()),
        risky.isEmpty() ? null : risky.getFirst().getRiskLevel(),
        risky.isEmpty() ? null : shortDate(risky.getFirst().getCreatedAt()));
  }

  private static List<String> notes(RouteMemory memory, boolean frequent) {
    List<String> notes = new ArrayList<>();
    if (memory.checks() == 0) {
      notes.add("First time you've checked this route in the last %d days.".formatted(WINDOW.toDays()));
    } else if (memory.riskyChecks() == 0) {
      notes.add("Your %d past %s on this route %s Low."
          .formatted(memory.checks(), plural(memory.checks(), "check"), memory.checks() == 1 ? "was" : "were all"));
    } else {
      notes.add("%d of your %d past %s on this route came back Medium or High (last %s on %s)."
          .formatted(memory.riskyChecks(), memory.checks(), plural(memory.checks(), "check"),
              memory.lastRiskyLevel(), memory.lastRiskyDate()));
    }
    if (memory.savedTrips() > 0) {
      String risky = memory.riskySavedTrips() == 0 ? "" : memory.riskySavedTrips() == memory.savedTrips()
          ? (memory.savedTrips() == 1 ? ", currently Medium or High" : ", all currently Medium or High")
          : ", %d currently Medium or High".formatted(memory.riskySavedTrips());
      notes.add("You have %d other saved %s on this route%s."
          .formatted(memory.savedTrips(), plural(memory.savedTrips(), "trip"), risky));
    }
    if (frequent) {
      notes.add("One of your frequent routes.");
    }
    return notes;
  }

  static String routeKey(String origin, String destination) {
    String a = normalize(origin);
    String b = normalize(destination);
    return Stream.of(a, b).sorted().reduce((first, second) -> first + "|" + second).orElse("");
  }

  private static String normalize(String value) {
    return Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
  }

  private static boolean risky(String level) {
    return "High".equalsIgnoreCase(level) || "Medium".equalsIgnoreCase(level);
  }

  private static String shortDate(Instant instant) {
    return LocalDate.ofInstant(instant, ZoneOffset.UTC).format(SHORT_DATE);
  }

  private static String plural(int count, String word) {
    return count == 1 ? word : word + "s";
  }

  /** What the result page shows under "For you". {@code status} is flagged, watch or clear. */
  public record Personalization(
      String tolerance,
      String toleranceLabel,
      int flagAt,
      int points,
      String status,
      String headline,
      String detail,
      List<String> notes,
      RouteMemory route,
      boolean frequentRoute) {}

  /** This employee's past checks and other saved trips on the same route, either direction. */
  public record RouteMemory(
      int checks,
      int riskyChecks,
      String lastRiskyLevel,
      String lastRiskyDate,
      int savedTrips,
      int riskySavedTrips) {}

  public record MemorySummary(int checks, int savedTrips, int windowDays, List<RouteStat> routes,
      List<FrequentRoute> suggestedRoutes) {}

  public record RouteStat(String origin, String destination, String mode, int checks, int riskyChecks,
      String lastLevel, String lastDate, String lastRiskyLevel, String lastRiskyDate) {}
}
