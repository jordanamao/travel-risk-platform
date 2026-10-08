package com.travelrisk.platform.controllers;

import com.travelrisk.platform.brief.TripBrief;
import com.travelrisk.platform.brief.TripBriefService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TripBriefController {
  private final TripBriefService service;

  public TripBriefController(TripBriefService service) {
    this.service = service;
  }

  /** A plain-language brief of the trip's check, written by Claude when it is set up. */
  @GetMapping("/api/analyze/brief")
  public TripBrief brief(@Valid TripQuery query) {
    return service.brief(
        query.origin(), query.destination(), query.date(), query.mode(), query.originAirport(), query.destinationAirport());
  }
}
