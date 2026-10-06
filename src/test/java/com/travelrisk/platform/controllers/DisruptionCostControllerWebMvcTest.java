package com.travelrisk.platform.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelrisk.platform.cost.DisruptionCostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DisruptionCostController.class)
@Import(DisruptionCostService.class)
@AutoConfigureMockMvc(addFilters = false)
class DisruptionCostControllerWebMvcTest {
  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @Test
  void returnsEstimateWithBreakdown() throws Exception {
    mockMvc.perform(get("/api/disruption-cost").param("riskLevel", "High").param("mode", "flight"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expected").value(590))
        .andExpect(jsonPath("$.ifDisrupted").value(1170))
        .andExpect(jsonPath("$.items[0].label").value("Rebooking or change fee"));
  }

  @Test
  void unknownRiskLevelIsABadRequest() throws Exception {
    mockMvc.perform(get("/api/disruption-cost").param("riskLevel", "Severe"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("riskLevel must be Low, Medium or High."));
  }
}
