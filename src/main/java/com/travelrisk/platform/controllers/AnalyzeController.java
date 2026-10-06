package com.travelrisk.platform.controllers;

import com.travelrisk.platform.service.AssessmentHistoryService;
import com.travelrisk.platform.service.TravelRiskService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
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
      @Valid TripQuery query,
      @RequestParam(defaultValue = "true") boolean recordHistory,
      Principal principal) {
    TravelRiskService.Assessment assessment =
        service.analyze(
            query.origin(), query.destination(), query.date(), query.mode(), query.originAirport(), query.destinationAirport());
    if (recordHistory) {
      historyService.record(username(principal), assessment);
    }
    return assessment;
  }

  @PostMapping("/api/analyze/cache/refresh")
  public TravelRiskService.Assessment refreshAssessment(
      @Valid TripQuery query,
      @RequestParam(defaultValue = "true") boolean recordHistory,
      Principal principal) {
    TravelRiskService.Assessment assessment =
        service.refreshAssessment(
            query.origin(), query.destination(), query.date(), query.mode(), query.originAirport(), query.destinationAirport());
    if (recordHistory) {
      historyService.record(username(principal), assessment);
    }
    return assessment;
  }

  @DeleteMapping("/api/analyze/cache")
  public Map<String, String> evictAssessment(
      @Valid TripQuery query) {
    service.evictAssessment(
        query.origin(), query.destination(), query.date(), query.mode(), query.originAirport(), query.destinationAirport());
    return Map.of("status", "evicted");
  }



  private String username(Principal principal) {
    return principal == null ? "anonymous" : principal.getName();
  }
}
