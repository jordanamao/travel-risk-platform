package com.travelrisk.platform.controllers;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelrisk.platform.brief.TripBrief;
import com.travelrisk.platform.brief.TripBriefService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TripBriefController.class)
@AutoConfigureMockMvc(addFilters = false)
class TripBriefControllerWebMvcTest {
  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private TripBriefService tripBriefService;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @Test
  void returnsTheBriefForTheTrip() throws Exception {
    when(tripBriefService.brief("New York, NY", "Chicago, IL", "2026-10-09", "flight", "", ""))
        .thenReturn(new TripBrief("High risk; get approval first.", TripBrief.CLAUDE, "claude-opus-5-5", null));

    mockMvc.perform(get("/api/analyze/brief")
            .param("origin", "New York, NY")
            .param("destination", "Chicago, IL")
            .param("date", "2026-10-09"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value("High risk; get approval first."))
        .andExpect(jsonPath("$.source").value("claude"))
        .andExpect(jsonPath("$.model").value("claude-opus-5-5"))
        .andExpect(jsonPath("$.note").doesNotExist());
  }

  @Test
  void missingTripFieldsAreABadRequest() throws Exception {
    mockMvc.perform(get("/api/analyze/brief").param("origin", "New York, NY"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }
}
