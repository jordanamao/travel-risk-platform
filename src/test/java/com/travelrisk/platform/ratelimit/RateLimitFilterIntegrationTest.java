package com.travelrisk.platform.ratelimit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.service.TravelRiskService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:travelrisk-ratelimit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "travel-risk.jwt.secret=test-secret-test-secret-test-secret-32",
    "travel-risk.security.employee-username-pattern=employee[0-9]+@email\\.com",
    "travel-risk.security.password=travel-risk-demo",
    "travel-risk.rate-limit.enabled=true",
    "travel-risk.rate-limit.analyze.requests=2",
    "travel-risk.rate-limit.analyze.window=60s",
    "travel-risk.rate-limit.token.requests=2",
    "travel-risk.rate-limit.token.window=60s"
})
@AutoConfigureMockMvc
class RateLimitFilterIntegrationTest {
  private static final String LOGIN_JSON = "{\"username\":\"employee100@email.com\",\"password\":\"travel-risk-demo\"}";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtEncoder jwtEncoder;

  @MockitoBean
  private TravelRiskService travelRiskService;

  @BeforeEach
  void stubService() {
    var assessment = TestAssessments.assessment("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "High", 12);
    when(travelRiskService.analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(assessment);
  }

  @Test
  void analyzeUnderLimitPassesThenOverLimitReturns429WithoutCallingService() throws Exception {
    String token = tokenFor("under-over-user");

    mockMvc.perform(analyze(token)).andExpect(status().isOk());
    mockMvc.perform(analyze(token)).andExpect(status().isOk());
    mockMvc.perform(analyze(token))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9]\\d*")))
        .andExpect(jsonPath("$.error").isNotEmpty());

    verify(travelRiskService, times(2)).analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void analyzeLimitIsTrackedPerUser() throws Exception {
    String userA = tokenFor("user-a");
    String userB = tokenFor("user-b");

    mockMvc.perform(analyze(userA)).andExpect(status().isOk());
    mockMvc.perform(analyze(userA)).andExpect(status().isOk());
    mockMvc.perform(analyze(userA)).andExpect(status().isTooManyRequests());

    mockMvc.perform(analyze(userB)).andExpect(status().isOk());
    mockMvc.perform(analyze(userB)).andExpect(status().isOk());
    mockMvc.perform(analyze(userB)).andExpect(status().isTooManyRequests());
  }

  @Test
  void analyzeLimitIsNotSharedAcrossIpsForAuthenticatedUsers() throws Exception {
    String token = tokenFor("roaming-user");

    mockMvc.perform(analyze(token).with(remoteAddr("10.1.0.1"))).andExpect(status().isOk());
    mockMvc.perform(analyze(token).with(remoteAddr("10.1.0.2"))).andExpect(status().isOk());
    // Same user from a third IP is still limited: the key is the user, not the IP.
    mockMvc.perform(analyze(token).with(remoteAddr("10.1.0.3"))).andExpect(status().isTooManyRequests());
  }

  @Test
  void unauthenticatedAnalyzeFallsBackToClientIp() throws Exception {
    // Not authenticated, so the controller is never reached (401), but the IP bucket still fills.
    mockMvc.perform(get("/api/analyze").queryParams(params()).with(remoteAddr("10.2.0.1")))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/analyze").queryParams(params()).with(remoteAddr("10.2.0.1")))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/analyze").queryParams(params()).with(remoteAddr("10.2.0.1")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    mockMvc.perform(get("/api/analyze").queryParams(params()).with(remoteAddr("10.2.0.2")))
        .andExpect(status().isUnauthorized());
    verify(travelRiskService, never()).analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void tokenEndpointIsLimitedPerClientIp() throws Exception {
    mockMvc.perform(tokenRequest("10.3.0.1")).andExpect(status().isOk());
    mockMvc.perform(tokenRequest("10.3.0.1")).andExpect(status().isOk());
    mockMvc.perform(tokenRequest("10.3.0.1"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.error").isNotEmpty());

    mockMvc.perform(tokenRequest("10.3.0.2")).andExpect(status().isOk());
  }

  @Test
  void tokenLimitDoesNotConsumeAnalyzeBudget() throws Exception {
    String token = tokenFor("independent-limits-user");
    mockMvc.perform(tokenRequest("10.4.0.1")).andExpect(status().isOk());
    mockMvc.perform(tokenRequest("10.4.0.1")).andExpect(status().isOk());
    mockMvc.perform(tokenRequest("10.4.0.1")).andExpect(status().isTooManyRequests());

    mockMvc.perform(analyze(token)).andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder analyze(String token) {
    return get("/api/analyze")
        .queryParams(params())
        .header("Authorization", "Bearer " + token);
  }

  private org.springframework.util.MultiValueMap<String, String> params() {
    var params = new org.springframework.util.LinkedMultiValueMap<String, String>();
    params.add("origin", "New York, NY");
    params.add("destination", "San Francisco, CA");
    params.add("date", "2026-09-28");
    params.add("mode", "flight");
    params.add("recordHistory", "false");
    return params;
  }

  private MockHttpServletRequestBuilder tokenRequest(String ip) {
    return post("/api/auth/token")
        .contentType(MediaType.APPLICATION_JSON)
        .content(LOGIN_JSON)
        .with(remoteAddr(ip));
  }

  private static RequestPostProcessor remoteAddr(String ip) {
    return (MockHttpServletRequest request) -> {
      request.setRemoteAddr(ip);
      return request;
    };
  }

  private String tokenFor(String subject) {
    Instant now = Instant.now();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("travel-risk-platform")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(600))
        .subject(subject)
        .claim("scope", "ROLE_USER")
        .build();
    return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
