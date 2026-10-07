package com.travelrisk.platform.controllers;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.travelrisk.platform.service.AssessmentHistoryService;
import com.travelrisk.platform.service.RiskMemoryService;
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
  private final RiskMemoryService riskMemory;

  public AnalyzeController(
      TravelRiskService service, AssessmentHistoryService historyService, RiskMemoryService riskMemory) {
    this.service = service;
    this.historyService = historyService;
    this.riskMemory = riskMemory;
  }

  /** The company assessment plus a "personal" block from the employee's own risk memory. */
  @GetMapping("/api/analyze")
  public PersonalizedAssessment analyze(
      @Valid TripQuery query,
      @RequestParam(defaultValue = "true") boolean recordHistory,
      Principal principal) {
    TravelRiskService.Assessment assessment =
        service.analyze(
            query.origin(), query.destination(), query.date(), query.mode(), query.originAirport(), query.destinationAirport());
    // Read the memory before recording this check, so it only reflects earlier ones.
    RiskMemoryService.Personalization personal = riskMemory.personalize(username(principal), assessment);
    if (recordHistory) {
      historyService.record(username(principal), assessment);
    }
    return new PersonalizedAssessment(assessment, personal);
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

  /** "What should I do instead?": re-scored options for the same trip, reusing the cached check. */
  @GetMapping("/api/analyze/alternatives")
  public TravelRiskService.Alternatives alternatives(@Valid TripQuery query) {
    TravelRiskService.Assessment assessment =
        service.analyze(
            query.origin(), query.destination(), query.date(), query.mode(), query.originAirport(), query.destinationAirport());
    return service.alternatives(assessment);
  }

  public record PersonalizedAssessment(
      @JsonUnwrapped TravelRiskService.Assessment assessment, RiskMemoryService.Personalization personal) {}

  private String username(Principal principal) {
    return principal == null ? "anonymous" : principal.getName();
  }
}
