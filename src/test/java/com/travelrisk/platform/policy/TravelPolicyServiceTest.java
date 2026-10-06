package com.travelrisk.platform.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.travelrisk.platform.TestPolicies;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

class TravelPolicyServiceTest {
  private final TravelPolicyService service = TestPolicies.defaultPolicy();

  @Test
  void lowRiskTripIsAllowedWithNoReasons() {
    PolicyDecision decision = service.evaluate("Low", 2, "flight", Set.of("weather"));

    assertThat(decision.outcome()).isEqualTo("allowed");
    assertThat(decision.label()).isEqualTo("Allowed");
    assertThat(decision.reasons()).isEmpty();
  }

  @Test
  void highRiskTripNeedsApprovalAndListsEveryMatchingRuleStrictestFirst() {
    PolicyDecision decision = service.evaluate("High", 10, "flight", Set.of("faa-airport-status", "aviation-weather"));

    assertThat(decision.outcome()).isEqualTo("approval_required");
    assertThat(decision.reasons()).extracting(PolicyDecision.Reason::ruleId)
        .containsExactly("high-risk-approval", "airport-disruption");
  }

  @Test
  void tripAtTheBlockThresholdIsBlocked() {
    PolicyDecision decision = service.evaluate("High", 18, "drive", Set.of("official-alert", "road-closure"));

    assertThat(decision.outcome()).isEqualTo("blocked");
    assertThat(decision.reasons()).extracting(PolicyDecision.Reason::ruleId)
        .containsExactly("severe-disruption", "high-risk-approval", "driving-hazard");
  }

  @Test
  void modeConditionLimitsARuleToThatTripType() {
    assertThat(service.evaluate("Low", 1, "flight", Set.of("road-closure")).outcome()).isEqualTo("allowed");
    assertThat(service.evaluate("Low", 1, "drive", Set.of("road-closure")).outcome()).isEqualTo("warning");
  }

  @Test
  void unscoredTripDoesNotMatchRiskLevelRules() {
    assertThat(service.evaluate(null, null, "flight", List.of()).outcome()).isEqualTo("allowed");
  }

  @Test
  void rejectsUnknownAction() {
    assertThatThrownBy(() -> load("""
        rules:
          - id: x
            action: escalate
        """))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unknown policy action 'escalate'");
  }

  @Test
  void rejectsMisspelledCondition() {
    assertThatThrownBy(() -> load("""
        rules:
          - id: x
            action: warn
            when:
              minPoint: 4
        """))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("minPoint");
  }

  @Test
  void rejectsDuplicateIdsAndUnknownRiskLevels() {
    assertThatThrownBy(() -> load("""
        rules:
          - id: x
            action: warn
          - id: x
            action: block
        """))
        .hasMessage("Travel policy is not valid: rule id 'x' is used more than once.");
    assertThatThrownBy(() -> load("""
        rules:
          - id: x
            action: warn
            when:
              minRiskLevel: Severe
        """))
        .hasMessage("Travel policy is not valid: rule 'x' has minRiskLevel 'Severe'; use Low, Medium or High.");
  }

  @Test
  void customerPolicyFileReplacesTheDefault() {
    TravelPolicyService strict = load("""
        company: Strict Co
        rules:
          - id: any-medium
            name: Medium or worse
            action: require_approval
            when:
              minRiskLevel: Medium
        """);

    assertThat(strict.policy().company()).isEqualTo("Strict Co");
    assertThat(strict.evaluate("Medium", 5, "general", Set.of()).outcome()).isEqualTo("approval_required");
  }

  private static TravelPolicyService load(String yaml) {
    Resource resource = new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8));
    return new TravelPolicyService("inline", new DefaultResourceLoader() {
      @Override
      public Resource getResource(String location) {
        return resource;
      }
    });
  }
}
