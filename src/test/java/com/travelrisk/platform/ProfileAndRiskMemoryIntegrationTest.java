package com.travelrisk.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelrisk.platform.service.TravelRiskService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:travelrisk-profiles;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "travel-risk.security.employee-username-pattern=employee[0-9]+@email\\.com",
        "travel-risk.security.password=travel-risk-demo"
    })
class ProfileAndRiskMemoryIntegrationTest {
  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  @MockitoBean
  private TravelRiskService travelRiskService;

  @Test
  void savedProfileShapesTheNextRiskCheck() {
    HttpHeaders auth = authHeaders("employee42@email.com");

    JsonNode defaults = restTemplate.exchange(url("/api/profile"), HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class)
        .getBody();
    assertThat(defaults.get("riskTolerance").asText()).isEqualTo("balanced");
    assertThat(defaults.get("notifications").get("email").asBoolean()).isTrue();
    assertThat(defaults.get("toleranceOptions")).hasSize(3);

    Map<String, Object> settings = Map.of(
        "homeCity", "Dallas, TX",
        "preferredMode", "flight",
        "riskTolerance", "cautious",
        "frequentRoutes", List.of(Map.of("origin", "Dallas, TX", "destination", "Orlando, FL", "mode", "flight")),
        "notifications", Map.of("email", false, "slack", true, "minLevel", "high"));
    ResponseEntity<JsonNode> saved = restTemplate.exchange(
        url("/api/profile"), HttpMethod.PUT, new HttpEntity<>(settings, auth), JsonNode.class);
    assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(saved.getBody().get("homeCity").asText()).isEqualTo("Dallas, TX");
    assertThat(saved.getBody().get("frequentRoutes").get(0).get("destination").asText()).isEqualTo("Orlando, FL");
    assertThat(saved.getBody().get("notifications").get("minLevel").asText()).isEqualTo("high");

    when(travelRiskService.analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TestAssessments.assessment("Dallas, TX", "Orlando, FL", "2026-10-10", "flight", "Low", 4));
    String analyze = url("/api/analyze?origin=Dallas, TX&destination=Orlando, FL&date=2026-10-10&mode=flight");

    JsonNode first = restTemplate.exchange(analyze, HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class).getBody();
    // The company result is unchanged and still at the top level; "personal" sits beside it.
    assertThat(first.get("score").get("level").asText()).isEqualTo("Low");
    assertThat(first.get("personal").get("status").asText()).isEqualTo("flagged");
    assertThat(first.get("personal").get("route").get("checks").asInt()).isZero();
    assertThat(first.get("personal").get("frequentRoute").asBoolean()).isTrue();

    JsonNode second = restTemplate.exchange(analyze, HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class).getBody();
    assertThat(second.get("personal").get("route").get("checks").asInt()).isEqualTo(1);
    assertThat(second.get("personal").get("notes").get(0).asText()).isEqualTo("Your 1 past check on this route was Low.");

    JsonNode profile = restTemplate.exchange(url("/api/profile"), HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class)
        .getBody();
    assertThat(profile.get("riskMemory").get("checks").asInt()).isEqualTo(2);
    assertThat(profile.get("riskMemory").get("routes").get(0).get("origin").asText()).isEqualTo("Dallas, TX");
  }

  @Test
  void invalidSettingsAreRefusedWithAReadableError() {
    HttpHeaders auth = authHeaders("employee43@email.com");

    ResponseEntity<JsonNode> response = restTemplate.exchange(url("/api/profile"), HttpMethod.PUT,
        new HttpEntity<>(Map.of("riskTolerance", "reckless"), auth), JsonNode.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().size()).isEqualTo(1);
    assertThat(response.getBody().get("error").asText()).isEqualTo("Risk tolerance must be cautious, balanced or flexible.");
  }

  private HttpHeaders authHeaders(String username) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> token = restTemplate.postForEntity(url("/api/auth/token"),
        new HttpEntity<>(Map.of("username", username, "password", "travel-risk-demo"), headers), JsonNode.class);
    assertThat(token.getStatusCode()).isEqualTo(HttpStatus.OK);
    HttpHeaders authorized = new HttpHeaders();
    authorized.setContentType(MediaType.APPLICATION_JSON);
    authorized.setBearerAuth(token.getBody().get("accessToken").asText());
    return authorized;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
