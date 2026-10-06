package com.travelrisk.platform.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Readable name and email for an account key, such as the numeric id Google sign-in uses. */
@Entity
@Table(name = "user_profiles")
public class UserProfile {
  @Id
  private String username;

  private String email;

  @Column(name = "display_name")
  private String displayName;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected UserProfile() {}

  public UserProfile(String username) {
    this.username = username;
  }

  public void update(String email, String displayName) {
    this.email = email;
    this.displayName = displayName;
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
}
