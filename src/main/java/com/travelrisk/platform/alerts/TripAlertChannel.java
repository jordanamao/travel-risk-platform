package com.travelrisk.platform.alerts;

/** Somewhere outside the app a risk-change alert can go. */
public interface TripAlertChannel {
  /** Short name shown to the user, e.g. "email". */
  String name();

  /** True when the channel is configured at all. */
  boolean configured();

  /** True when this alert can go out on this channel (configured, and has somewhere to send it). */
  default boolean accepts(TripAlert alert) {
    return configured();
  }

  void send(TripAlert alert);
}
