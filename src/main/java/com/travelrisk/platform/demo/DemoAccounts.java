package com.travelrisk.platform.demo;

import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The public demo logins. Employee accounts match the configured username pattern; the read-only
 * demo admin exists only while demo data is enabled, so the real admin login never has to be shared.
 */
@Component
public class DemoAccounts {
  private final boolean enabled;
  private final String adminUsername;
  private final String adminPassword;
  private final Pattern employeePattern;
  private final String employeePassword;

  public DemoAccounts(
      @Value("${travel-risk.demo-data.enabled:false}") boolean enabled,
      @Value("${travel-risk.demo-admin.username:demo-admin@email.com}") String adminUsername,
      @Value("${travel-risk.demo-admin.password:travel-risk-demo}") String adminPassword,
      @Value("${travel-risk.security.employee-username-pattern}") String employeeUsernamePattern,
      @Value("${travel-risk.security.password}") String employeePassword) {
    this.enabled = enabled;
    this.adminUsername = adminUsername.trim();
    this.adminPassword = adminPassword;
    this.employeePattern = Pattern.compile(employeeUsernamePattern, Pattern.CASE_INSENSITIVE);
    this.employeePassword = employeePassword;
  }

  public boolean adminEnabled() {
    return enabled && !adminUsername.isBlank() && !adminPassword.isBlank();
  }

  public boolean isDemoAdmin(String username) {
    return adminEnabled() && adminUsername.equalsIgnoreCase(username);
  }

  /** Demo employee accounts; Google sign-ins and the real admin are never demo users. */
  public boolean isDemoEmployee(String username) {
    return username != null && employeePattern.matcher(username).matches();
  }

  public String adminUsername() {
    return adminUsername;
  }

  public String adminPassword() {
    return adminPassword;
  }

  public String employeePassword() {
    return employeePassword;
  }
}
