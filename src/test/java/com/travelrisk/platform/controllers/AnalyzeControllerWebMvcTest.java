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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AnalyzeController.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyzeControllerWebMvcTest {
  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private TravelRiskService travelRiskService;

  @MockitoBean
  private AssessmentHistoryService historyService;

  @MockitoBean
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

  @Test
  void alternativesReuseTheCheckAndSkipHistory() throws Exception {
    var assessment = TestAssessments.assessment("New York, NY", "Chicago, IL", "2026-09-28", "flight", "High", 14);
    when(travelRiskService.analyze("New York, NY", "Chicago, IL", "2026-09-28", "flight", "", ""))
        .thenReturn(assessment);
    var later = new TravelRiskService.Alternative("later-day", "Leave a day later", "Tue, Sep 29",
        new TravelRiskService.Input("New York, NY", "Chicago, IL", "2026-09-29", "flight", "", ""),
        new TravelRiskService.Score(4, "Low", "medium"), 10, List.of("Chicago, IL: rain chance 90% → 10%"), true);
    when(travelRiskService.alternatives(assessment))
        .thenReturn(new TravelRiskService.Alternatives(assessment.score(), List.of(later), 5, "Re-scored."));

    mockMvc.perform(get("/api/analyze/alternatives")
            .param("origin", "New York, NY")
            .param("destination", "Chicago, IL")
            .param("date", "2026-09-28")
            .param("mode", "flight"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.current.points").value(14))
        .andExpect(jsonPath("$.options[0].title").value("Leave a day later"))
        .andExpect(jsonPath("$.options[0].input.date").value("2026-09-29"))
        .andExpect(jsonPath("$.options[0].pointsSaved").value(10))
        .andExpect(jsonPath("$.checked").value(5));

    verify(historyService, never()).record("anonymous", assessment);
  }

  @Test
  void alternativesNeedATrip() throws Exception {
    mockMvc.perform(get("/api/analyze/alternatives").param("origin", "New York, NY"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }
}
