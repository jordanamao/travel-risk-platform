package com.travelrisk.platform.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One alert waiting to go out on one channel (email or Slack). Written in the same transaction as
 * the trip notification it announces, so the alert exists if and only if the notification does.
 */
@Entity
@Table(name = "alert_outbox")
public class AlertOutboxEntry {
  public enum Status { PENDING, SENDING, SENT, FAILED }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Makes enqueueing idempotent: the same change on the same channel is only ever queued once. */
  @Column(name = "dedupe_key", nullable = false, unique = true)
  private String dedupeKey;

  @Column(name = "notification_id", nullable = false)
  private Long notificationId;

  @Column(length = 40, nullable = false)
  private String channel;

  @Column(length = 4000, nullable = false)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(length = 20, nullable = false)
  private Status status;

  @Column(nullable = false)
  private int attempts;

  /** When a PENDING row is due, or when a SENDING row's lease runs out and another worker may take it. */
  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "sent_at")
  private Instant sentAt;

  protected AlertOutboxEntry() {}

  public AlertOutboxEntry(String dedupeKey, Long notificationId, String channel, String payload, Instant now) {
    this.dedupeKey = dedupeKey;
    this.notificationId = notificationId;
    this.channel = channel;
    this.payload = payload;
    this.status = Status.PENDING;
    this.attempts = 0;
    this.nextAttemptAt = now;
    this.createdAt = now;
  }

  public void markSent(Instant now) {
    this.status = Status.SENT;
    this.attempts++;
    this.sentAt = now;
    this.lastError = null;
  }

  /** Records a failed send: tries again at {@code retryAt}, or gives up when {@code retryAt} is null. */
  public void markFailed(String error, Instant retryAt) {
    this.attempts++;
    this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    if (retryAt == null) {
      this.status = Status.FAILED;
    } else {
      this.status = Status.PENDING;
      this.nextAttemptAt = retryAt;
    }
  }

  public Long getId() { return id; }
  public String getDedupeKey() { return dedupeKey; }
  public Long getNotificationId() { return notificationId; }
  public String getChannel() { return channel; }
  public String getPayload() { return payload; }
  public Status getStatus() { return status; }
  public int getAttempts() { return attempts; }
  public Instant getNextAttemptAt() { return nextAttemptAt; }
  public String getLastError() { return lastError; }
  public Instant getSentAt() { return sentAt; }
}
