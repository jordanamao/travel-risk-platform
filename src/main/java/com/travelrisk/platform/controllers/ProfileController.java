package com.travelrisk.platform.controllers;

import com.travelrisk.platform.alerts.TripAlertService;
import com.travelrisk.platform.database.entities.FrequentRoute;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.service.RiskMemoryService;
import com.travelrisk.platform.service.RiskTolerance;
import com.travelrisk.platform.service.UserProfileService;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/** The signed-in employee's own profile: preferences, frequent routes, risk tolerance and alert settings. */
@Controller
public class ProfileController {
  private final UserProfileService profiles;
  private final RiskMemoryService riskMemory;
  private final TripAlertService tripAlerts;

  public ProfileController(UserProfileService profiles, RiskMemoryService riskMemory, TripAlertService tripAlerts) {
    this.profiles = profiles;
    this.riskMemory = riskMemory;
    this.tripAlerts = tripAlerts;
  }

  @GetMapping("/profile")
  public String page() {
    return "forward:/profile.html";
  }

  @GetMapping("/api/profile")
  @ResponseBody
  public ProfileResponse get(Principal principal) {
    return response(principal.getName(), profiles.find(principal.getName()).orElse(null));
  }

  // The demo admin is read-only, so it can look but not save.
  @PreAuthorize("!hasRole('DEMO_ADMIN')")
  @PutMapping("/api/profile")
  @ResponseBody
  public ProfileResponse update(Principal principal, @RequestBody ProfileRequest request) {
    Notifications notifications = request.notifications() == null ? Notifications.DEFAULTS : request.notifications();
    UserProfile saved = profiles.updateSettings(principal.getName(), new UserProfileService.Settings(
        request.homeCity(),
        request.preferredMode(),
        request.riskTolerance(),
        request.frequentRoutes(),
        notifications.email() == null || notifications.email(),
        notifications.slack() == null || notifications.slack(),
        notifications.minLevel()));
    return response(principal.getName(), saved);
  }

  private ProfileResponse response(String username, UserProfile profile) {
    List<FrequentRoute> routes = profile == null ? List.of() : profile.getFrequentRoutes();
    return new ProfileResponse(
        username,
        profile == null ? null : profile.getDisplayName(),
        profile == null ? null : profile.getEmail(),
        profile == null ? null : profile.getHomeCity(),
        profile == null ? null : profile.getPreferredMode(),
        RiskTolerance.from(profile == null ? null : profile.getRiskTolerance()).id(),
        Arrays.stream(RiskTolerance.values())
            .map(tolerance -> new ToleranceOption(tolerance.id(), tolerance.label(), tolerance.flagAt(), tolerance.description()))
            .toList(),
        routes,
        profile == null ? Notifications.DEFAULTS
            : new Notifications(profile.isAlertEmail(), profile.isAlertSlack(), profile.getAlertMinLevel()),
        tripAlerts.configuredChannels(),
        riskMemory.summary(username, routes));
  }

  public record ProfileRequest(
      String homeCity,
      String preferredMode,
      String riskTolerance,
      List<FrequentRoute> frequentRoutes,
      Notifications notifications) {}

  /** Which outside channels may carry this employee's alerts, and whether every level change counts or only High. */
  public record Notifications(Boolean email, Boolean slack, String minLevel) {
    static final Notifications DEFAULTS = new Notifications(true, true, "any");
  }

  public record ToleranceOption(String id, String label, int flagAt, String description) {}

  public record ProfileResponse(
      String username,
      String displayName,
      String email,
      String homeCity,
      String preferredMode,
      String riskTolerance,
      List<ToleranceOption> toleranceOptions,
      List<FrequentRoute> frequentRoutes,
      Notifications notifications,
      List<String> alertChannels,
      RiskMemoryService.MemorySummary riskMemory) {}
}
