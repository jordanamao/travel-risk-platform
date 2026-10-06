package com.travelrisk.platform.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/** Answers every outside API the risk check calls with canned data after a per-host delay. */
class SlowFakeApis implements ClientHttpRequestFactory {
  private final Map<String, Long> delayMsByHost;
  final AtomicInteger geocodeCalls = new AtomicInteger();

  SlowFakeApis(Map<String, Long> delayMsByHost) {
    this.delayMsByHost = delayMsByHost;
  }

  @Override
  public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
    return new MockClientHttpRequest(method, uri) {
      @Override
      protected ClientHttpResponse executeInternal() {
        String host = uri.getHost();
        try {
          Thread.sleep(delayMsByHost.getOrDefault(host, 0L));
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
        }
        MockClientHttpResponse response = new MockClientHttpResponse(body(uri).getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        response.getHeaders().setContentType(host.contains("faa") ? MediaType.APPLICATION_XML : MediaType.APPLICATION_JSON);
        return response;
      }
    };
  }

  private String body(URI uri) {
    String host = uri.getHost();
    String path = uri.getPath();
    if (host.contains("nominatim")) {
      geocodeCalls.incrementAndGet();
      return "[{\"display_name\":\"Somewhere, US\",\"lat\":\"43.6\",\"lon\":\"-116.2\"}]";
    }
    if (host.contains("open-meteo")) {
      return "{\"daily\":{\"precipitation_probability_max\":[10],\"precipitation_sum\":[0],\"wind_speed_10m_max\":[12],"
          + "\"wind_gusts_10m_max\":[20],\"temperature_2m_max\":[22],\"temperature_2m_min\":[12]}}";
    }
    if (host.contains("weather.gov") && path.startsWith("/points")) {
      return "{\"properties\":{\"forecast\":\"https://api.weather.gov/gridpoints/OKX/1,1/forecast\"}}";
    }
    if (host.contains("weather.gov") && path.contains("forecast")) {
      return "{\"properties\":{\"periods\":[{\"name\":\"Today\",\"shortForecast\":\"Sunny\"}]}}";
    }
    if (host.contains("weather.gov")) {
      return "{\"features\":[]}";
    }
    if (host.contains("aviationweather")) {
      return "[{\"icaoId\":\"KJFK\",\"lat\":40.6,\"lon\":-73.8,\"fltCat\":\"VFR\",\"rawOb\":\"KJFK 061251Z\"}]";
    }
    if (host.contains("faa")) {
      return "<AIRPORT_STATUS_INFORMATION><Update_Time>now</Update_Time></AIRPORT_STATUS_INFORMATION>";
    }
    if (host.contains("openai")) {
      return "{\"output_text\":\"{\\\"summary\\\":\\\"Low risk.\\\",\\\"recommendation\\\":\\\"Go.\\\",\\\"uncertainty\\\":\\\"None.\\\"}\"}";
    }
    return "{}";
  }
}
