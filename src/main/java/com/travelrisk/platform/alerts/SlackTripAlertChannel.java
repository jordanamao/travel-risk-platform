package com.travelrisk.platform.alerts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Posts risk-change alerts to a Slack channel through an incoming webhook
 * ({@code TRIP_ALERT_SLACK_WEBHOOK_URL}). Off when the URL is not set.
 */
@Component
public class SlackTripAlertChannel implements TripAlertChannel {
  private final RestClient restClient;
  private final String webhookUrl;

  public SlackTripAlertChannel(
      RestClient.Builder restClientBuilder,
      @Value("${travel-risk.trip-alerts.slack-webhook-url:}") String webhookUrl) {
    this.restClient = restClientBuilder.build();
    this.webhookUrl = webhookUrl.trim();
  }

  @Override
  public String name() {
    return "Slack";
  }

  @Override
  public boolean configured() {
    return !webhookUrl.isBlank();
  }

  @Override
  public void send(TripAlert alert) {
    restClient.post()
        .uri(webhookUrl)
        .contentType(MediaType.APPLICATION_JSON)
        .body(message(alert))
        .retrieve()
        .toBodilessEntity();
  }

  static Map<String, Object> message(TripAlert alert) {
    String icon = switch (alert.currentLevel()) {
      case "High" -> ":red_circle:";
      case "Medium" -> ":large_orange_circle:";
      default -> ":large_green_circle:";
    };
    StringBuilder body = new StringBuilder()
        .append(icon).append(" *").append(slackEscape(alert.route())).append("*\n")
        .append(slackEscape(alert.friendlyDate())).append(" · ").append(alert.tripType()).append("\n")
        .append("Risk went from *").append(slackEscape(alert.change())).append("*");
    if (alert.reason() != null && !alert.reason().isBlank()) {
      body.append("\n>").append(slackEscape(alert.reason()));
    }

    List<Map<String, Object>> blocks = new ArrayList<>();
    blocks.add(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", body.toString())));
    if (alert.appUrl() != null && !alert.appUrl().isBlank()) {
      blocks.add(Map.of("type", "actions", "elements", List.of(Map.of(
          "type", "button",
          "text", Map.of("type", "plain_text", "text", "Open in Travel Risk"),
          "url", alert.appUrl()))));
    }
    // "text" is the notification preview and the fallback for clients that can't show blocks.
    return Map.of("text", alert.subject(), "blocks", blocks);
  }

  private static String slackEscape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
