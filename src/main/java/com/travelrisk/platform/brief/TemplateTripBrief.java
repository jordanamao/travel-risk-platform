package com.travelrisk.platform.brief;

import com.travelrisk.platform.cost.DisruptionCostEstimate;
import com.travelrisk.platform.policy.PolicyDecision;
import com.travelrisk.platform.service.TravelRiskService;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/** The built-in brief: the same facts in fixed wording, used when Claude is not set up or does not answer. */
final class TemplateTripBrief {
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

  private TemplateTripBrief() {}

  static String write(TripBriefFacts facts) {
    TravelRiskService.Assessment assessment = facts.assessment();
    TravelRiskService.Input input = assessment.input();
    String level = assessment.score().level();
    List<String> sentences = new ArrayList<>();

    List<TravelRiskService.Signal> drivers = facts.drivers();
    String trip = tripNoun(input.mode()) + " from " + input.origin() + " to " + input.destination() + " on " + day(input.date());
    sentences.add(drivers.isEmpty()
        ? "Your " + trip + " is " + level + " risk, with no major warning signs in the current data."
        : "Your " + trip + " is " + level + " risk. Main concern: " + drivers.getFirst().message());

    PolicyDecision policy = facts.policy();
    if (policy != null && !policy.reasons().isEmpty()) {
      sentences.add("Company policy: " + policy.label() + ". " + policy.reasons().getFirst().message());
    }

    DisruptionCostEstimate cost = facts.cost();
    if (cost != null && cost.expected() > 0 && !"Low".equals(level)) {
      sentences.add("Going ahead unchanged has an expected disruption cost of about " + money(cost.expected(), cost.currency())
          + " (" + money(cost.ifDisrupted(), cost.currency()) + " if it is disrupted).");
    }

    TravelRiskService.Alternative best = facts.bestAlternative();
    if (best != null) {
      sentences.add("Best safer option: " + lowerFirst(best.title()) + " (" + best.when() + "), which brings it down to "
          + best.score().level() + ".");
    } else if (facts.alternatives() != null && facts.alternatives().checked() > 0) {
      sentences.add("No other day, time or airport scored better, so build in extra time instead.");
    } else if ("Low".equals(level)) {
      sentences.add("No change is needed; recheck conditions before you leave.");
    }

    if (!facts.unavailableSources().isEmpty()) {
      sentences.add("Some data sources did not respond, so treat this as an incomplete picture.");
    }
    return String.join(" ", sentences.stream().map(TemplateTripBrief::endWithPeriod).toList());
  }

  private static String tripNoun(String mode) {
    return switch (mode == null ? "" : mode) {
      case "flight" -> "flight";
      case "drive" -> "drive";
      default -> "trip";
    };
  }

  private static String day(String date) {
    try {
      return LocalDate.parse(date).format(DAY);
    } catch (DateTimeParseException error) {
      return date;
    }
  }

  static String money(int amount, String currency) {
    NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
    try {
      format.setCurrency(Currency.getInstance(currency));
    } catch (IllegalArgumentException | NullPointerException error) {
      return amount + " " + (currency == null ? "" : currency).trim();
    }
    format.setMaximumFractionDigits(0);
    return format.format(amount);
  }

  // Option titles read as instructions ("Leave a day later"), so they go mid-sentence in lower case.
  private static String lowerFirst(String text) {
    if (text == null || text.isEmpty()) return "";
    return Character.toLowerCase(text.charAt(0)) + text.substring(1);
  }

  private static String endWithPeriod(String sentence) {
    String trimmed = sentence.trim();
    return trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?") ? trimmed : trimmed + ".";
  }
}
