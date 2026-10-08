package com.travelrisk.platform.brief;

import com.travelrisk.platform.cost.DisruptionCostEstimate;
import com.travelrisk.platform.monitoring.SourceCheck;
import com.travelrisk.platform.policy.PolicyDecision;
import com.travelrisk.platform.service.TravelRiskService;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the result page already shows for one trip, gathered for the brief: the check itself,
 * the company policy decision, the estimated cost of disruption and the safer alternatives.
 * {@code cost} and {@code alternatives} are null when they could not be worked out.
 */
public record TripBriefFacts(
    TravelRiskService.Assessment assessment,
    PolicyDecision policy,
    DisruptionCostEstimate cost,
    TravelRiskService.Alternatives alternatives) {

  /** Signals that added points, highest first. */
  List<TravelRiskService.Signal> drivers() {
    if (assessment.signals() == null) return List.of();
    return assessment.signals().stream()
        .filter(signal -> signal.points() > 0)
        .sorted(Comparator.comparingInt((TravelRiskService.Signal signal) -> signal.points()).reversed())
        .toList();
  }

  /** The option that saves the most points, or null when there is none. */
  TravelRiskService.Alternative bestAlternative() {
    if (alternatives == null || alternatives.options() == null || alternatives.options().isEmpty()) return null;
    return alternatives.options().getFirst();
  }

  List<String> unavailableSources() {
    if (assessment.dataSources() == null) return List.of();
    return assessment.dataSources().stream().filter(SourceCheck::unavailable).map(SourceCheck::name).toList();
  }

  /** The facts as plain data for the model: only what the page shows, nothing it would have to guess. */
  Map<String, Object> toPromptData() {
    TravelRiskService.Input input = assessment.input();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("trip", ordered("from", input.origin(), "to", input.destination(), "date", input.date(), "mode", input.mode()));
    data.put("risk", ordered("level", assessment.score().level(), "points", assessment.score().points(),
        "confidence", assessment.score().confidence()));
    data.put("drivers", drivers().stream().limit(4)
        .map(signal -> ordered("type", signal.type(), "severity", signal.severity(), "points", signal.points(),
            "what", signal.message()))
        .toList());
    data.put("unavailableSources", unavailableSources());
    if (policy != null) {
      data.put("companyPolicy", ordered("result", policy.label(),
          "rules", policy.reasons().stream().map(PolicyDecision.Reason::message).toList()));
    }
    if (cost != null) {
      data.put("costOfDoingNothing", ordered("currency", cost.currency(), "expected", cost.expected(),
          "ifDisrupted", cost.ifDisrupted(), "assumedChanceOfDisruption", cost.likelihood()));
    }
    if (alternatives != null) {
      data.put("saferOptions", ordered("checked", alternatives.checked(),
          "options", alternatives.options().stream().limit(3)
              .map(option -> ordered("change", option.title(), "when", option.when(), "newLevel", option.score().level(),
                  "pointsSaved", option.pointsSaved()))
              .toList()));
    }
    return data;
  }

  private static Map<String, Object> ordered(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }
}
