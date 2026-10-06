package com.travelrisk.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class TravelRiskServiceAlternativesTest {
  private final LocalDate tripDate = LocalDate.now(ZoneOffset.UTC).plusDays(2);
  private final AtomicInteger forecastCalls = new AtomicInteger();

  /** Chicago is stormy on the trip date (worst in the evening), calm the day after, showery the day before. */
  private String forecast(double lat, String start, String end) {
    boolean chicago = lat > 41.5 && lat < 42.5;
    List<String> days = new ArrayList<>();
    for (LocalDate day = LocalDate.parse(start); !day.isAfter(LocalDate.parse(end)); day = day.plusDays(1)) {
      days.add(day.toString());
    }
    List<String> probability = new ArrayList<>();
    List<String> gusts = new ArrayList<>();
    List<String> hourTimes = new ArrayList<>();
    List<String> hourProbability = new ArrayList<>();
    List<String> hourRain = new ArrayList<>();
    List<String> hourGusts = new ArrayList<>();
    for (String day : days) {
      boolean tripDay = day.equals(tripDate.toString());
      boolean dayBefore = day.equals(tripDate.minusDays(1).toString());
      probability.add(!chicago ? "10" : tripDay ? "90" : dayBefore ? "65" : "10");
      gusts.add(chicago && tripDay ? "80" : "20");
      for (int hour = 0; hour < 24; hour += 1) {
        boolean storm = chicago && tripDay && hour >= 15;
        hourTimes.add("\"" + day + "T%02d:00\"".formatted(hour));
        hourProbability.add(storm ? "90" : "10");
        hourRain.add(storm ? "8" : "0");
        hourGusts.add(storm ? "80" : "20");
      }
    }
    return """
        {"daily": {"time": %s, "precipitation_probability_max": %s, "precipitation_sum": %s,
          "wind_speed_10m_max": %s, "wind_gusts_10m_max": %s, "temperature_2m_max": %s, "temperature_2m_min": %s},
         "hourly": {"time": %s, "precipitation_probability": %s, "precipitation": %s, "wind_speed_10m": %s, "wind_gusts_10m": %s}}
        """.formatted(quoted(days), probability, zeros(days.size()), zeros(days.size()), gusts, zeros(days.size()),
        zeros(days.size()), hourTimes, hourProbability, hourRain, zeros(hourTimes.size()), hourGusts);
  }

  private String body(java.net.URI uri) {
    Map<String, String> query = UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap();
    String host = uri.getHost();
    if (host.contains("open-meteo")) {
      forecastCalls.incrementAndGet();
      return forecast(Double.parseDouble(query.get("latitude")), query.get("start_date"), query.get("end_date"));
    }
    if (host.contains("aviationweather")) {
      double south = Double.parseDouble(query.get("bbox").split(",")[0]);
      if (south < 40 && south > 38) {
        return """
            [{"icaoId":"KJFK","lat":40.64,"lon":-73.78,"fltCat":"IFR","rawOb":"KJFK IFR"},
             {"icaoId":"KLGA","lat":40.78,"lon":-73.87,"fltCat":"MVFR","rawOb":"KLGA MVFR"},
             {"icaoId":"KEWR","lat":40.69,"lon":-74.17,"fltCat":"VFR","rawOb":"KEWR VFR"}]
            """;
      }
      return """
          [{"icaoId":"KORD","lat":41.98,"lon":-87.90,"fltCat":"VFR","rawOb":"KORD VFR"},
           {"icaoId":"KMDW","lat":41.79,"lon":-87.75,"fltCat":"VFR","rawOb":"KMDW VFR"}]
          """;
    }
    if (host.contains("faa")) {
      return """
          <AIRPORT_STATUS_INFORMATION><Update_Time>now</Update_Time>
            <Ground_Delay><ARPT>JFK</ARPT><Reason>low ceilings</Reason><Avg>45 minutes</Avg><Max>1 hour</Max></Ground_Delay>
          </AIRPORT_STATUS_INFORMATION>
          """;
    }
    return "{\"features\":[]}";
  }

  private TravelRiskService service() {
    ClientHttpRequestFactory requestFactory = (uri, method) -> {
      MockClientHttpResponse response = new MockClientHttpResponse(body(uri).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
      response.getHeaders().set(HttpHeaders.CONTENT_TYPE,
          uri.getHost().contains("faa") ? MediaType.APPLICATION_XML_VALUE : MediaType.APPLICATION_JSON_VALUE);
      MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
      request.setResponse(response);
      return request;
    };
    return new TravelRiskService(RestClient.builder().requestFactory(requestFactory), new ObjectMapper(),
        "test-agent", "", "test-model", "");
  }

  @Test
  void highRiskTripGetsRescoredOptionsBestFirst() {
    TravelRiskService service = service();
    TravelRiskService.Assessment base = service.analyze("New York, NY", "Chicago, IL", tripDate.toString(), "flight", "", "");
    assertThat(base.score().level()).isEqualTo("High");
    assertThat(base.score().points()).isEqualTo(29);
    List<Integer> pointsBefore = base.signals().stream().map(TravelRiskService.Signal::points).toList();
    forecastCalls.set(0);

    TravelRiskService.Alternatives alternatives = service.alternatives(base);

    assertThat(forecastCalls.get()).as("one forecast call per route point covers every date and time option").isEqualTo(3);
    assertThat(alternatives.options()).extracting(TravelRiskService.Alternative::kind)
        .containsExactly("combined", "origin-airport", "later-day", "time-of-day");
    assertThat(alternatives.options()).extracting(option -> option.score().points()).containsExactly(0, 12, 17, 17);
    assertThat(alternatives.checked()).isEqualTo(2 + 3 + 2 + 1);

    TravelRiskService.Alternative combined = alternatives.options().get(0);
    assertThat(combined.title()).isEqualTo("Fly out of Newark (EWR) a day later");
    assertThat(combined.when()).endsWith(" · instead of JFK");
    assertThat(combined.score().level()).isEqualTo("Low");
    assertThat(combined.input().date()).isEqualTo(tripDate.plusDays(1).toString());
    assertThat(combined.input().originAirport()).isEqualTo("KEWR");
    assertThat(combined.checkable()).isTrue();

    TravelRiskService.Alternative airport = alternatives.options().get(1);
    assertThat(airport.title()).isEqualTo("Fly out of Newark (EWR)");
    assertThat(airport.when()).isEqualTo("Instead of JFK");
    assertThat(airport.input().originAirport()).isEqualTo("KEWR");
    assertThat(airport.pointsSaved()).isEqualTo(17);
    assertThat(airport.reasons()).containsExactly(
        "EWR has no FAA delays or closures; JFK has a ground delay program.",
        "EWR is reporting clear flying weather (VFR); JFK is reporting low clouds or poor visibility (IFR).");

    TravelRiskService.Alternative later = alternatives.options().get(2);
    assertThat(later.input().date()).isEqualTo(tripDate.plusDays(1).toString());
    assertThat(later.checkable()).isTrue();
    assertThat(later.reasons()).containsExactly("Chicago, IL: rain chance 90% → 10%, gusts 80 → 20 km/h");

    TravelRiskService.Alternative morning = alternatives.options().get(3);
    assertThat(morning.title()).isEqualTo("Take a morning flight");
    assertThat(morning.when()).isEqualTo("6 AM to noon, local time");
    assertThat(morning.checkable()).isFalse();
    assertThat(morning.reasons()).containsExactly(
        "Chicago, IL (vs afternoon): rain chance 90% → 10%, heaviest rain 8.0 → 0.0 mm/h, gusts 80 → 20 km/h");

    assertThat(base.signals()).extracting(TravelRiskService.Signal::points)
        .as("re-scoring must not change the cached assessment").isEqualTo(pointsBefore);
  }

  @Test
  void airportsThatDoNotBeatThePlannedOneAreNotSuggested() {
    TravelRiskService service = service();
    TravelRiskService.Assessment base = service.analyze("New York, NY", "Chicago, IL", tripDate.toString(), "flight", "", "KORD");

    TravelRiskService.Alternatives alternatives = service.alternatives(base);

    assertThat(alternatives.options()).noneMatch(option -> "destination-airport".equals(option.kind()));
  }

  @Test
  void drivingTripsOnlyGetDateAndTimeOptions() {
    TravelRiskService service = service();
    TravelRiskService.Assessment base = service.analyze("New York, NY", "Chicago, IL", tripDate.toString(), "driving", "", "");

    TravelRiskService.Alternatives alternatives = service.alternatives(base);

    assertThat(alternatives.options()).extracting(TravelRiskService.Alternative::kind)
        .containsExactlyInAnyOrder("later-day", "earlier-day", "time-of-day");
    assertThat(alternatives.options()).filteredOn(option -> "time-of-day".equals(option.kind()))
        .extracting(TravelRiskService.Alternative::title).containsExactly("Leave in the morning");
    assertThat(alternatives.note()).contains("are as of right now");
  }

  @Test
  void lowRiskTripsSkipTheSearch() {
    TravelRiskService service = service();
    TravelRiskService.Assessment base = service.analyze("Seattle, WA", "Portland, OR", tripDate.toString(), "driving", "", "");
    assertThat(base.score().level()).isEqualTo("Low");
    forecastCalls.set(0);

    TravelRiskService.Alternatives alternatives = service.alternatives(base);

    assertThat(alternatives.options()).isEmpty();
    assertThat(alternatives.checked()).isZero();
    assertThat(forecastCalls.get()).isZero();
  }

  private static List<String> quoted(List<String> values) {
    return values.stream().map(value -> "\"" + value + "\"").toList();
  }

  private static List<String> zeros(int count) {
    return java.util.Collections.nCopies(count, "0");
  }
}
