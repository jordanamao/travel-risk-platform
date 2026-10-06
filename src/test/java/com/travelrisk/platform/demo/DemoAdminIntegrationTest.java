package com.travelrisk.platform.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.database.entities.AssessmentHistory;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.repository.AssessmentHistoryRepository;
import com.travelrisk.platform.repository.SavedTripRepository;
import java.util.ArrayList;
import java.util.List;
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

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:travelrisk-demo-admin;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "travel-risk.demo-data.enabled=true",
        "travel-risk.demo-admin.username=demo-admin@email.com",
        "travel-risk.demo-admin.password=demo-admin-pass",
        "travel-risk.security.admin-username=admin@email.com",
        "travel-risk.security.admin-password=admin-secret-demo"
    })
class DemoAdminIntegrationTest {
  private static final String REAL_USER = "103948572938475@google";

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  @Autowired
  private SavedTripRepository trips;

  @Autowired
  private AssessmentHistoryRepository history;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void demoAdminSeesOnlyDemoAccountsAndCannotChangeAnything() throws Exception {
    var real = TestAssessments.assessment("Austin, TX", "Houston, TX", "2026-10-09", "driving", "High", 12);
    trips.save(new SavedTrip(REAL_USER, real, objectMapper.writeValueAsString(real)));
    history.save(new AssessmentHistory(REAL_USER, real, objectMapper.writeValueAsString(real)));

    HttpEntity<Void> demoAdmin = new HttpEntity<>(authHeaders("demo-admin@email.com", "demo-admin-pass"));
    ResponseEntity<JsonNode> dashboard = restTemplate.exchange(url("/api/admin/dashboard"), HttpMethod.GET, demoAdmin, JsonNode.class);

    assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = dashboard.getBody();
    assertThat(body.get("readOnly").asBoolean()).isTrue();
    assertThat(body.get("stats").get("savedTrips").asLong()).isEqualTo(8);
    assertThat(body.get("stats").get("highRiskTrips").asLong()).isEqualTo(2);
    assertThat(body.get("stats").get("unreadAlerts").asLong()).isEqualTo(1);
    assertThat(usernames(body.get("trips"))).doesNotContain(REAL_USER);
    assertThat(usernames(body.get("history"))).doesNotContain(REAL_USER);

    assertThat(restTemplate.exchange(url("/api/admin/assessment-history"), HttpMethod.DELETE, demoAdmin, String.class)
        .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(restTemplate.exchange(url("/api/admin/source-health"), HttpMethod.GET, demoAdmin, String.class)
        .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    HttpEntity<Void> admin = new HttpEntity<>(authHeaders("admin@email.com", "admin-secret-demo"));
    JsonNode full = restTemplate.exchange(url("/api/admin/dashboard"), HttpMethod.GET, admin, JsonNode.class).getBody();
    assertThat(full.get("readOnly").asBoolean()).isFalse();
    assertThat(usernames(full.get("trips"))).contains(REAL_USER);
  }

  @Test
  void loginPageCanShowTheDemoAdminLogin() {
    JsonNode body = restTemplate.getForObject(url("/api/auth/demo-accounts"), JsonNode.class);

    assertThat(body.get("demoAdmin").get("username").asText()).isEqualTo("demo-admin@email.com");
    assertThat(body.get("demoAdmin").get("password").asText()).isEqualTo("demo-admin-pass");
  }

  private static List<String> usernames(JsonNode rows) {
    List<String> names = new ArrayList<>();
    rows.forEach(row -> names.add(row.get("username").asText()));
    return names;
  }

  private HttpHeaders authHeaders(String username, String password) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> token = restTemplate.postForEntity(
        url("/api/auth/token"),
        new HttpEntity<>(new LoginRequest(username, password), headers),
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
}
