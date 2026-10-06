package com.travelrisk.platform.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;

/**
 * A company's travel policy, loaded from a YAML file (see {@code travel-policy.yml}).
 * Each rule says "when a trip looks like this, take this action"; the strictest matching action wins.
 */
public record TravelPolicy(String company, String updated, List<Rule> rules) {

  public TravelPolicy {
    rules = rules == null ? List.of() : List.copyOf(rules);
  }

  public record Rule(String id, String name, Action action, Condition when, String message) {

    public Rule {
      when = when == null ? new Condition(null, null, null, null, null) : when;
    }
  }

  /** Every condition that is set must match. A rule with no conditions matches every trip. */
  public record Condition(
      String minRiskLevel, List<String> riskLevels, Integer minPoints, List<String> modes, List<String> signalTypes) {

    public Condition {
      riskLevels = riskLevels == null ? List.of() : List.copyOf(riskLevels);
      modes = modes == null ? List.of() : modes.stream().map(mode -> mode.trim().toLowerCase(Locale.ROOT)).toList();
      signalTypes = signalTypes == null ? List.of() : List.copyOf(signalTypes);
    }
  }

  /** Ordered from least to most strict. */
  public enum Action {
    ALLOW("allow", "allowed", "Allowed"),
    WARN("warn", "warning", "Heads-up"),
    REQUIRE_APPROVAL("require_approval", "approval_required", "Needs approval"),
    BLOCK("block", "blocked", "Blocked");

    private final String key;
    private final String outcome;
    private final String label;

    Action(String key, String outcome, String label) {
      this.key = key;
      this.outcome = outcome;
      this.label = label;
    }

    @JsonValue
    public String key() {
      return key;
    }

    public String outcome() {
      return outcome;
    }

    public String label() {
      return label;
    }

    @JsonCreator
    public static Action fromKey(String value) {
      String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
      for (Action action : values()) {
        if (action.key.equals(normalized)) {
          return action;
        }
      }
      throw new IllegalArgumentException(
          "Unknown policy action '" + value + "'. Use allow, warn, require_approval or block.");
    }
  }
}
