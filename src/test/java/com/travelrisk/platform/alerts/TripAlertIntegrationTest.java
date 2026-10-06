package com.travelrisk.platform.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:travelrisk-trip-alerts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "travel-risk.demo-data.enabled=true",
        "travel-risk.demo-admin.username=demo-admin@email.com",
        "travel-risk.demo-admin.password=demo-admin-pass",
        "travel-risk.trip-alerts.email.to=me@example.test",
        "travel-risk.trip-alerts.app-url=https://travel-risk.example.test"
    })
class TripAlertIntegrationTest {
  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  @Autowired
  private RecordingChannel recording;

  @Test
  void anEmployeeCanSendTheSeededAlertOutAndTheDemoAdminCannot() {
    HttpEntity<Void> employee = new HttpEntity<>(authHeaders("employee1@email.com", "travel-risk-demo"));

    JsonNode channels = restTemplate.exchange(url("/api/notifications/channels"), HttpMethod.GET, employee, JsonNode.class).getBody();
    assertThat(channels.get("channels").toString()).isEqualTo("[\"test\"]");

    JsonNode notifications = restTemplate.exchange(url("/api/notifications"), HttpMethod.GET, employee, JsonNode.class).getBody();
    long id = notifications.get(0).get("id").asLong();

    ResponseEntity<JsonNode> sent = restTemplate.exchange(url("/api/notifications/" + id + "/send"), HttpMethod.POST, employee, JsonNode.class);
    assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
    await().atMost(Duration.ofSeconds(5)).until(() -> !recording.alerts.isEmpty());
    TripAlert alert = recording.alerts.get(0);
    assertThat(alert.recipientEmail()).isEqualTo("me@example.test");
    assertThat(alert.route()).isEqualTo("Dallas, TX to Orlando, FL");
    assertThat(alert.change()).startsWith("Medium (").contains(") to High (");
    assertThat(alert.reason()).isNotBlank();
    assertThat(alert.appUrl()).isEqualTo("https://travel-risk.example.test");

    ResponseEntity<JsonNode> again = restTemplate.exchange(url("/api/notifications/" + id + "/send"), HttpMethod.POST, employee, JsonNode.class);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(again.getBody().get("error").asText()).isEqualTo("This alert was just sent. Try again in a couple of minutes.");

    HttpEntity<Void> demoAdmin = new HttpEntity<>(authHeaders("demo-admin@email.com", "demo-admin-pass"));
    assertThat(restTemplate.exchange(url("/api/notifications/" + id + "/send"), HttpMethod.POST, demoAdmin, JsonNode.class)
        .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private HttpHeaders authHeaders(String username, String password) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> token = restTemplate.postForEntity(
        url("/api/auth/token"), new HttpEntity<>(new LoginRequest(username, password), headers), JsonNode.class);
    assertThat(token.getStatusCode()).isEqualTo(HttpStatus.OK);
    HttpHeaders authorized = new HttpHeaders();
    authorized.setBearerAuth(token.getBody().get("accessToken").asText());
    return authorized;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private record LoginRequest(String username, String password) {}

  static class RecordingChannel implements TripAlertChannel {
    final List<TripAlert> alerts = new CopyOnWriteArrayList<>();

    public String name() { return "test"; }
    public boolean configured() { return true; }
    public void send(TripAlert alert) { alerts.add(alert); }
  }

  @TestConfiguration
  static class Channels {
    @Bean
    RecordingChannel recordingChannel() {
      return new RecordingChannel();
    }
  }
}
