package com.travelrisk.platform.monitoring;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ApiMonitoringAspect {
  private static final Logger log = LoggerFactory.getLogger(ApiMonitoringAspect.class);

  private final ApiMonitoringService monitoringService;
  private final long slowCallThresholdMs;

  public ApiMonitoringAspect(
      ApiMonitoringService monitoringService,
      @Value("${travel-risk.monitoring.slow-call-threshold-ms:1500}") long slowCallThresholdMs) {
    this.monitoringService = monitoringService;
    this.slowCallThresholdMs = slowCallThresholdMs;
  }

  @Around("execution(* com.travelrisk.platform.controllers..*(..)) || execution(* com.travelrisk.platform.service.TravelRiskService.*(..))")
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
    return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
  }

  private long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
