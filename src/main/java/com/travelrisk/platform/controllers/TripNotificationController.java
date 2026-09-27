package com.travelrisk.platform.controllers;

import com.travelrisk.platform.service.TripNotificationService;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

  @PostMapping("/api/notifications/{id}/read")
  public TripNotificationService.TripNotificationResponse markRead(Principal principal, @PathVariable Long id) {
    return service.markRead(principal.getName(), id);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public org.springframework.http.ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
    return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
  }
}
