package com.travelrisk.platform.alerts;

import java.util.List;

/** Where {@link TripAlertService} hands an alert once it has decided which channels it goes to. */
@FunctionalInterface
public interface TripAlertQueue {
  /** Why an alert is being queued: an automatic level change, or a person pressing "Send to email". */
  enum Kind { CHANGE, RESEND }

  void enqueue(Long notificationId, Kind kind, TripAlert alert, List<TripAlertChannel> targets);
}
