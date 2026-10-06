package com.travelrisk.platform.controllers;

import com.travelrisk.platform.service.TripNotificationService;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TripNotificationController {
  private final TripNotificationService service;

  public TripNotificationController(TripNotificationService service) {
    this.service = service;
  }

  @GetMapping("/api/notifications")
  public List<TripNotificationService.TripNotificationResponse> list(Principal principal) {
    return service.list(principal.getName());
  }

  @GetMapping("/api/notifications/channels")
  public TripNotificationService.AlertChannelsResponse channels() {
    return service.channels();
  }

  // The demo admin is read-only, so it never sends mail.
  @PreAuthorize("!hasRole('DEMO_ADMIN')")
  @PostMapping("/api/notifications/{id}/send")
  public TripNotificationService.AlertChannelsResponse send(Principal principal, @PathVariable Long id) {
    return service.send(principal.getName(), id);
  }

  @PostMapping("/api/notifications/{id}/read")
  public TripNotificationService.TripNotificationResponse markRead(Principal principal, @PathVariable Long id) {
    return service.markRead(principal.getName(), id);
  }
}
