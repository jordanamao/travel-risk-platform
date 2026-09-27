package com.travelrisk.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.service.TravelRiskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:travelrisk-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "travel-risk.jwt.secret=test-secret-test-secret-test-secret-32",
        "travel-risk.security.username=employee",
        "travel-risk.security.password=travel-risk-demo"
    })
class InternalApiIntegrationTest {
  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  @MockBean
  private TravelRiskService travelRiskService;

  @Test
  void callsPrimaryApplicationApisWithPersistence() throws Exception {
    var assessment = TestAssessments.assessment("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "High", 12);
    when(travelRiskService.analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(assessment);

    HttpEntity<Void> authorized = new HttpEntity<>(authHeaders());
    String analyzeUrl = UriComponentsBuilder.fromHttpUrl(url("/api/analyze"))
        .queryParam("origin", "New York, NY")
        .queryParam("destination", "San Francisco, CA")
        .queryParam("date", "2026-09-28")
        .queryParam("mode", "flight")
        .toUriString();

    ResponseEntity<JsonNode> analyze = restTemplate.exchange(analyzeUrl, HttpMethod.GET, authorized, JsonNode.class);
    assertThat(analyze.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(analyze.getBody().at("/score/level").asText()).isEqualTo("High");

    HttpHeaders postHeaders = authHeaders();
    postHeaders.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> saveRequest = new HttpEntity<>(
        objectMapper.writeValueAsString(new SaveTripRequest(assessment)),
        postHeaders);
    ResponseEntity<JsonNode> savedTrip = restTemplate.postForEntity(url("/api/trips"), saveRequest, JsonNode.class);
    assertThat(savedTrip.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(savedTrip.getBody().get("riskLevel").asText()).isEqualTo("High");

    ResponseEntity<JsonNode> trips = restTemplate.exchange(url("/api/trips"), HttpMethod.GET, authorized, JsonNode.class);
    assertThat(trips.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(trips.getBody().get(0).get("origin").asText()).isEqualTo("New York, NY");

    ResponseEntity<JsonNode> notifications = restTemplate.exchange(url("/api/notifications"), HttpMethod.GET, authorized, JsonNode.class);
    assertThat(notifications.getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<JsonNode> dashboard = restTemplate.exchange(url("/api/admin/dashboard"), HttpMethod.GET, authorized, JsonNode.class);
    assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(dashboard.getBody().at("/stats/savedTrips").asLong()).isEqualTo(1);
    assertThat(dashboard.getBody().at("/stats/historyRecords").asLong()).isEqualTo(1);
  }

  private HttpHeaders authHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> token = restTemplate.postForEntity(
        url("/api/auth/token"),
        new HttpEntity<>(new LoginRequest("employee", "travel-risk-demo"), headers),
        JsonNode.class);
    assertThat(token.getStatusCode()).isEqualTo(HttpStatus.OK);

    HttpHeaders authorized = new HttpHeaders();
    authorized.setBearerAuth(token.getBody().get("accessToken").asText());
    return authorized;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private record LoginRequest(String username, String password) {}

  private record SaveTripRequest(TravelRiskService.Assessment assessment) {}
}
