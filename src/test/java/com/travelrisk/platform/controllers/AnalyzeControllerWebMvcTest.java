package com.travelrisk.platform.controllers;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.service.AssessmentHistoryService;
import com.travelrisk.platform.service.TravelRiskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AnalyzeController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyzeControllerWebMvcTest {
  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private TravelRiskService travelRiskService;

  @MockBean
  private AssessmentHistoryService historyService;

  @MockBean
  private JwtDecoder jwtDecoder;

  @Test
  void analyzeReturnsAssessmentJsonAndRecordsHistoryByDefault() throws Exception {
    var assessment = TestAssessments.assessment("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "High", 12);
    when(travelRiskService.analyze("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "", ""))
        .thenReturn(assessment);

    mockMvc.perform(get("/api/analyze")
            .param("origin", "New York, NY")
            .param("destination", "San Francisco, CA")
            .param("date", "2026-09-28")
            .param("mode", "flight"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.score.level").value("High"))
        .andExpect(jsonPath("$.score.points").value(12));

    verify(historyService).record("anonymous", assessment);
  }

  @Test
  void analyzeCanSkipHistoryForComparisonRequests() throws Exception {
    var assessment = TestAssessments.assessment("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "High", 12);
    when(travelRiskService.analyze("New York, NY", "San Francisco, CA", "2026-09-28", "flight", "", ""))
        .thenReturn(assessment);

    mockMvc.perform(get("/api/analyze")
            .param("origin", "New York, NY")
            .param("destination", "San Francisco, CA")
            .param("date", "2026-09-28")
            .param("mode", "flight")
            .param("recordHistory", "false"))
        .andExpect(status().isOk());

    verify(historyService, never()).record("anonymous", assessment);
  }
}
