package com.travelrisk.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class TravelRiskServiceScoringTest {
  private TravelRiskService service() {
    return new TravelRiskService(RestClient.builder(), new ObjectMapper(), "test-agent", "", "test-model", "");
  }

  @Test
  void summaryNamesEachDriverOnce() {
    List<TravelRiskService.Signal> signals = List.of(
        new TravelRiskService.Signal("road-closure", "high", "Road511 reports A.", "A"),
        new TravelRiskService.Signal("road-closure", "high", "Road511 reports B.", "B"),
        new TravelRiskService.Signal("road-closure", "high", "Road511 reports C.", "C"));

    String text = ReflectionTestUtils.invokeMethod(service(), "readableDriverText", signals);

    assertThat(text).isEqualTo("live road closure or traffic incident data");
  }

  @Test
  void roadEventsBecomeOneSignal() {
    TravelRiskService.Signal signal = TravelRiskService.roadClosureSignal("CA", "high",
        List.of("I-80 closed", "US-101 lane closure", "I-80 closed", "SR-1 incident"));

    assertThat(signal.type()).isEqualTo("road-closure");
    assertThat(signal.severity()).isEqualTo("high");
    assertThat(signal.message()).isEqualTo("3 road closures or incidents reported in CA right now.");
    assertThat(signal.evidence()).isEqualTo("I-80 closed; US-101 lane closure; SR-1 incident");
  }

  @Test
  void roadSeverityComesFromClosureType() {
    assertThat(TravelRiskService.roadSeverity("closure", "", "Full — US-101")).isEqualTo("high");
    assertThat(TravelRiskService.roadSeverity("closure", "", "Lane closure — I-80")).isEqualTo("medium");
    assertThat(TravelRiskService.roadSeverity("construction", "", "Work Zone - SR-61")).isEqualTo("low");
  }

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
