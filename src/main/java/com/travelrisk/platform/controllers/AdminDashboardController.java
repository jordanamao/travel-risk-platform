package com.travelrisk.platform.controllers;

import com.travelrisk.platform.demo.DemoAccounts;
import com.travelrisk.platform.service.AdminDashboardService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminDashboardController {
  private final AdminDashboardService service;
  private final DemoAccounts demoAccounts;

  public AdminDashboardController(AdminDashboardService service, DemoAccounts demoAccounts) {
    this.service = service;
    this.demoAccounts = demoAccounts;
  }

  @PreAuthorize("hasAnyRole('ADMIN', 'DEMO_ADMIN')")
  @GetMapping("/api/admin/dashboard")
  public AdminDashboardService.AdminDashboardResponse dashboard(Authentication authentication) {
    boolean admin = authentication.getAuthorities().stream().anyMatch(role -> "ROLE_ADMIN".equals(role.getAuthority()));
    return admin ? service.getDashboard() : service.getDemoDashboard(demoAccounts::isDemoEmployee);
  }

  @PreAuthorize("hasRole('ADMIN')")
  @DeleteMapping("/api/admin/assessment-history")
  public Map<String, Long> clearAssessmentHistory() {
    long deleted = service.clearAssessmentHistory();
    return Map.of("deleted", deleted);
  }
}
