// Estimated cost of disruption: the "cost of doing nothing" card on a result, and the money-at-risk rollup for admins.

let disruptionCostRequestId = 0;

function formatCostAmount(amount, currency) {
  try {
    return new Intl.NumberFormat("en-US", { style: "currency", currency: currency || "USD", maximumFractionDigits: 0 }).format(amount || 0);
  } catch {
    return `${amount || 0} ${currency || ""}`.trim();
  }
}

function formatLikelihood(value) {
  return `${Math.round((value || 0) * 100)}%`;
}

// Shows the cost card for the assessment on screen. Older responses are ignored if a newer one was requested.
async function renderDisruptionCost(assessment) {
  const container = document.querySelector("#disruption-cost");
  if (!container) return;
  const requestId = ++disruptionCostRequestId;
  container.classList.add("hidden");

  try {
    const params = new URLSearchParams({ riskLevel: assessment.score.level, mode: assessment.input.mode || "general" });
    const response = await fetch(`/api/disruption-cost?${params}`);
    const estimate = await readJsonResponse(response, "Unable to estimate the cost of disruption");
    if (!response.ok) throw new Error(estimate.error || "Unable to estimate the cost of disruption");
    if (requestId !== disruptionCostRequestId) return;

    const level = (estimate.riskLevel || "").toLowerCase();
    const money = (amount) => formatCostAmount(amount, estimate.currency);
    container.className = `disruption-cost-section ${level}`;
    container.innerHTML = `
      <div class="disruption-cost-headline">
        <div class="disruption-cost-title">
          <p class="eyebrow">Cost of doing nothing</p>
          <span class="estimate-tag">Estimate</span>
        </div>
        <p class="disruption-cost-amount"><span class="disruption-cost-approx">about</span> <strong></strong></p>
        <p class="disruption-cost-caption">expected cost if this trip goes ahead unchanged</p>
        <p class="disruption-cost-math"></p>
      </div>
      <div class="disruption-cost-breakdown">
        <p class="disruption-cost-breakdown-label">If the trip is disrupted</p>
        <ul></ul>
        <p class="disruption-cost-total"><span>Total if disrupted</span><strong></strong></p>
      </div>
      <p class="disruption-cost-note">Built from typical company costs, not a quote. Admins can change the amounts in the app settings.</p>
    `;
    container.querySelector(".disruption-cost-amount strong").textContent = money(estimate.expected);
    container.querySelector(".disruption-cost-math").textContent =
      `${formatLikelihood(estimate.likelihood)} assumed chance of disruption at ${estimate.riskLevel} risk × ${money(estimate.ifDisrupted)} if disrupted`;
    const list = container.querySelector(".disruption-cost-breakdown ul");
    for (const item of estimate.items || []) {
      const row = document.createElement("li");
      row.innerHTML = `<div><span></span><small></small></div><strong></strong>`;
      row.querySelector("span").textContent = item.label;
      row.querySelector("small").textContent = item.basis || "";
      row.querySelector("strong").textContent = money(item.amount);
      list.appendChild(row);
    }
    container.querySelector(".disruption-cost-total strong").textContent = money(estimate.ifDisrupted);
  } catch {
    if (requestId === disruptionCostRequestId) container.classList.add("hidden");
  }
}

function renderAdminCostAtRisk(rollup) {
  const container = document.querySelector("#admin-cost-at-risk");
  if (!container) return;
  if (!rollup || !rollup.trips) {
    container.innerHTML = `
      <div class="admin-cost-header"><h3>Money at risk</h3><span class="estimate-tag">Estimate</span></div>
      <p class="section-note">No upcoming saved trips to estimate yet.</p>`;
    return;
  }

  const money = (amount) => formatCostAmount(amount, rollup.currency);
  container.innerHTML = `
    <div class="admin-cost-header">
      <h3>Money at risk</h3>
      <span class="estimate-tag">Estimate</span>
    </div>
    <div class="admin-cost-grid">
      <div class="admin-cost-total">
        <strong></strong>
        <p></p>
        <small></small>
      </div>
      <div class="admin-cost-levels">
        <p class="admin-cost-label">By risk level</p>
        <ul></ul>
      </div>
      <div class="admin-cost-top">
        <p class="admin-cost-label">Biggest exposure</p>
        <ol></ol>
      </div>
    </div>
    <p class="admin-cost-note"></p>
  `;
  container.querySelector(".admin-cost-total strong").textContent = money(rollup.expected);
  container.querySelector(".admin-cost-total p").textContent =
    `expected disruption cost across ${rollup.trips} upcoming ${rollup.trips === 1 ? "trip" : "trips"} if nothing changes`;
  container.querySelector(".admin-cost-total small").textContent = `${money(rollup.ifDisrupted)} if every trip were disrupted`;
  const high = (rollup.byLevel || []).find((level) => level.riskLevel === "High");
  if (high && high.trips && high.expected) {
    const savings = document.createElement("p");
    savings.className = "admin-cost-savings";
    savings.textContent =
      `Delaying or rerouting the ${high.trips} High-risk ${high.trips === 1 ? "trip" : "trips"} avoids about ${money(high.expected)}.`;
    container.querySelector(".admin-cost-total").appendChild(savings);
  }

  const levels = container.querySelector(".admin-cost-levels ul");
  const largest = Math.max(1, ...(rollup.byLevel || []).map((level) => level.expected));
  for (const level of rollup.byLevel || []) {
    const row = document.createElement("li");
    row.className = level.riskLevel.toLowerCase();
    row.innerHTML = `<span class="admin-cost-level-name"></span><span class="admin-cost-bar"><span></span></span><strong></strong>`;
    row.querySelector(".admin-cost-level-name").textContent = `${level.riskLevel} · ${level.trips}`;
    row.querySelector(".admin-cost-level-name").title = `${level.trips} ${level.trips === 1 ? "trip" : "trips"} rated ${level.riskLevel}`;
    row.querySelector(".admin-cost-bar span").style.width = `${Math.round((level.expected / largest) * 100)}%`;
    row.querySelector("strong").textContent = money(level.expected);
    levels.appendChild(row);
  }

  const top = container.querySelector(".admin-cost-top ol");
  for (const trip of rollup.topTrips || []) {
    const row = document.createElement("li");
    row.innerHTML = `<div><strong></strong><span></span></div><b></b>`;
    row.querySelector("strong").textContent = `${trip.origin} to ${trip.destination}`;
    row.querySelector("span").textContent = `${trip.employee} · ${formatAdminTravelDate(trip.date)} · ${trip.estimate.riskLevel}`;
    row.querySelector("b").textContent = money(trip.estimate.expected);
    top.appendChild(row);
  }

  const likelihood = rollup.likelihood || {};
  container.querySelector(".admin-cost-note").textContent =
    `Each trip: assumed chance of disruption (Low ${formatLikelihood(likelihood.Low)}, Medium ${formatLikelihood(likelihood.Medium)}, ` +
    `High ${formatLikelihood(likelihood.High)}) × rebooking fee, an extra hotel night and lost working time. Amounts are company defaults, not quotes.`;
}

function adminCostCell(estimate) {
  const cell = document.createElement("td");
  cell.className = "admin-nowrap admin-cost-cell";
  if (!estimate) {
    cell.textContent = "—";
    return cell;
  }
  cell.textContent = `~${formatCostAmount(estimate.expected, estimate.currency)}`;
  cell.title = (estimate.items || [])
    .map((item) => `${item.label}: ${formatCostAmount(item.amount, estimate.currency)}`)
    .concat(`${formatLikelihood(estimate.likelihood)} chance × ${formatCostAmount(estimate.ifDisrupted, estimate.currency)} if disrupted`)
    .join("\n");
  return cell;
}
