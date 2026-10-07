package com.travelrisk.platform.service;

import com.travelrisk.platform.database.entities.FrequentRoute;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.repository.UserProfileRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
  static final int MAX_FREQUENT_ROUTES = 5;
  private static final int MAX_TEXT = 120;
  private static final Set<String> MODES = Set.of("flight", "drive", "driving", "general");
  private static final Set<String> ALERT_LEVELS = Set.of("any", "high");

  private final UserProfileRepository repository;

  public UserProfileService(UserProfileRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public void remember(String username, String email, String displayName) {
    if (username == null || username.isBlank()) return;
    UserProfile profile = repository.findById(username).orElseGet(() -> new UserProfile(username));
    profile.update(blankToNull(email), blankToNull(displayName));
    repository.save(profile);
  }

  @Transactional(readOnly = true)
  public Map<String, UserProfile> findAll(Collection<String> usernames) {
    return repository.findAllById(usernames).stream()
        .collect(Collectors.toMap(UserProfile::getUsername, Function.identity()));
  }

  @Transactional(readOnly = true)
  public Optional<UserProfile> find(String username) {
    return repository.findById(username);
  }

  /** Saves what the employee chose on the Profile page. Invalid input is refused with a readable message. */
  @Transactional
  public UserProfile updateSettings(String username, Settings settings) {
    if (settings == null) {
      throw new IllegalArgumentException("Profile settings are required.");
    }
    String homeCity = text(settings.homeCity(), "Home city");
    String mode = blankToNull(settings.preferredMode());
    if (mode != null && !MODES.contains(mode)) {
      throw new IllegalArgumentException("Preferred trip type must be flight, drive or general.");
    }
    if (!RiskTolerance.isKnown(settings.riskTolerance())) {
      throw new IllegalArgumentException("Risk tolerance must be cautious, balanced or flexible.");
    }
    String alertMinLevel = settings.alertMinLevel() == null ? "any" : settings.alertMinLevel().trim();
    if (!ALERT_LEVELS.contains(alertMinLevel)) {
      throw new IllegalArgumentException("Alert level must be any or high.");
    }

    UserProfile profile = repository.findById(username).orElseGet(() -> new UserProfile(username));
    profile.updateSettings(homeCity, mode, RiskTolerance.from(settings.riskTolerance()).id(),
        routes(settings.frequentRoutes()), settings.alertEmail(), settings.alertSlack(), alertMinLevel);
    return repository.save(profile);
  }

  private static List<FrequentRoute> routes(List<FrequentRoute> requested) {
    List<FrequentRoute> routes = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (FrequentRoute route : requested == null ? List.<FrequentRoute>of() : requested) {
      if (route == null) continue;
      String origin = text(route.origin(), "Route origin");
      String destination = text(route.destination(), "Route destination");
      if (origin == null || destination == null) {
        throw new IllegalArgumentException("Each frequent route needs an origin and a destination.");
      }
      if (origin.equalsIgnoreCase(destination)) {
        throw new IllegalArgumentException("A frequent route's origin and destination must be different.");
      }
      String mode = blankToNull(route.mode()) == null ? "flight" : route.mode().trim();
      if (!MODES.contains(mode)) {
        throw new IllegalArgumentException("Route trip type must be flight, drive or general.");
      }
      if (seen.add(RiskMemoryService.routeKey(origin, destination) + "|" + mode)) {
        routes.add(new FrequentRoute(origin, destination, mode));
      }
    }
    if (routes.size() > MAX_FREQUENT_ROUTES) {
      throw new IllegalArgumentException("Pin up to " + MAX_FREQUENT_ROUTES + " frequent routes.");
    }
    return routes;
  }

  private static String text(String value, String field) {
    String clean = blankToNull(value);
    if (clean != null && clean.length() > MAX_TEXT) {
      throw new IllegalArgumentException(field + " must be " + MAX_TEXT + " characters or fewer.");
    }
    return clean;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record Settings(
      String homeCity,
      String preferredMode,
      String riskTolerance,
      List<FrequentRoute> frequentRoutes,
      boolean alertEmail,
      boolean alertSlack,
      String alertMinLevel) {}
}
