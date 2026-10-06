package com.travelrisk.platform.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.travelrisk.platform.service.TravelRiskService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Loads the company travel policy file once at startup and checks trips against it.
 * Decisions are computed when a trip is read, so a policy change applies to every saved trip on restart.
 */
@Service
public class TravelPolicyService {
  private static final Logger log = LoggerFactory.getLogger(TravelPolicyService.class);
  private static final List<String> RISK_LEVELS = List.of("Low", "Medium", "High");
  private static final Set<String> MODES = Set.of("flight", "drive", "general");

  private final TravelPolicy policy;

  @Autowired
  public TravelPolicyService(
      @Value("${travel-risk.policy.location:classpath:travel-policy.yml}") String location,
      ResourceLoader resourceLoader) {
    this(load(resourceLoader.getResource(location), location));
    log.info("Loaded travel policy for {} with {} rules from {}", policy.company(), policy.rules().size(), location);
  }

  public TravelPolicyService(TravelPolicy policy) {
    validate(policy);
    this.policy = policy;
  }

  public TravelPolicy policy() {
    return policy;
  }

  public PolicyDecision evaluate(TravelRiskService.Assessment assessment) {
    Set<String> signalTypes = assessment.signals() == null ? Set.of() : assessment.signals().stream()
        .map(TravelRiskService.Signal::type)
        .collect(Collectors.toSet());
    return evaluate(assessment.score().level(), assessment.score().points(), assessment.input().mode(), signalTypes);
  }

  /** Checks a stored assessment snapshot, as read back from JSON. */
  public PolicyDecision evaluate(String riskLevel, Integer points, String mode, Map<String, Object> assessmentSnapshot) {
    return evaluate(riskLevel, points, mode, signalTypes(assessmentSnapshot));
  }

  public PolicyDecision evaluate(String riskLevel, Integer points, String mode, Collection<String> signalTypes) {
    List<TravelPolicy.Rule> matched = policy.rules().stream()
        .filter(rule -> matches(rule.when(), riskLevel, points, mode, signalTypes))
        .sorted(Comparator.comparing((TravelPolicy.Rule rule) -> rule.action()).reversed())
        .toList();
    TravelPolicy.Action strictest = matched.isEmpty() ? TravelPolicy.Action.ALLOW : matched.get(0).action();
    List<PolicyDecision.Reason> reasons = matched.stream()
        .filter(rule -> rule.action() != TravelPolicy.Action.ALLOW)
        .map(rule -> new PolicyDecision.Reason(rule.id(), rule.name(), rule.action().key(), rule.message()))
        .toList();
    return new PolicyDecision(strictest.outcome(), strictest.label(), reasons);
  }

  private static boolean matches(TravelPolicy.Condition when, String riskLevel, Integer points, String mode,
      Collection<String> signalTypes) {
    if (when.minRiskLevel() != null && levelRank(riskLevel) < levelRank(when.minRiskLevel())) {
      return false;
    }
    if (!when.riskLevels().isEmpty() && when.riskLevels().stream().noneMatch(level -> level.equalsIgnoreCase(riskLevel))) {
      return false;
    }
    if (when.minPoints() != null && (points == null || points < when.minPoints())) {
      return false;
    }
    if (!when.modes().isEmpty() && (mode == null || !when.modes().contains(mode))) {
      return false;
    }
    return when.signalTypes().isEmpty() || when.signalTypes().stream().anyMatch(signalTypes::contains);
  }

  private static int levelRank(String level) {
    for (int index = 0; index < RISK_LEVELS.size(); index++) {
      if (RISK_LEVELS.get(index).equalsIgnoreCase(level)) {
        return index;
      }
    }
    return -1;
  }

  private static Set<String> signalTypes(Map<String, Object> assessmentSnapshot) {
    Set<String> types = new HashSet<>();
    if (assessmentSnapshot != null && assessmentSnapshot.get("signals") instanceof List<?> signals) {
      for (Object signal : signals) {
        if (signal instanceof Map<?, ?> fields && fields.get("type") != null) {
          types.add(String.valueOf(fields.get("type")));
        }
      }
    }
    return types;
  }

  private static TravelPolicy load(Resource resource, String location) {
    if (!resource.exists()) {
      throw new IllegalStateException("Travel policy file not found: " + location);
    }
    try (InputStream input = resource.getInputStream()) {
      return new ObjectMapper(new YAMLFactory()).readValue(input, TravelPolicy.class);
    } catch (IOException error) {
      throw new IllegalStateException("Travel policy file " + location + " is not valid: " + error.getMessage(), error);
    }
  }

  /** Fails startup with a readable message instead of silently ignoring a typo in the policy file. */
  private static void validate(TravelPolicy policy) {
    Set<String> ids = new HashSet<>();
    for (TravelPolicy.Rule rule : policy.rules()) {
      String label = rule.id() == null ? "(missing id)" : rule.id();
      if (rule.id() == null || rule.id().isBlank()) {
        throw invalid("every rule needs an id");
      }
      if (!ids.add(rule.id())) {
        throw invalid("rule id '" + rule.id() + "' is used more than once");
      }
      if (rule.action() == null) {
        throw invalid("rule '" + label + "' needs an action (allow, warn, require_approval or block)");
      }
      TravelPolicy.Condition when = rule.when();
      if (when.minRiskLevel() != null && levelRank(when.minRiskLevel()) < 0) {
        throw invalid("rule '" + label + "' has minRiskLevel '" + when.minRiskLevel() + "'; use Low, Medium or High");
      }
      for (String level : when.riskLevels()) {
        if (levelRank(level) < 0) {
          throw invalid("rule '" + label + "' has risk level '" + level + "'; use Low, Medium or High");
        }
      }
      if (when.minPoints() != null && when.minPoints() < 0) {
        throw invalid("rule '" + label + "' has a negative minPoints");
      }
      for (String mode : when.modes()) {
        if (!MODES.contains(mode)) {
          throw invalid("rule '" + label + "' has mode '" + mode + "'; use flight, drive or general");
        }
      }
    }
  }

  private static IllegalStateException invalid(String detail) {
    return new IllegalStateException("Travel policy is not valid: " + detail + ".");
  }
}
