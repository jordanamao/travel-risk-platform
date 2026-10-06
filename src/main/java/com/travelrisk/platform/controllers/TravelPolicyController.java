package com.travelrisk.platform.controllers;

import com.travelrisk.platform.policy.PolicyDecision;
import com.travelrisk.platform.policy.TravelPolicy;
import com.travelrisk.platform.policy.TravelPolicyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TravelPolicyController {
  private final TravelPolicyService service;

  public TravelPolicyController(TravelPolicyService service) {
    this.service = service;
  }

  /** The active company policy, so travelers can see the rules their trips are checked against. */
  @GetMapping("/api/policy")
  public TravelPolicy policy() {
    return service.policy();
  }

  /** Checks an assessment that has not been saved yet, e.g. the one on screen. */
  @PostMapping("/api/policy/evaluate")
  public PolicyDecision evaluate(@Valid @RequestBody SavedTripController.SaveTripRequest request) {
    return service.evaluate(request.assessment());
  }
}
