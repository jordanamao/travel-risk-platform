package com.travelrisk.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.monitoring.SourceCheck;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

class TravelRiskServiceDegradationTest {
  private static final String OPEN_METEO_DAILY = """
      {"daily": {"temperature_2m_max": [21], "temperature_2m_min": [12], "precipitation_sum": [0],
        "precipitation_probability_max": [10], "wind_speed_10m_max": [12], "wind_gusts_10m_max": [20]}}
      """;

  private final String tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();

  @Test
  void checkCompletesAndMarksDownSourcesUnavailable() {
    List<List<SourceCheck>> reported = new ArrayList<>();
    TravelRiskService service = service(uri -> "api.open-meteo.com".equals(uri.getHost()), reported);

    TravelRiskService.Assessment assessment = service.analyze("New York, NY", "Chicago, IL", tomorrow, "flight", "", "");

    Map<String, String> statuses = statuses(assessment);
    assertThat(statuses).containsEntry("Open-Meteo Forecast API", SourceCheck.OK)
        .containsEntry("National Weather Service API", SourceCheck.UNAVAILABLE)
        .containsEntry("Aviation Weather Center API", SourceCheck.UNAVAILABLE)
        .containsEntry("FAA NAS Status API", SourceCheck.UNAVAILABLE)
        .containsEntry("Road511 Traffic Data API", SourceCheck.NOT_CONFIGURED)
        .containsEntry("OpenAI Responses API", SourceCheck.NOT_CONFIGURED);
    assertThat(assessment.score().level()).isEqualTo("Low");
    assertThat(assessment.score().confidence()).isEqualTo("low");
    assertThat(assessment.uncertainty()).contains(
        "Not reachable during this check: National Weather Service, Aviation Weather Center, FAA NAS Status API".replace(" API", ""));
    assertThat(assessment.degraded()).isTrue();
    assertThat(reported).hasSize(1);
    assertThat(reported.getFirst()).isEqualTo(assessment.dataSources());
  }

  @Test
  void checkCompletesWhenEverySourceIsDown() {
    TravelRiskService service = service(uri -> false, new ArrayList<>());

    TravelRiskService.Assessment assessment = service.analyze("Seattle, WA", "Denver, CO", tomorrow, "driving", "", "");

    assertThat(assessment.score().level()).isEqualTo("Low");
    assertThat(assessment.signals()).isEmpty();
    assertThat(statuses(assessment)).containsEntry("Open-Meteo Forecast API", SourceCheck.UNAVAILABLE);
    assertThat(assessment.evidence()).allSatisfy(item -> assertThat(item.severity()).isEqualTo("unknown"));
  }

  @Test
  void sourceIsDegradedWhenOnlySomeCallsFail() {
    List<SourceCheck> checks = TravelRiskService.summarizeSources(List.of(
        new TravelRiskService.TimedBundle("Open-Meteo Forecast API", SourceCheck.OK, 120),
        new TravelRiskService.TimedBundle("Open-Meteo Forecast API", SourceCheck.UNAVAILABLE, 900),
        new TravelRiskService.TimedBundle("FAA NAS Status API", SourceCheck.OK, 300)));

    assertThat(checks).containsExactly(
        new SourceCheck("Open-Meteo Forecast API", SourceCheck.DEGRADED, 900),
        new SourceCheck("FAA NAS Status API", SourceCheck.OK, 300));
  }

  @Test
  void healthyChecksKeepTheirUncertaintyText() {
    String text = TravelRiskService.withUnavailableNote("Company policy is not checked.",
        List.of(new SourceCheck("FAA NAS Status API", SourceCheck.OK, 10)));

    assertThat(text).isEqualTo("Company policy is not checked.");
  }

  private TravelRiskService service(Predicate<URI> reachable, List<List<SourceCheck>> reported) {
    ClientHttpRequestFactory requestFactory = (uri, method) -> {
      if (!reachable.test(uri)) {
        throw new IOException("Connection refused: " + uri.getHost());
      }
      MockClientHttpResponse response = new MockClientHttpResponse(
          OPEN_METEO_DAILY.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
      response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
      MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
      request.setResponse(response);
      return request;
    };
    return new TravelRiskService(RestClient.builder().requestFactory(requestFactory), new ObjectMapper(),
        "test-agent", "", "test-model", "", reported::add);
  }

  private static Map<String, String> statuses(TravelRiskService.Assessment assessment) {
    return assessment.dataSources().stream().collect(Collectors.toMap(SourceCheck::name, SourceCheck::status));
  }
}
