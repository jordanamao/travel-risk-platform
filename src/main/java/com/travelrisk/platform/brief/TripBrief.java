package com.travelrisk.platform.brief;

/**
 * A few plain-language sentences on what one trip's check means for the traveler.
 *
 * @param text the brief itself
 * @param source "claude" when Claude wrote it, "template" when the built-in wording was used
 * @param model the Claude model that wrote it, or null for the template
 * @param note why the template was used, or null when Claude wrote it
 */
public record TripBrief(String text, String source, String model, String note) {
  public static final String CLAUDE = "claude";
  public static final String TEMPLATE = "template";

  static TripBrief fromClaude(String text, String model) {
    return new TripBrief(text, CLAUDE, model, null);
  }

  static TripBrief fromTemplate(String text, String note) {
    return new TripBrief(text, TEMPLATE, null, note);
  }
}
