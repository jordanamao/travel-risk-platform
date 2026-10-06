// Company travel policy: the rule list in "Your trips", badges on trips, and the result on an open assessment.

let policyRequestId = 0;

function setupTravelPolicy() {
  loadTravelPolicy();
}

async function loadTravelPolicy() {
  const list = document.querySelector("#policy-rules");
  const company = document.querySelector("#policy-company");
  if (!list) return;

  try {
    const response = await fetch("/api/policy");
    const policy = await readJsonResponse(response, "Unable to load the travel policy");
    if (!response.ok) throw new Error(policy.error || "Unable to load the travel policy");
    if (company) company.textContent = policy.company || "";
    list.innerHTML = "";
    const rules = policy.rules || [];
    if (!rules.length) {
      list.innerHTML = `<li class="field-note">No policy rules are set. Every trip is allowed.</li>`;
      return;
    }
    for (const rule of rules) {
      const item = document.createElement("li");
      item.innerHTML = `<span></span><div><strong></strong><p></p></div>`;
      item.querySelector("span").replaceWith(policyBadge({ outcome: actionOutcome(rule.action), label: actionLabel(rule.action) }));
      item.querySelector("strong").textContent = rule.name || rule.id;
      item.querySelector("p").textContent = rule.message || "";
      list.appendChild(item);
    }
  } catch (error) {
    list.innerHTML = `<li class="error-inline"></li>`;
    list.querySelector("li").textContent = error.message;
  }
}

function actionOutcome(action) {
  return {
    allow: "allowed",
    warn: "warning",
    require_approval: "approval_required",
    block: "blocked"
  }[action] || "allowed";
}

function actionLabel(action) {
  return {
    allow: "Allowed",
    warn: "Heads-up",
    require_approval: "Needs approval",
    block: "Blocked"
  }[action] || action;
}

function policyBadge(policy) {
  const badge = document.createElement("span");
  const outcome = policy?.outcome || "allowed";
  badge.className = `policy-badge ${outcome}`;
  badge.textContent = policy?.label || "Allowed";
  const reasons = (policy?.reasons || []).map((reason) => reason.message || reason.rule).filter(Boolean);
  if (reasons.length) badge.title = reasons.join("\n");
  return badge;
}

// Shows the policy decision for the assessment on screen. Older responses are ignored if a newer one was requested.
async function renderPolicyResult(assessment) {
  const container = document.querySelector("#policy-result");
  if (!container) return;
  const requestId = ++policyRequestId;
  container.classList.add("hidden");

  try {
    const response = await fetch("/api/policy/evaluate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ assessment })
    });
    const policy = await readJsonResponse(response, "Unable to check the travel policy");
    if (!response.ok) throw new Error(policy.error || "Unable to check the travel policy");
    if (requestId !== policyRequestId) return;

    container.className = `policy-result ${policy.outcome}`;
    container.innerHTML = `<p class="policy-result-label">Company policy</p><div class="policy-result-body"><span></span><ul></ul></div>`;
    container.querySelector("span").replaceWith(policyBadge(policy));
    const list = container.querySelector("ul");
    const reasons = policy.reasons || [];
    if (!reasons.length) {
      const item = document.createElement("li");
      item.textContent = "No policy rules apply to this trip.";
      list.appendChild(item);
    }
    for (const reason of reasons) {
      const item = document.createElement("li");
      item.textContent = reason.message || reason.rule;
      list.appendChild(item);
    }
  } catch {
    if (requestId === policyRequestId) container.classList.add("hidden");
  }
}
