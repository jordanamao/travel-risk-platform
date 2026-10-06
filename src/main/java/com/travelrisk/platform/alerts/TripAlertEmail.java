package com.travelrisk.platform.alerts;

import org.springframework.web.util.HtmlUtils;

/** Renders a risk-change alert as an email. Inline styles and tables, since mail clients ignore stylesheets. */
final class TripAlertEmail {
  private TripAlertEmail() {}

  static String html(TripAlert alert) {
    String accent = color(alert.currentLevel());
    String verb = alert.escalated() ? "went up" : "went down";
    String reason = alert.reason() == null || alert.reason().isBlank()
        ? ""
        : """
          <tr><td style="padding:0 28px 22px;">
            <div style="font-size:12px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:#64748b;">Main reason</div>
            <div style="margin-top:6px;font-size:15px;line-height:1.5;color:#0f172a;">%s</div>
          </td></tr>
          """.formatted(escape(alert.reason()));
    String button = alert.appUrl() == null || alert.appUrl().isBlank()
        ? ""
        : """
          <tr><td style="padding:0 28px 28px;">
            <a href="%s" style="display:inline-block;padding:12px 20px;border-radius:8px;background:#2563eb;color:#ffffff;font-size:14px;font-weight:700;text-decoration:none;">Open the trip in Travel Risk</a>
          </td></tr>
          """.formatted(escape(alert.appUrl()));

    return """
        <!doctype html>
        <html><body style="margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f1f5f9;padding:28px 12px;">
        <tr><td align="center">
        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;">
          <tr><td style="height:6px;background:%s;"></td></tr>
          <tr><td style="padding:24px 28px 6px;font-size:13px;font-weight:700;color:#64748b;">Travel Risk alert</td></tr>
          <tr><td style="padding:0 28px 4px;font-size:22px;font-weight:800;line-height:1.3;color:#0f172a;">%s</td></tr>
          <tr><td style="padding:0 28px 20px;font-size:14px;color:#475569;">%s · %s</td></tr>
          <tr><td style="padding:0 28px 22px;">
            <table role="presentation" cellpadding="0" cellspacing="0"><tr>
              <td>%s</td>
              <td style="padding:0 12px;font-size:20px;color:#94a3b8;">&rarr;</td>
              <td>%s</td>
            </tr></table>
            <div style="margin-top:10px;font-size:14px;color:#475569;">Risk %s since the last check.</div>
          </td></tr>
          %s
          %s
        </table>
        <div style="max-width:560px;padding:14px 4px 0;font-size:12px;line-height:1.5;color:#94a3b8;">
          You get this because the trip is saved in Travel Risk and its risk level changed.
        </div>
        </td></tr>
        </table>
        </body></html>
        """.formatted(
            accent,
            escape(alert.route()),
            escape(alert.friendlyDate()),
            escape(alert.tripType()),
            pill(alert.previousLevel(), alert.previousPoints(), false),
            pill(alert.currentLevel(), alert.currentPoints(), true),
            verb,
            reason,
            button);
  }

  static String text(TripAlert alert) {
    StringBuilder text = new StringBuilder()
        .append(alert.route()).append(" (").append(alert.friendlyDate()).append(", ").append(alert.tripType()).append(")\n")
        .append("Risk changed from ").append(alert.change()).append(".\n");
    if (alert.reason() != null && !alert.reason().isBlank()) {
      text.append("Main reason: ").append(alert.reason()).append("\n");
    }
    if (alert.appUrl() != null && !alert.appUrl().isBlank()) {
      text.append("\nOpen the trip: ").append(alert.appUrl()).append("\n");
    }
    return text.toString();
  }

  private static String pill(String level, Integer points, boolean current) {
    String color = color(level);
    String style = current
        ? "background:%s;color:#ffffff;border:1px solid %s;".formatted(color, color)
        : "background:#ffffff;color:%s;border:1px solid %s;".formatted(color, color);
    return """
        <span style="display:inline-block;padding:8px 14px;border-radius:999px;font-size:15px;font-weight:800;%s">%s <span style="font-weight:600;opacity:.85;">%d pts</span></span>"""
        .formatted(style, escape(level), points == null ? 0 : points);
  }

  private static String color(String level) {
    return switch (level == null ? "" : level) {
      case "High" -> "#dc2626";
      case "Medium" -> "#d97706";
      case "Low" -> "#16a34a";
      default -> "#64748b";
    };
  }

  private static String escape(String value) {
    return HtmlUtils.htmlEscape(value == null ? "" : value);
  }
}
