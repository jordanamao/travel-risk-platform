package com.travelrisk.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class TravelRiskServiceScoringTest {
  @Test
  void scoresSignalsWithFlightAirportBonus() {
    TravelRiskService service = new TravelRiskService(
        RestClient.builder(),
        new ObjectMapper(),
        "test-agent",
        "",
        "test-model",
        "");
    List<TravelRiskService.Signal> signals = new ArrayList<>(List.of(
        new TravelRiskService.Signal("aviation-weather", "high", "Airport weather", "METAR"),
        new TravelRiskService.Signal("official-alert", "medium", "Weather alert", "NWS"),
        new TravelRiskService.Signal("weather", "low", "Rain forecast", "Forecast")));

    TravelRiskService.Score score = ReflectionTestUtils.invokeMethod(service, "scoreSignals", signals, "flight");

    assertThat(score).isNotNull();
    assertThat(score.points()).isEqualTo(11);
    assertThat(score.level()).isEqualTo("High");
    assertThat(signals).extracting(TravelRiskService.Signal::points).containsExactly(7, 3, 1);
  }
}
