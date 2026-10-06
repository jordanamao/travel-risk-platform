package com.travelrisk.platform.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(addFilters = false)
class HealthControllerWebMvcTest {
  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private JwtDecoder jwtDecoder;

  @Test
  void healthReturnsUp() throws Exception {
    mockMvc.perform(get("/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
