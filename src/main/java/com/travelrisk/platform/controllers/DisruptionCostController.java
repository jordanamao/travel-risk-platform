package com.travelrisk.platform.controllers;

import com.travelrisk.platform.cost.DisruptionCostEstimate;
import com.travelrisk.platform.cost.DisruptionCostService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DisruptionCostController {
  private final DisruptionCostService service;

  public DisruptionCostController(DisruptionCostService service) {
    this.service = service;
  }

  /** Estimated cost of disruption for a trip at this risk level, with the items behind it. */
  @GetMapping("/api/disruption-cost")
  public DisruptionCostEstimate estimate(
      @RequestParam String riskLevel,
      @RequestParam(defaultValue = "general") String mode) {
    DisruptionCostEstimate estimate = service.estimate(riskLevel, mode);
    if (estimate == null) {
      throw new IllegalArgumentException("riskLevel must be Low, Medium or High.");
    }
    return estimate;
  }
}
