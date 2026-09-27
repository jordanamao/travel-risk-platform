package com.travelrisk.platform;

import com.travelrisk.platform.service.TravelRiskService;
import java.util.List;
import java.util.Map;

public final class TestAssessments {
  private TestAssessments() {}

  public static TravelRiskService.Assessment assessment(String origin, String destination, String date,
      String mode, String riskLevel, int riskPoints) {
    return new TravelRiskService.Assessment(
        new TravelRiskService.Input(origin, destination, date, mode, "", ""),
        new TravelRiskService.Route(
            new TravelRiskService.GeoPoint(origin, 40.7128, -74.0060, "test"),
            new TravelRiskService.GeoPoint(destination, 37.7749, -122.4194, "test"),
            new TravelRiskService.GeoPoint("midpoint", 39.0, -120.0, "test")),
        new TravelRiskService.Score(riskPoints, riskLevel, "test"),
        riskLevel + " disruption risk",
        "Monitor conditions before departure.",
        "Test assessment only.",
        Map.of("used", false),
        List.of(new TravelRiskService.Signal("aviation-weather", riskLevel.toLowerCase(), "Airport weather", "METAR")),
        List.of(),
        List.of());
  }
}
