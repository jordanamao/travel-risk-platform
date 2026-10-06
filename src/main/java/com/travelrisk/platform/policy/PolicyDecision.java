package com.travelrisk.platform.policy;

import java.util.List;

/**
 * The result of checking one trip against the company travel policy.
 *
 * @param outcome machine-readable result: allowed, warning, approval_required or blocked
 * @param label short text for a badge, e.g. "Needs approval"
 * @param reasons every rule that matched, strictest first
 */
public record PolicyDecision(String outcome, String label, List<Reason> reasons) {

  public record Reason(String ruleId, String rule, String action, String message) {}
}
