package com.travelrisk.platform.monitoring;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ApiMonitoringAspect {
  private static final Logger log = LoggerFactory.getLogger(ApiMonitoringAspect.class);
  private static final Map<String, String> FRIENDLY_NAMES = Map.ofEntries(
      Map.entry("AnalyzeController.analyze", "Risk check"),
      Map.entry("AnalyzeController.refreshAssessment", "Risk check (refresh)"),
      Map.entry("AnalyzeController.evictAssessment", "Clear cached risk check"),
      Map.entry("SavedTripController.list", "Load saved trips"),
      Map.entry("SavedTripController.save", "Save trip"),
      Map.entry("SavedTripController.checkAlerts", "Check trip alerts"),
      Map.entry("SavedTripController.delete", "Delete saved trip"),
      Map.entry("TripNotificationController.list", "Load notifications"),
      Map.entry("TripNotificationController.markRead", "Mark notification read"),
      Map.entry("AdminDashboardController.dashboard", "Admin dashboard"),
      Map.entry("AdminDashboardController.clearAssessmentHistory", "Clear assessment history"),
      Map.entry("AuthController.token", "API sign-in"),
      Map.entry("DemoAccountsController.demoAccounts", "Demo login details"),
      Map.entry("SourceHealthController.sourceHealth", "Data source health"),
      Map.entry("TravelPolicyController.policy", "Load travel policy"),
      Map.entry("TravelPolicyController.evaluate", "Check trip against policy"),
      Map.entry("ItineraryImportController.importItinerary", "Import itinerary"),
      Map.entry("ItineraryImportController.sample", "Download itinerary sample"));

  private final ApiMonitoringService monitoringService;
  private final long slowCallThresholdMs;

  public ApiMonitoringAspect(
      ApiMonitoringService monitoringService,
      @Value("${travel-risk.monitoring.slow-call-threshold-ms:1500}") long slowCallThresholdMs) {
    this.monitoringService = monitoringService;
    this.slowCallThresholdMs = slowCallThresholdMs;
  }

  // One timing per API request: REST endpoints only. Page forwards (/login) and the
  // health probe are not API work, and timing the service under an endpoint too
  // would count the same slow request twice.
  @Around("within(com.travelrisk.platform.controllers..*)"
      + " && @within(org.springframework.web.bind.annotation.RestController)"
      + " && !within(com.travelrisk.platform.controllers.HealthController)")
  public Object monitor(ProceedingJoinPoint joinPoint) throws Throwable {
    String operation = operationName(joinPoint);
    long start = System.nanoTime();
    try {
      Object result = joinPoint.proceed();
      long durationMs = elapsedMs(start);
      monitoringService.recordSuccess(operation, durationMs);
      if (durationMs >= slowCallThresholdMs) {
        log.warn("slow_api_call operation={} durationMs={} thresholdMs={}", operation, durationMs, slowCallThresholdMs);
      } else {
        log.info("api_call operation={} durationMs={}", operation, durationMs);
      }
      return result;
    } catch (Throwable error) {
      long durationMs = elapsedMs(start);
      monitoringService.recordFailure(operation, durationMs, error);
      log.error("api_call_failed operation={} durationMs={} error={}", operation, durationMs, error.toString());
      throw error;
    }
  }

  private String operationName(ProceedingJoinPoint joinPoint) {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    String codeName = signature.getDeclaringType().getSimpleName() + "." + signature.getName();
    return FRIENDLY_NAMES.getOrDefault(codeName, codeName);
  }

  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
