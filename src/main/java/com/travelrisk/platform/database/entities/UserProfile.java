package com.travelrisk.platform.database.entities;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One employee's profile: who they are (name and email from Google sign-in), their travel
 * preferences, the routes they travel often, how much risk they are comfortable with, and where
 * risk-change alerts should reach them.
 */
@Entity
@Table(name = "user_profiles")
public class UserProfile {
  @Id
  private String username;

  private String email;

  @Column(name = "display_name")
  private String displayName;

  @Column(name = "home_city")
  private String homeCity;

  @Column(name = "preferred_mode", length = 32)
  private String preferredMode;

  @Column(name = "risk_tolerance", nullable = false, length = 32)
  private String riskTolerance = "balanced";

  @Column(name = "alert_email", nullable = false)
  private boolean alertEmail = true;

  @Column(name = "alert_slack", nullable = false)
  private boolean alertSlack = true;

  @Column(name = "alert_min_level", nullable = false, length = 32)
  private String alertMinLevel = "any";

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "user_frequent_routes", joinColumns = @JoinColumn(name = "username"))
  @OrderColumn(name = "route_order")
  private List<FrequentRoute> frequentRoutes = new ArrayList<>();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UserProfile() {}

  public UserProfile(String username) {
    this.username = username;
    this.updatedAt = Instant.now();
  }

  /** Name and email from sign-in. Leaves the employee's own settings alone. */
  public void update(String email, String displayName) {
    this.email = email;
    this.displayName = displayName;
    this.updatedAt = Instant.now();
  }

  public void updateSettings(String homeCity, String preferredMode, String riskTolerance,
      List<FrequentRoute> frequentRoutes, boolean alertEmail, boolean alertSlack, String alertMinLevel) {
    this.homeCity = homeCity;
    this.preferredMode = preferredMode;
    this.riskTolerance = riskTolerance;
    this.frequentRoutes.clear();
    this.frequentRoutes.addAll(frequentRoutes);
    this.alertEmail = alertEmail;
    this.alertSlack = alertSlack;
    this.alertMinLevel = alertMinLevel;
    this.updatedAt = Instant.now();
  }

  public String getUsername() {
    return username;
  }

  public String getEmail() {
    return email;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getHomeCity() {
    return homeCity;
  }

  public String getPreferredMode() {
    return preferredMode;
  }

  public String getRiskTolerance() {
    return riskTolerance;
  }

  public List<FrequentRoute> getFrequentRoutes() {
    return List.copyOf(frequentRoutes);
  }

  public boolean isAlertEmail() {
    return alertEmail;
  }

  public boolean isAlertSlack() {
    return alertSlack;
  }

  public String getAlertMinLevel() {
    return alertMinLevel;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
