package com.travelrisk.platform.controllers;

import com.travelrisk.platform.service.SavedTripService;
import com.travelrisk.platform.service.TravelRiskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SavedTripController {
  private final SavedTripService service;

  public SavedTripController(SavedTripService service) {
    this.service = service;
  }

  @GetMapping("/api/trips")
  public List<SavedTripService.SavedTripResponse> list(Principal principal) {
    return service.list(principal.getName());
  }

  @PostMapping("/api/trips")
  public SavedTripService.SavedTripResponse save(
      Principal principal,
      @Valid @RequestBody SaveTripRequest request) {
    return service.save(principal.getName(), request.assessment());
  }

  @PostMapping("/api/trips/alerts/check")
  public SavedTripService.AlertCheckResponse checkAlerts(Principal principal) {
    return service.checkAlerts(principal.getName());
  }

  @DeleteMapping("/api/trips/{id}")
  public Map<String, String> delete(Principal principal, @PathVariable Long id) {
    service.delete(principal.getName(), id);
    return Map.of("status", "deleted");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public org.springframework.http.ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
    return org.springframework.http.ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
  }

  public record SaveTripRequest(@NotNull TravelRiskService.Assessment assessment) {}
}
