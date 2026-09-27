package com.travelrisk.platform.controllers;

import com.travelrisk.platform.service.AdminDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminDashboardController {
  private final AdminDashboardService service;

  public AdminDashboardController(AdminDashboardService service) {
    this.service = service;
  }

  @GetMapping("/api/admin/dashboard")
  public AdminDashboardService.AdminDashboardResponse dashboard() {
    return service.getDashboard();
  }
}
