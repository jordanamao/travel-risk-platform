package com.travelrisk.platform.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelrisk.platform.controllers.AnalyzeController;
import com.travelrisk.platform.controllers.SavedTripController;
import com.travelrisk.platform.service.AssessmentHistoryService;
import com.travelrisk.platform.service.SavedTripService;
import com.travelrisk.platform.service.TravelRiskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest({AnalyzeController.class, SavedTripController.class})
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerWebMvcTest {
  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private TravelRiskService travelRiskService;

  @MockitoBean
  private AssessmentHistoryService historyService;

  @MockitoBean
  private SavedTripService savedTripService;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @Test
  void analyzeWithoutOriginDestinationOrDateListsWhatIsMissing() throws Exception {
    mockMvc.perform(get("/api/analyze"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"error\":\"Origin is required. Destination is required. Date is required.\"}", true));

    verify(travelRiskService, never()).analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void analyzeWithBlankDateNamesOnlyTheDate() throws Exception {
    mockMvc.perform(get("/api/analyze").param("origin", "A").param("destination", "B").param("date", " "))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"error\":\"Date is required.\"}", true));
  }

  @Test
  void analyzeDefaultsModeAndAirportsWhenOmitted() throws Exception {
    mockMvc.perform(analyze()).andExpect(status().isOk());

    verify(travelRiskService).analyze("A", "B", "2026-09-28", "flight", "", "");
  }

  @Test
  void illegalArgumentBecomes400() throws Exception {
    when(travelRiskService.analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalArgumentException("Travel date must be today or later."));

    mockMvc.perform(analyze())
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"error\":\"Travel date must be today or later.\"}", true));
  }

  @Test
  void unexpectedErrorBecomes500WithoutLeakingDetails() throws Exception {
    when(travelRiskService.analyze(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("db password is hunter2"));

    mockMvc.perform(analyze())
        .andExpect(status().isInternalServerError())
        .andExpect(content().json("{\"error\":\"Unexpected server error. Please try again.\"}", true));
  }

  @Test
  void missingRecordBecomes404() throws Exception {
    doThrow(new ResourceNotFoundException("Saved trip not found."))
        .when(savedTripService).delete("employee1@email.com", 99L);

    mockMvc.perform(delete("/api/trips/99").principal(() -> "employee1@email.com"))
        .andExpect(status().isNotFound())
        .andExpect(content().json("{\"error\":\"Saved trip not found.\"}", true));
  }

  @Test
  void malformedJsonBodyBecomes400() throws Exception {
    mockMvc.perform(post("/api/trips")
            .principal(() -> "employee1@email.com")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(content().json("{\"error\":\"Request body is missing or is not valid JSON.\"}", true));
  }

  @Test
  void unsupportedMethodKeepsItsStatus() throws Exception {
    mockMvc.perform(post("/api/analyze/cache"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().json("{\"error\":\"This HTTP method is not supported for this path.\"}", true));
  }

  private static MockHttpServletRequestBuilder analyze() {
    return get("/api/analyze").param("origin", "A").param("destination", "B").param("date", "2026-09-28");
  }
}
