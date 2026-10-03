package com.travelrisk.platform.ratelimit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.service.TravelRiskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:travelrisk-ratelimit-off;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "travel-risk.jwt.secret=test-secret-test-secret-test-secret-32",
    "travel-risk.security.username=employee@email.com",
    "travel-risk.security.password=travel-risk-demo",
    "travel-risk.rate-limit.enabled=false",
    "travel-risk.rate-limit.analyze.requests=1",
    "travel-risk.rate-limit.token.requests=1"
})
@AutoConfigureMockMvc
class RateLimitDisabledIntegrationTest {
  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private TravelRiskService travelRiskService;

  @Test
  void disabledFlagTurnsOffBothLimits() throws Exception {
    var assessment = TestAssessments.assessment("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "High", 12);
    when(travelRiskService.analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn(assessment);

    String token = null;
    for (int i = 0; i < 5; i++) {
      var result = mockMvc.perform(post("/api/auth/token")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"username\":\"employee@email.com\",\"password\":\"travel-risk-demo\"}"))
          .andExpect(status().isOk())
          .andReturn();
      token = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    for (int i = 0; i < 5; i++) {
      mockMvc.perform(get("/api/analyze")
              .param("origin", "New York, NY")
              .param("destination", "San Francisco, CA")
              .param("date", "2026-09-28")
              .param("recordHistory", "false")
              .header("Authorization", "Bearer " + token))
          .andExpect(status().isOk());
    }
  }
}
