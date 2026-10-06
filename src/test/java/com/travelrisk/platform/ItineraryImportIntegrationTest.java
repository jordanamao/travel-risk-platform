package com.travelrisk.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelrisk.platform.service.TravelRiskService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:travelrisk-import-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "travel-risk.jwt.secret=test-secret-test-secret-test-secret-32",
        "travel-risk.security.employee-username-pattern=employee[0-9]+@email\\.com",
        "travel-risk.security.password=travel-risk-demo",
        "travel-risk.security.admin-username=admin@email.com",
        "travel-risk.security.admin-password=admin-secret-demo"
    })
class ItineraryImportIntegrationTest {
  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  @MockitoBean
  private TravelRiskService travelRiskService;

  @Test
  void importedTripsAreSavedWithTheirPolicyResult() {
    String date = LocalDate.now(ZoneOffset.UTC).plusDays(2).toString();
    when(travelRiskService.analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(TestAssessments.assessment("New York, NY", "Chicago, IL", date, "flight", "High", 12));

    HttpHeaders employee = authHeaders("employee7@email.com", "travel-risk-demo");
    ResponseEntity<String> sample = restTemplate.exchange(
        url("/api/trips/import/sample?format=csv"), HttpMethod.GET, new HttpEntity<>(employee), String.class);
    assertThat(sample.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(sample.getHeaders().getContentDisposition().getFilename()).isEqualTo("sample-itinerary.csv");
    assertThat(sample.getBody()).startsWith("traveler_email,origin,destination").contains("employee7@email.com");

    ResponseEntity<JsonNode> imported = upload(employee, "bookings.csv",
        "origin,destination,date,trip_type\n\"New York, NY\",\"Chicago, IL\"," + date + ",flight\n");
    assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(imported.getBody().get("imported").asInt()).isEqualTo(1);
    assertThat(imported.getBody().at("/rows/0/policy/label").asText()).isEqualTo("Needs approval");

    ResponseEntity<JsonNode> trips = restTemplate.exchange(url("/api/trips"), HttpMethod.GET, new HttpEntity<>(employee), JsonNode.class);
    assertThat(trips.getBody()).hasSize(1);
    assertThat(trips.getBody().at("/0/policy/outcome").asText()).isEqualTo("approval_required");

    ResponseEntity<JsonNode> missingColumns = upload(employee, "bad.csv", "origin,date\nA,2026-10-10\n");
    assertThat(missingColumns.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(missingColumns.getBody().get("error").asText()).startsWith("The CSV file is missing the destination column.");

    ResponseEntity<JsonNode> policy = restTemplate.exchange(url("/api/policy"), HttpMethod.GET, new HttpEntity<>(employee), JsonNode.class);
    assertThat(policy.getBody().get("company").asText()).isEqualTo("Demo Company");

    HttpHeaders admin = authHeaders("admin@email.com", "admin-secret-demo");
    ResponseEntity<JsonNode> dashboard = restTemplate.exchange(url("/api/admin/dashboard"), HttpMethod.GET, new HttpEntity<>(admin), JsonNode.class);
    assertThat(dashboard.getBody().at("/trips/0/policy/label").asText()).isEqualTo("Needs approval");
  }

  private ResponseEntity<JsonNode> upload(HttpHeaders auth, String filename, String content) {
    HttpHeaders headers = new HttpHeaders();
    headers.putAll(auth);
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("file", new ByteArrayResource(content.getBytes()) {
      @Override
      public String getFilename() {
        return filename;
      }
    });
    return restTemplate.postForEntity(url("/api/trips/import"), new HttpEntity<>(body, headers), JsonNode.class);
  }

  private HttpHeaders authHeaders(String username, String password) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<JsonNode> token = restTemplate.postForEntity(url("/api/auth/token"),
        new HttpEntity<>("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}", headers), JsonNode.class);
    HttpHeaders authorized = new HttpHeaders();
    authorized.setBearerAuth(token.getBody().get("accessToken").asText());
    return authorized;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }
}
