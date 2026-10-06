package com.travelrisk.platform.alerts;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Emails risk-change alerts. Two ways to send, picked from the environment:
 * <ul>
 *   <li>{@code RESEND_API_KEY}: the Resend HTTPS API. Works on hosts that block SMTP ports,
 *       such as Render's free plan.</li>
 *   <li>{@code SMTP_HOST} (+ port, username, password): any SMTP server, e.g. Gmail with an app password.</li>
 * </ul>
 * With neither set the channel is off and nothing is ever sent.
 */
@Component
public class EmailTripAlertChannel implements TripAlertChannel {
  static final String RESEND_DEFAULT_FROM = "Travel Risk <onboarding@resend.dev>";

  private final Sender sender;
  private final String from;

  @Autowired
  public EmailTripAlertChannel(
      RestClient.Builder restClientBuilder,
      @Value("${travel-risk.trip-alerts.email.from:}") String from,
      @Value("${travel-risk.trip-alerts.email.resend-api-key:}") String resendApiKey,
      @Value("${travel-risk.trip-alerts.email.resend-url:https://api.resend.com/emails}") String resendUrl,
      @Value("${travel-risk.trip-alerts.email.smtp-host:}") String smtpHost,
      @Value("${travel-risk.trip-alerts.email.smtp-port:587}") int smtpPort,
      @Value("${travel-risk.trip-alerts.email.smtp-username:}") String smtpUsername,
      @Value("${travel-risk.trip-alerts.email.smtp-password:}") String smtpPassword) {
    if (!resendApiKey.isBlank()) {
      this.sender = resendSender(restClientBuilder.build(), resendUrl, resendApiKey.trim());
      this.from = from.isBlank() ? RESEND_DEFAULT_FROM : from.trim();
    } else if (!smtpHost.isBlank()) {
      this.sender = smtpSender(smtpHost.trim(), smtpPort, smtpUsername.trim(), smtpPassword);
      this.from = from.isBlank() ? smtpUsername.trim() : from.trim();
    } else {
      this.sender = null;
      this.from = "";
    }
  }

  EmailTripAlertChannel(Sender sender, String from) {
    this.sender = sender;
    this.from = from;
  }

  @Override
  public String name() {
    return "email";
  }

  @Override
  public boolean configured() {
    return sender != null && !from.isBlank();
  }

  @Override
  public boolean accepts(TripAlert alert) {
    return configured() && alert.recipientEmail() != null && !alert.recipientEmail().isBlank();
  }

  @Override
  public void send(TripAlert alert) {
    sender.send(from, alert.recipientEmail(), alert.subject(), TripAlertEmail.html(alert), TripAlertEmail.text(alert));
  }

  private static Sender resendSender(RestClient restClient, String url, String apiKey) {
    return (from, to, subject, html, text) -> restClient.post()
        .uri(url)
        .header("Authorization", "Bearer " + apiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("from", from, "to", List.of(to), "subject", subject, "html", html, "text", text))
        .retrieve()
        .toBodilessEntity();
  }

  private static Sender smtpSender(String host, int port, String username, String password) {
    JavaMailSenderImpl mail = new JavaMailSenderImpl();
    mail.setHost(host);
    mail.setPort(port);
    if (!username.isBlank()) {
      mail.setUsername(username);
      mail.setPassword(password);
    }
    Properties properties = mail.getJavaMailProperties();
    properties.put("mail.smtp.auth", String.valueOf(!username.isBlank()));
    properties.put("mail.smtp.starttls.enable", "true");
    properties.put("mail.smtp.ssl.enable", String.valueOf(port == 465));
    properties.put("mail.smtp.connectiontimeout", "5000");
    properties.put("mail.smtp.timeout", "10000");
    properties.put("mail.smtp.writetimeout", "10000");
    return (from, to, subject, html, text) -> {
      try {
        MimeMessage message = mail.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(text, html);
        mail.send(message);
      } catch (MessagingException error) {
        throw new IllegalStateException("Could not build alert email", error);
      }
    };
  }

  @FunctionalInterface
  interface Sender {
    void send(String from, String to, String subject, String html, String text);
  }
}
