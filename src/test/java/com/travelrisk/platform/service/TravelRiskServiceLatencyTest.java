package com.travelrisk.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TravelRiskServiceLatencyTest {
  private static final String DATE = LocalDate.now().plusDays(2).toString();

  private TravelRiskService service(SlowFakeApis apis, long sourceTimeoutMs) {
    return new TravelRiskService(
        RestClient.builder().requestFactory(apis), new ObjectMapper(), "test-agent", "test-key", "test-model", "",
        sourceTimeoutMs, 5000);
  }

  @Test
  void outsideSourcesAreFetchedInParallel() {
    // About 14 outside calls at 300 ms each: one after another that is over 4 s.
    SlowFakeApis apis = new SlowFakeApis(Map.of(
        "nominatim.openstreetmap.org", 300L,
        "api.open-meteo.com", 300L,
        "api.weather.gov", 300L,
        "aviationweather.gov", 300L,
        "nasstatus.faa.gov", 300L,
        "api.openai.com", 300L));

    long start = System.nanoTime();
    TravelRiskService.Assessment assessment = service(apis, 4000).analyze("Boise, ID", "Tulsa, OK", DATE, "flight", "", "");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(assessment.ai()).containsEntry("used", true);
    assertThat(elapsedMs).isLessThan(2500);
  }

  @Test
  void repeatLocationsAreNotGeocodedAgain() {
    SlowFakeApis apis = new SlowFakeApis(Map.of());
    TravelRiskService service = service(apis, 4000);

    service.analyze("Boise, ID", "Tulsa, OK", DATE, "flight", "", "");
    service.analyze("Boise, ID", "Tulsa, OK", LocalDate.now().plusDays(3).toString(), "flight", "", "");

    assertThat(apis.geocodeCalls).hasValue(2);
  }

  @Test
  void slowSourceIsReportedInsteadOfHoldingUpTheCheck() {
    SlowFakeApis apis = new SlowFakeApis(Map.of("nasstatus.faa.gov", 3000L));

    long start = System.nanoTime();
    TravelRiskService.Assessment assessment = service(apis, 500).analyze("New York, NY", "Chicago, IL", DATE, "flight", "", "");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMs).isLessThan(2000);
    assertThat(assessment.evidence())
        .anySatisfy(item -> assertThat(item.headline()).contains("took too long"));
  }
}
