package com.travelrisk.platform.controllers;

import jakarta.validation.constraints.NotBlank;

/** Query parameters shared by the analyze endpoints; origin, destination and date are required. */
public record TripQuery(
    @NotBlank(message = "is required") String origin,
    @NotBlank(message = "is required") String destination,
    @NotBlank(message = "is required") String date,
    String mode,
    String originAirport,
    String destinationAirport) {

  public TripQuery {
    mode = mode == null || mode.isBlank() ? "flight" : mode;
    originAirport = originAirport == null ? "" : originAirport;
    destinationAirport = destinationAirport == null ? "" : destinationAirport;
  }
}
