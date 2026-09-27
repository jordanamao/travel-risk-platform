package com.travelrisk.platform.controllers;

import com.travelrisk.platform.service.AssessmentHistoryService;
import com.travelrisk.platform.service.TravelRiskService;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyzeController {
  private final TravelRiskService service;
  private final AssessmentHistoryService historyService;

  public AnalyzeController(TravelRiskService service, AssessmentHistoryService historyService) {
    this.service = service;
    this.historyService = historyService;
  }

  @GetMapping("/api/analyze")
  public TravelRiskService.Assessment analyze(
      @RequestParam String origin,
      @RequestParam String destination,
      @RequestParam String date,
      @RequestParam(defaultValue = "flight") String mode,
      @RequestParam(defaultValue = "") String originAirport,
      @RequestParam(defaultValue = "") String destinationAirport,
      @RequestParam(defaultValue = "true") boolean recordHistory,
      Principal principal) {
    TravelRiskService.Assessment assessment =
        service.analyze(origin, destination, date, mode, originAirport, destinationAirport);
    if (recordHistory) {
      historyService.record(username(principal), assessment);
    }
    return assessment;
  }

  @PostMapping("/api/analyze/cache/refresh")
  public TravelRiskService.Assessment refreshAssessment(
      @RequestParam String origin,
      @RequestParam String destination,
      @RequestParam String date,
      @RequestParam(defaultValue = "flight") String mode,
      @RequestParam(defaultValue = "") String originAirport,
      @RequestParam(defaultValue = "") String destinationAirport,
      @RequestParam(defaultValue = "true") boolean recordHistory,
      Principal principal) {
    TravelRiskService.Assessment assessment =
        service.refreshAssessment(origin, destination, date, mode, originAirport, destinationAirport);
    if (recordHistory) {
      historyService.record(username(principal), assessment);
    }
    return assessment;
  }

  @DeleteMapping("/api/analyze/cache")
  public Map<String, String> evictAssessment(
      @RequestParam String origin,
      @RequestParam String destination,
      @RequestParam String date,
      @RequestParam(defaultValue = "flight") String mode,
      @RequestParam(defaultValue = "") String originAirport,
      @RequestParam(defaultValue = "") String destinationAirport) {
    service.evictAssessment(origin, destination, date, mode, originAirport, destinationAirport);
    return Map.of("status", "evicted");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> serverError(Exception error) {
    String message = error.getMessage() != null && error.getMessage().contains("429")
        ? "External geocoding service is rate-limiting requests. Try a listed city or wait a moment before retrying."
        : "Unexpected server error";
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", message, "details", error.getMessage() == null ? "" : error.getMessage()));
  }

  private String username(Principal principal) {
    return principal == null ? "anonymous" : principal.getName();
  }
}
