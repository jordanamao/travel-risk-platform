package com.travelrisk.platform.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelrisk.platform.TestAssessments;
import com.travelrisk.platform.database.entities.SavedTrip;
import com.travelrisk.platform.database.entities.TripNotification;
import com.travelrisk.platform.database.entities.UserProfile;
import com.travelrisk.platform.repository.UserProfileRepository;
import com.travelrisk.platform.service.TravelRiskService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class TripAlertServiceTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final UserProfileRepository profiles = mock(UserProfileRepository.class);
  private final List<String[]> sentEmails = new ArrayList<>();
  private final List<TripAlert> slackAlerts = new ArrayList<>();
  private final MutableClock clock = new MutableClock();

  private EmailTripAlertChannel email;
  private TripAlertChannel slack;

  @BeforeEach
  void setUp() {
    email = new EmailTripAlertChannel(
        (from, to, subject, html, text) -> sentEmails.add(new String[] {from, to, subject, html, text}),
        "Travel Risk <alerts@example.test>");
    slack = new TripAlertChannel() {
      public String name() { return "Slack"; }
      public boolean configured() { return true; }
      public void send(TripAlert alert) { slackAlerts.add(alert); }
    };
  }

  @Test
  void levelChangeEmailsTheOverrideAddressWithTheMainReason() throws Exception {
    TripAlertService service = service(List.of(email), "me@example.test");

    service.onRiskChange(notification("Medium", 5, "High", 12), trip("High", 12));

    assertThat(sentEmails).hasSize(1);
    String[] mail = sentEmails.get(0);
    assertThat(mail[1]).isEqualTo("me@example.test");
    assertThat(mail[2]).isEqualTo("Risk up: Dallas, TX to Orlando, FL is now High");
    assertThat(mail[3])
        .contains("Dallas, TX to Orlando, FL")
        .contains("Medium").contains("High")
        .contains("Ground stop at MCO.")
        .contains("href=\"https://travel-risk.example.test\"");
    assertThat(mail[4]).contains("Risk changed from Medium (5 pts) to High (12 pts).");
  }

  @Test
  void pointsMovingInsideTheSameLevelStaysInTheApp() throws Exception {
    TripAlertService service = service(List.of(email, slack), "me@example.test");

    service.onRiskChange(notification("Medium", 5, "Medium", 7), trip("Medium", 7));

    assertThat(sentEmails).isEmpty();
    assertThat(slackAlerts).isEmpty();
  }

  @Test
  void withoutOverrideEmailGoesToTheSignedInAddressAndAccountsWithoutOneGetNoMail() throws Exception {
    TripAlertService service = service(List.of(email, slack), "");
    UserProfile profile = new UserProfile("google-123");
    profile.update("traveler@example.test", "Traveler");
    when(profiles.findById("google-123")).thenReturn(Optional.of(profile));
    when(profiles.findById("employee1@email.com")).thenReturn(Optional.empty());

    service.onRiskChange(notification("google-123", "Low", 1, "High", 12), trip("High", 12));
    service.onRiskChange(notification("employee1@email.com", "Low", 1, "Medium", 5), trip("Medium", 5));

    assertThat(sentEmails).extracting(mail -> mail[1]).containsExactly("traveler@example.test");
    assertThat(slackAlerts).hasSize(2);
  }

  @Test
  void resendNeedsAChannelAndIsRateLimitedPerAlert() throws Exception {
    TripNotification notification = notification("Medium", 5, "High", 12);
    ReflectionTestUtils.setField(notification, "id", 7L);

    assertThatThrownBy(() -> service(List.of(), "me@example.test").resend(notification, trip("High", 12)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    TripAlertService service = service(List.of(email, slack), "me@example.test");
    assertThat(service.resend(notification, trip("High", 12))).containsExactly("email", "Slack");
    assertThatThrownBy(() -> service.resend(notification, trip("High", 12)))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

    clock.advance(TripAlertService.RESEND_COOLDOWN.plusSeconds(1));
    service.resend(notification, trip("High", 12));
    assertThat(sentEmails).hasSize(2);
  }

  @Test
  void aFailingChannelDoesNotStopTheOthers() throws Exception {
    TripAlertChannel broken = new TripAlertChannel() {
      public String name() { return "email"; }
      public boolean configured() { return true; }
      public void send(TripAlert alert) { throw new IllegalStateException("SMTP down"); }
    };

    service(List.of(broken, slack), "me@example.test")
        .onRiskChange(notification("Medium", 5, "High", 12), trip("High", 12));

    assertThat(slackAlerts).hasSize(1);
  }

  @Test
  void slackMessageHasTheChangeReasonAndAButton() throws Exception {
    service(List.of(slack), "").onRiskChange(notification("High", 12, "Low", 1), trip("Low", 1));

    Map<String, Object> message = SlackTripAlertChannel.message(slackAlerts.get(0));
    assertThat(message.get("text")).isEqualTo("Risk down: Dallas, TX to Orlando, FL is now Low");
    assertThat(message.toString())
        .contains("High (12 pts) to Low (1 pts)")
        .contains("Open in Travel Risk")
        .contains("https://travel-risk.example.test");
  }

  @Test
  void profileSettingsChooseTheChannelsAndWhichLevelChangesGoOut() throws Exception {
    TripAlertService service = service(List.of(email, slack), "me@example.test");
    UserProfile profile = new UserProfile("employee1@email.com");
    profile.updateSettings(null, null, "balanced", List.of(), false, true, "high");
    when(profiles.findById("employee1@email.com")).thenReturn(Optional.of(profile));

    // Only High counts for this employee, so Low to Medium stays in the app.
    service.onRiskChange(notification("Low", 1, "Medium", 5), trip("Medium", 5));
    assertThat(slackAlerts).isEmpty();

    // Medium to High goes out, but only to Slack: email is switched off.
    service.onRiskChange(notification("Medium", 5, "High", 12), trip("High", 12));
    assertThat(slackAlerts).hasSize(1);
    assertThat(sentEmails).isEmpty();
    assertThat(service.channelsFor("employee1@email.com")).containsExactly("Slack");
  }

  @Test
  void resendExplainsWhenTheProfileTurnedEveryChannelOff() throws Exception {
    UserProfile profile = new UserProfile("employee1@email.com");
    profile.updateSettings(null, null, "balanced", List.of(), false, false, "any");
    when(profiles.findById("employee1@email.com")).thenReturn(Optional.of(profile));
    TripNotification notification = notification("Medium", 5, "High", 12);
    ReflectionTestUtils.setField(notification, "id", 8L);

    assertThatThrownBy(() -> service(List.of(email, slack), "me@example.test").resend(notification, trip("High", 12)))
        .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
          assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(error.getReason()).contains("turned off in your profile");
        });
  }

  @Test
  void emailEscapesTripText() {
    TripAlert alert = new TripAlert("u", "<b>Dallas</b>", "Orlando", java.time.LocalDate.parse("2026-10-09"), "flight",
        "Medium", "High", 5, 12, "Storm & <script>", "", "me@example.test");

    String html = TripAlertEmail.html(alert);

    assertThat(html).doesNotContain("<b>Dallas</b>").doesNotContain("<script>").contains("&lt;b&gt;Dallas");
    assertThat(html).doesNotContain("Open the trip");
  }

  private TripAlertService service(List<TripAlertChannel> channels, String override) {
    return new TripAlertService(channels, profiles, objectMapper, "https://travel-risk.example.test/", override,
        Runnable::run, clock);
  }

  private TripNotification notification(String fromLevel, int fromPoints, String toLevel, int toPoints) throws Exception {
    return notification("employee1@email.com", fromLevel, fromPoints, toLevel, toPoints);
  }

  private TripNotification notification(String username, String fromLevel, int fromPoints, String toLevel, int toPoints)
      throws Exception {
    SavedTrip before = savedTrip(username, fromLevel, fromPoints);
    return new TripNotification(before, assessment(toLevel, toPoints));
  }

  private SavedTrip trip(String level, int points) throws Exception {
    return savedTrip("employee1@email.com", level, points);
  }

  private SavedTrip savedTrip(String username, String level, int points) throws Exception {
    TravelRiskService.Assessment assessment = assessment(level, points);
    SavedTrip trip = new SavedTrip(username, assessment, objectMapper.writeValueAsString(assessment));
    ReflectionTestUtils.setField(trip, "id", 3L);
    return trip;
  }

  private static TravelRiskService.Assessment assessment(String level, int points) {
    TravelRiskService.Assessment base =
        TestAssessments.assessment("Dallas, TX", "Orlando, FL", "2026-10-09", "flight", level, points);
    TravelRiskService.Signal minor = new TravelRiskService.Signal("weather", "low", "Light rain.", "forecast");
    minor.points(1);
    TravelRiskService.Signal major = new TravelRiskService.Signal("faa-airport-status", "high", "Ground stop at MCO.", "FAA");
    major.points(8);
    return new TravelRiskService.Assessment(base.input(), base.route(), base.score(), base.summary(),
        base.recommendation(), base.uncertainty(), base.ai(), List.of(minor, major), List.of(), List.of(), List.of());
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-06T12:00:00Z");

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
