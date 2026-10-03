package com.travelrisk.platform.controllers;

import com.travelrisk.platform.service.AdminDashboardService;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminDashboardController {
  private final AdminDashboardService service;

  public AdminDashboardController(AdminDashboardService service) {
    this.service = service;
  }

  @PreAuthorize("hasRole('ADMIN')")
  @GetMapping("/api/admin/dashboard")
  public AdminDashboardService.AdminDashboardResponse dashboard() {
    return service.getDashboard();
  }

  @PreAuthorize("hasRole('ADMIN')")
  @DeleteMapping("/api/admin/assessment-history")
  public Map<String, Long> clearAssessmentHistory() {
    long deleted = service.clearAssessmentHistory();
    return Map.of("deleted", deleted);
  }
}
