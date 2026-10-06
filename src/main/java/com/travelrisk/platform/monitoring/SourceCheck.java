package com.travelrisk.platform.monitoring;

/**
 * How one external data source did during a single trip check.
 *
 * @param status {@code ok}, {@code degraded} (some calls failed), {@code unavailable} (every call
 *     failed), or {@code not_configured} (no API key for this deployment)
 */
public record SourceCheck(String name, String status, long durationMs) {
  public static final String OK = "ok";
  public static final String DEGRADED = "degraded";
  public static final String UNAVAILABLE = "unavailable";
  public static final String NOT_CONFIGURED = "not_configured";

  public boolean unavailable() {
    return UNAVAILABLE.equals(status);
  }
}
