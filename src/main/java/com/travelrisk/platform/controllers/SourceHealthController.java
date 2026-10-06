package com.travelrisk.platform.controllers;

import com.travelrisk.platform.monitoring.AlertService;
import com.travelrisk.platform.monitoring.SourceHealthMonitor;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only view of each data source's latest status, used by the runbook. */
@RestController
public class SourceHealthController {
  private final SourceHealthMonitor monitor;
  private final AlertService alertService;

  public SourceHealthController(SourceHealthMonitor monitor, AlertService alertService) {
    this.monitor = monitor;
    this.alertService = alertService;
  }

  @GetMapping("/api/admin/source-health")
  public SourceHealthResponse sourceHealth() {
    return new SourceHealthResponse(alertService.webhookEnabled(), monitor.snapshot());
  }

  public record SourceHealthResponse(boolean alertWebhookEnabled, List<SourceHealthMonitor.SourceHealth> sources) {}
}
