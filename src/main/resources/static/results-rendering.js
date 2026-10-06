function setState(state) {
  placeTripsPanel(state === "empty");
  emptyState.classList.toggle("hidden", state !== "empty");
  loading.classList.toggle("hidden", state !== "loading");
  results.classList.toggle("hidden", state !== "results");
  errorBox.classList.toggle("hidden", state !== "error");
}

function renderResults(data) {
  const level = data.score.level.toLowerCase();
  document.querySelector("#risk-route").textContent =
      `${data.input.origin} to ${data.input.destination}`;
  document.querySelector("#risk-trip-type").textContent =
      `${friendlyDate(data.input.date)} · ${tripTypeLabel(data.input.mode)}`;
  document.querySelector("#risk-summary").textContent = data.summary;
  document.querySelector("#recommendation").textContent = data.recommendation;

  const header = document.querySelector(".risk-header");
  header.className = `risk-header ${level}`;

  const badge = document.querySelector("#risk-badge");
  badge.className = `risk-badge ${level}`;
  badge.textContent = data.score.level;

  const decision = decisionForLevel(data.score.level);
  const decisionBadge = document.querySelector("#decision-badge");
  decisionBadge.className = `decision-badge ${level}`;
  decisionBadge.textContent = decision;

  renderTopDrivers(data.signals);
  renderFreshness(data);
  updateRadarMap(data.route);
  renderGlance(data);
  renderImpactSplit(data);
  renderScoreBreakdown(data.signals);
  renderNextSteps(data);
  renderWhySummary(data);
  renderSignals(data.signals);

  renderSources(data);

  renderEvidence(data);
  renderPolicyResult(data);
}

// The trips card fills the empty right column before a result exists, then returns under the form.
function placeTripsPanel(showInEmptyState) {
  const panel = document.querySelector(".saved-trips-panel");
  const slot = showInEmptyState
      ? document.querySelector("#empty-trips-slot")
      : document.querySelector(".input-panel .panel-content");
  if (panel && slot && panel.parentElement !== slot) slot.appendChild(panel);
}

function friendlyDate(value) {
  const date = new Date(`${value}T00:00:00`);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("en-US", { weekday: "short", month: "short", day: "numeric", year: "numeric" });
}

function decisionForLevel(level) {
  if (level === "High") return "Delay / reroute";
  if (level === "Medium") return "Monitor closely";
  return "Proceed";
}

function renderTopDrivers(signals) {
  const container = document.querySelector("#top-drivers");
  const topSignals = [...signals]
      .filter((signal) => (signal.points || 0) > 0)
      .sort((a, b) => (b.points || 0) - (a.points || 0))
      .slice(0, 3);

  container.innerHTML = "";
  if (!topSignals.length) {
    container.textContent = "No major risk drivers were detected.";
    return;
  }

  const label = document.createElement("span");
  label.textContent = "Main drivers";
  container.appendChild(label);

  for (const text of [...new Set(topSignals.map(shortSignalLabel))]) {
    const pill = document.createElement("strong");
    pill.textContent = text;
    container.appendChild(pill);
  }
}

function renderFreshness(data) {
  const sourceCount = data.sources.length;
  const checkedAt = new Date().toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit"
  });
  document.querySelector("#freshness-note").textContent =
      `Last checked ${checkedAt}. ${sourceCount} external sources reviewed.`;
}

async function compareDates() {
  return runDateComparison({ automatic: false });
}

async function runDateComparison({ automatic }) {
  const container = document.querySelector("#scenario-results");
  const button = document.querySelector("#compare-dates");
  const formData = new FormData(form);
  const baseDate = new Date(`${formData.get("date")}T00:00:00`);
  const dates = [0, 1, 2]
      .map((offset) => {
        const date = new Date(baseDate);
        date.setDate(baseDate.getDate() + offset);
        return formatDate(date);
      })
      .filter((date) => date <= dateInput.max);

  button.disabled = true;
  button.textContent = automatic ? "Updating..." : "Comparing...";
  container.innerHTML = `<p class="section-note">Checking nearby forecast dates...</p>`;

  try {
    const results = await Promise.all(dates.map(async (date) => {
      const params = new URLSearchParams(formData);
      params.set("date", date);
      params.set("recordHistory", "false");
      const response = await fetch(`/api/analyze?${params}`);
      const data = await readJsonResponse(response, "Unable to compare dates");
      if (!response.ok) throw new Error(data.error || "Unable to compare dates");
      return data;
    }));
    renderScenarioResults(results);
  } catch (error) {
    container.innerHTML = `<p class="error-inline">${error.message}</p>`;
  } finally {
    button.disabled = false;
    button.textContent = "Compare dates";
  }
}

function renderScenarioResults(results) {
  const container = document.querySelector("#scenario-results");
  container.innerHTML = "";
  const usesRoadData = results.some((item) => item.signals.some((signal) => signal.type === "road-closure"));

  for (const item of results) {
    const row = document.createElement("article");
    const level = item.score.level.toLowerCase();
    row.className = `scenario-row ${level}`;
    row.innerHTML = `
      <div>
        <strong></strong>
        <span></span>
      </div>
      <p></p>
    `;
    row.querySelector("strong").textContent = friendlyDate(item.input.date);
    row.querySelector("span").textContent =
        `${decisionForLevel(item.score.level)} · ${item.score.points} pts · ${item.score.level}`;
    const drivers = [...new Set(item.signals.map(shortSignalLabel))];
    row.querySelector("p").textContent = drivers.length
        ? drivers.slice(0, 2).join(", ")
        : "No major scored signals";
    container.appendChild(row);
  }
  if (usesRoadData) {
    const note = document.createElement("p");
    note.className = "field-note";
    note.textContent = "Road closures reflect current conditions, so they count the same on every date.";
    container.appendChild(note);
  }
}

function buildReportText(data) {
  const drivers = [...data.signals]
      .filter((signal) => (signal.points || 0) > 0)
      .sort((a, b) => (b.points || 0) - (a.points || 0))
      .map((signal) => `- ${shortSignalLabel(signal)}: +${signal.points || 0}`)
      .join("\n");

  return [
    `Travel disruption risk assessment`,
    `${data.input.origin} to ${data.input.destination}`,
    `Date: ${friendlyDate(data.input.date)}`,
    `Decision: ${decisionForLevel(data.score.level)}`,
    `Risk: ${data.score.level} (${data.score.points} points)`,
    ``,
    `Recommended action:`,
    data.recommendation,
    ``,
    `Main drivers:`,
    drivers || "- No major scored drivers",
    ``,
    `Limits:`,
    data.uncertainty
  ].join("\n");
}

function renderImpactSplit(data) {
  const container = document.querySelector("#impact-split");
  const segments = [
    {
      label: "Origin",
      level: segmentLevel(data, "Origin"),
      detail: "Departure area"
    },
    {
      label: "Destination",
      level: segmentLevel(data, "Destination"),
      detail: "Arrival area"
    },
    {
      label: "Route midpoint",
      level: segmentLevel(data, "Route midpoint"),
      detail: "En-route weather"
    }
  ];

  const roadPoints = scoreCategory(data.signals, (signal) => signal.type === "road-closure");
  container.innerHTML = segments.map((segment) => `
    <article class="impact-card ${segment.level.toLowerCase()}">
      <span>${segment.label}</span>
      <strong>${segment.level}</strong>
      <p>${segment.detail}</p>
    </article>
  `).join("") + (roadPoints
      ? `<p class="impact-note">These areas reflect weather and airport conditions. Road closures add ${roadPoints} points separately.</p>`
      : "");
}

function segmentLevel(data, keyword) {
  const relatedSignals = data.signals.filter((signal) =>
      signal.message.includes(keyword) || signal.evidence.includes(keyword)
  );
  const relatedEvidence = data.evidence.filter((item) =>
      item.label.includes(keyword) || item.headline.includes(keyword)
  );
  const levels = [...relatedSignals, ...relatedEvidence].map((item) => item.severity);
  if (levels.includes("high")) return "High";
  if (levels.includes("medium")) return "Medium";
  if (levels.includes("low")) return "Low";
  return "Info";
}

function renderScoreBreakdown(signals) {
  const container = document.querySelector("#score-breakdown");
  const categories = [
    {
      label: "Weather",
      points: scoreCategory(signals, (signal) => ["weather", "wind"].includes(signal.type))
    },
    {
      label: "Alerts",
      points: scoreCategory(signals, (signal) => signal.type === "official-alert")
    },
    {
      label: "Airports",
      points: scoreCategory(signals, (signal) => ["aviation-weather", "faa-airport-status"].includes(signal.type))
    },
    {
      label: "Roads",
      points: scoreCategory(signals, (signal) => signal.type === "road-closure")
    }
  ];
  const total = categories.reduce((sum, item) => sum + item.points, 0);

  container.innerHTML = `
    <div class="breakdown-header">
      <strong>Risk source breakdown</strong>
      <span>${total} scored points</span>
    </div>
    <div class="breakdown-bars">
      ${categories.map((item) => `
        <div class="breakdown-row">
          <span>${item.label}</span>
          <div><i style="width: ${total ? (item.points / total) * 100 : 0}%"></i></div>
          <strong>${item.points}</strong>
        </div>
      `).join("")}
    </div>
  `;
}

function scoreCategory(signals, predicate) {
  return signals
      .filter(predicate)
      .reduce((sum, signal) => sum + (signal.points || 0), 0);
}

function renderNextSteps(data) {
  const list = document.querySelector("#next-steps");
  const level = data.score.level;
  const types = new Set(data.signals.filter((signal) => (signal.points || 0) > 0).map((signal) => signal.type));
  const steps = [];

  if (level === "High") steps.push("Confirm an alternate departure time or route with the traveler.");
  if (level === "Medium") steps.push("Build extra buffer into the trip plan.");
  if (types.has("aviation-weather") || types.has("faa-airport-status")) {
    steps.push("Check airline and airport delay boards before committing to departure.");
  }
  if (types.has("official-alert")) steps.push("Follow the active weather alert and recheck it before leaving.");
  if (types.has("road-closure")) steps.push("Check the state 511 map for closures on the planned driving route.");
  if (types.has("weather") || types.has("wind")) steps.push("Recheck the forecast within 6 hours of travel.");
  if (level === "Low") {
    steps.push("Proceed with the current plan.");
    steps.push("Recheck conditions before departure.");
  }
  if (steps.length < 3) steps.push("Keep the downloaded report for reference.");

  list.innerHTML = steps.slice(0, 4).map((step) => `<li>${step}</li>`).join("");
}

// Center the radar on the whole route, zoomed out far enough to show both ends.
function updateRadarMap(route) {
  const iframe = document.querySelector("#radar-iframe");
  if (!iframe || !route) return;

  const { origin, destination } = route;
  const lat = ((origin.lat + destination.lat) / 2).toFixed(4);
  const lon = ((origin.lon + destination.lon) / 2).toFixed(4);
  const span = Math.max(Math.abs(origin.lat - destination.lat), Math.abs(origin.lon - destination.lon));
  const zoom = span > 20 ? 3 : span > 8 ? 5 : span > 3 ? 6 : 7;

  iframe.src = `https://embed.windy.com/embed2.html?lat=${lat}&lon=${lon}&zoom=${zoom}&level=surface&overlay=radar&menu=&message=true&marker=true&calendar=now&pressure=&type=map&location=coordinates&detail=&metricWind=default&metricTemp=default&radarRange=-1`;
}

function renderSources(data) {
  const container = document.querySelector("#sources");
  container.innerHTML = "";
  for (const source of data.sources) {
    const status = sourceStatus(source, data.evidence);
    const item = document.createElement("article");
    item.className = "source-row";
    item.innerHTML = `
      <div>
        <p class="source-name"><span></span><em class="source-status"></em></p>
        <p class="source-purpose"></p>
      </div>
      <a target="_blank" rel="noreferrer"></a>
    `;
    item.querySelector(".source-name span").textContent = sourceDisplayName(source.name);
    const badge = item.querySelector(".source-status");
    badge.className = `source-status ${status.tone}`;
    badge.textContent = status.label;
    item.querySelector(".source-purpose").textContent = source.purpose;
    const link = item.querySelector("a");
    link.href = source.url;
    link.textContent = "Visit site ↗";
    container.appendChild(item);
  }
}

function sourceDisplayName(name) {
  return name.replace(/\s+API$/, "");
}

function sourceStatus(source, evidence) {
  const items = evidence.filter((item) => item.source === source.name);
  if (!items.length) {
    // Geocoding leaves no evidence rows; reaching a result means it located the route.
    return { tone: "ok", label: "OK" };
  }
  const usable = items.filter((item) => item.severity !== "unknown");
  if (usable.length) {
    return { tone: "ok", label: `OK · ${usable.length} item${usable.length === 1 ? "" : "s"}` };
  }
  if (items.some((item) => /not connected/i.test(item.headline))) {
    return { tone: "muted", label: "Not connected" };
  }
  return { tone: "failed", label: "No response" };
}

function renderGlance(data) {
  const countOf = (types) => data.signals.filter((signal) => types.includes(signal.type)).length;
  const alertCount = countOf(["official-alert"]);
  const airportIssueCount = countOf(["aviation-weather", "faa-airport-status"]);
  const forecastIssueCount = countOf(["weather", "wind"]);
  const roadIssueCount = countOf(["road-closure"]);
  const statuses = data.sources.map((source) => sourceStatus(source, data.evidence));
  const sourceCount = data.sources.length;
  const failedCount = statuses.filter((status) => status.tone === "failed").length;

  const metrics = [
    {
      label: "Risk score",
      val: `${data.score.level} risk`,
      sub: `${data.score.points} pts · ${data.score.confidence} confidence`,
      sev: data.score.level.toLowerCase(),
      badge: data.score.level
    },
    {
      label: "Signals",
      val: `${data.signals.length} detected`,
      sub: `${alertCount} alerts · ${airportIssueCount} airport · ${forecastIssueCount} forecast · ${roadIssueCount} road`,
      sev: data.signals.length ? data.score.level.toLowerCase() : "low",
      badge: data.signals.length ? data.score.level : "None"
    },
    {
      label: "Evidence",
      val: `${data.evidence.length} items`,
      sub: `${sourceCount} sources checked`,
      sev: "info",
      badge: "Info"
    },
    {
      label: "Source health",
      val: failedCount ? `${failedCount} not responding` : "All available",
      sub: failedCount ? "Details are kept in the evidence below" : `${sourceCount - failedCount} of ${sourceCount} sources responded`,
      sev: failedCount ? "medium" : "low",
      badge: failedCount ? "Partial" : "Healthy"
    }
  ];

  const container = document.querySelector("#glance");
  container.innerHTML = metrics.map(m => `
    <div class="card metric-card">
      <span class="meta">${m.label}</span>
      <strong class="metric-val">${m.val}</strong>
      <span class="body">${m.sub}</span>
      <span class="severity ${m.sev}">${m.badge}</span>
    </div>
  `).join('');
}

function renderWhySummary(data) {
  const list = document.querySelector("#why-list");
  const math = document.querySelector("#score-math");
  list.innerHTML = "";

  const scoredSignals = [...data.signals]
      .sort((a, b) => (b.points || 0) - (a.points || 0));

  if (!scoredSignals.length) {
    const item = document.createElement("li");
    item.innerHTML = `
      <span>No scored risk signals were detected. The available evidence stayed below the scoring thresholds.</span>
      <strong>+0</strong>
    `;
    list.appendChild(item);
  } else {
    for (const signal of summarizeWhySignals(scoredSignals)) {
      const item = document.createElement("li");
      item.innerHTML = `
        <span>
          <b>${signal.title}</b>
          <em>${signal.reason}</em>
        </span>
        <strong>+${signal.points}</strong>
      `;
      list.appendChild(item);
    }
  }

  const pointEquation = scoredSignals.map((signal) => signal.points || 0).join(" + ");
  math.textContent = scoredSignals.length
      ? `${pointEquation} = ${data.score.points} points. ${scoreBandText(data.score.points)} Result: ${data.score.level} risk.`
      : `0 points. ${scoreBandText(0)} Result: ${data.score.level} risk.`;
}

function summarizeWhySignals(signals) {
  const airportSignals = signals.filter((signal) => signal.type === "aviation-weather");
  const otherSignals = signals.filter((signal) => signal.type !== "aviation-weather");
  const summaries = [];

  if (airportSignals.length > 1) {
    const airportNames = airportSignals.map(airportSignalName).join(", ");
    const pointValues = airportSignals.map((signal) => signal.points || 0);
    const totalPoints = pointValues.reduce((total, points) => total + points, 0);
    summaries.push({
      points: totalPoints,
      title: `Airport weather evidence (${airportSignals.length} airports)`,
      reason: `The risk score is calculated from evidence at ${airportNames}. Each airport-weather signal adds points for severity, and flight mode adds the airport-weather bonus. ${pointValues.join(" + ")} = ${totalPoints} points from airport conditions.`
    });
  } else if (airportSignals.length === 1) {
    const signal = airportSignals[0];
    summaries.push({
      points: signal.points || 0,
      title: shortSignalLabel(signal),
      reason: scoreReason(signal)
    });
  }

  for (const signal of otherSignals) {
    summaries.push({
      points: signal.points || 0,
      title: shortSignalLabel(signal),
      reason: scoreReason(signal)
    });
  }

  return summaries.sort((a, b) => b.points - a.points);
}

function airportSignalName(signal) {
  const label = shortSignalLabel(signal);
  const match = label.match(/^([A-Z0-9]+)/);
  return match ? match[1] : "airport weather";
}

function sourceHealthSummary(sourceCount, unavailableEvidence) {
  if (!unavailableEvidence.length) {
    return `${sourceCount} sources responded cleanly`;
  }
  const names = [...new Set(unavailableEvidence.map((item) => item.source))];
  return `${names.slice(0, 2).join(", ")}${names.length > 2 ? ` +${names.length - 2}` : ""} had fallback evidence`;
}

function scoreReason(signal) {
  const severityText = signal.severity === "high"
      ? "High severity adds 6 points"
      : signal.severity === "medium"
      ? "Medium severity adds 3 points"
      : signal.severity === "low"
      ? "Low severity adds 1 point"
      : "Informational evidence adds 0 points";
  const flightBonus = signal.type === "aviation-weather" && (signal.points || 0) > severityBasePoints(signal.severity)
      ? "; flight mode adds +1 airport-weather bonus"
      : "";
  const evidence = signal.type === "road-closure"
      ? signal.evidence.split("; ").map(friendlyRoadTitle).join(", ")
      : signal.evidence;
  return `${severityText}${flightBonus}. Evidence: ${evidence || "source evidence"}.`;
}

function severityBasePoints(severity) {
  return { high: 6, medium: 3, low: 1, info: 0, unknown: 0 }[severity] || 0;
}

function scoreBandText(points) {
  if (points >= 10) return "10+ points is High.";
  if (points >= 5) return "5-9 points is Medium.";
  return "0-4 points is Low.";
}

function renderCards(selector, items) {
  const container = document.querySelector(selector);
  container.innerHTML = "";
  for (const item of items) {
    const card = document.createElement("article");
    card.className = "card";
    card.innerHTML = `
      <p class="meta"></p>
      <p><strong></strong></p>
      <p class="body"></p>
      ${item.severity ? `<span class="severity ${item.severity}">${item.severity}</span>` : ""}
    `;
    card.querySelector(".meta").textContent = item.meta;
    card.querySelector("strong").textContent = item.title;
    card.querySelector(".body").textContent = item.body || "";
    container.appendChild(card);
  }
}

function renderSignals(signals) {
  const container = document.querySelector("#signals");
  container.innerHTML = "";

  if (!signals.length) {
    renderCards("#signals", [
      {
        meta: "No major signals",
        title: "No meaningful risk signal was detected.",
        body: "The evidence still appears below for review.",
        severity: "low"
      }
    ]);
    return;
  }

  groupSignals(signals).forEach((group, index) => {
    container.appendChild(createGroup(group, index === 0, createSignalCard));
  });
}

const GROUP_PREVIEW_COUNT = 3;

// Shared collapsible group: the first few items show, the rest sit behind "Show all".
function createGroup(group, open, renderItem) {
  const wrapper = document.createElement("details");
  wrapper.className = "evidence-group";
  wrapper.open = open;
  wrapper.innerHTML = `
    <summary>
      <span class="summary-title"></span>
      <span class="summary-description"></span>
    </summary>
    <div class="evidence-group-body"></div>
  `;
  wrapper.querySelector(".summary-title").textContent = group.title;
  wrapper.querySelector(".summary-description").textContent = group.description;

  const body = wrapper.querySelector(".evidence-group-body");
  group.items.forEach((item, index) => {
    const card = renderItem(item);
    if (index >= GROUP_PREVIEW_COUNT) card.classList.add("hidden");
    body.appendChild(card);
  });
  if (group.items.length > GROUP_PREVIEW_COUNT) {
    const more = document.createElement("button");
    more.type = "button";
    more.className = "show-all-button";
    more.textContent = `Show all ${group.items.length}`;
    more.addEventListener("click", () => {
      body.querySelectorAll(".card.hidden").forEach((card) => card.classList.remove("hidden"));
      more.remove();
    });
    body.appendChild(more);
  }
  return wrapper;
}

function createSignalCard(signal) {
  const card = document.createElement("article");
  card.className = "card";
  card.innerHTML = `
    <p class="meta"></p>
    <p><strong></strong></p>
    <p class="body"></p>
    <div class="card-footer">
      <span class="severity ${signal.severity}">${signal.severity}</span>
      <span class="points-chip"></span>
    </div>
  `;
  card.querySelector(".meta").textContent = shortSignalLabel(signal);
  card.querySelector("strong").textContent = signal.message;
  const body = signal.type === "road-closure"
      ? signal.evidence.split("; ").map(friendlyRoadTitle).join(" · ")
      : signal.evidence || "";
  card.querySelector(".body").textContent = body === signal.message ? "" : body;
  card.querySelector(".points-chip").textContent = `+${signal.points || 0} pts`;
  return card;
}

function friendlyRoadTitle(title) {
  return String(title || "")
      .replace(/^Full\s*[—-]\s*(.+)$/i, "Full closure on $1")
      .replace(/^Work Zone\s*[—-]\s*(.+)$/i, "Work zone on $1")
      .replace(/^Lane\s*[—-]\s*(.+)$/i, "Lane closure on $1");
}

function cleanRoadDescription(text) {
  return String(text || "")
      .replace(/,?\s*est\.? delay Not Reported( min)?/i, "")
      .trim();
}

function shortSignalLabel(signal) {
  if (signal.type === "weather") return "Heavy precipitation forecast";
  if (signal.type === "wind") return "Wind forecast";
  if (signal.type === "road-closure") return "Road closures";
  if (signal.type === "faa-airport-status") return "FAA airport status";
  if (signal.type === "aviation-weather") {
    const match = signal.message.match(/near\s+([A-Z0-9]+)/);
    return match ? `${match[1]} airport weather` : "Airport weather";
  }
  if (signal.type === "official-alert") {
    const match = signal.message.match(/alert:\s*(.+)$/);
    return match ? match[1] : "Official alert";
  }
  return signal.type || "Signal";
}

function groupSignals(signals) {
  const groups = [
    { title: "Forecast weather", description: "Precipitation or wind in the forecast.", types: ["weather", "wind"] },
    { title: "Official alerts", description: "Active National Weather Service alerts.", types: ["official-alert"] },
    { title: "Airport conditions", description: "Airport weather and FAA delay or closure status.", types: ["aviation-weather", "faa-airport-status"] },
    { title: "Road closures", description: "Current closures and incidents from 511 traffic data.", types: ["road-closure"] }
  ];
  const known = groups.flatMap((group) => group.types);
  const result = groups.map((group) => {
    const items = signals.filter((signal) => group.types.includes(signal.type));
    return { title: `${group.title} (${items.length})`, description: group.description, items };
  });
  const other = signals.filter((signal) => !known.includes(signal.type));
  result.push({ title: `Other signals (${other.length})`, description: "Additional detected risk signals.", items: other });
  return result.filter((group) => group.items.length);
}

function renderEvidence(data) {
  const container = document.querySelector("#evidence");
  container.innerHTML = "";
  groupEvidence(data).forEach((group, index) => {
    container.appendChild(createGroup(group, index === 0, createEvidenceCard));
  });
}

function createEvidenceCard(item) {
  const card = document.createElement("article");
  card.className = "card";
  card.innerHTML = `
      <p class="meta"></p>
      <p><strong></strong></p>
      <p class="body"></p>
      <div class="card-footer">
        <span class="severity ${item.severity || "unknown"}">${item.severity || "unknown"}</span>
      </div>
    `;
  card.querySelector(".meta").textContent = `${sourceDisplayName(item.source)} · ${item.label}`;
  const isRoad = item.source === "Road511 Traffic Data API";
  const headline = isRoad ? friendlyRoadTitle(item.headline) : item.headline;
  card.querySelector("strong").textContent = headline;
  const body = isRoad
      ? cleanRoadDescription(item.details && item.details.description)
      : summarizeDetails(item.details);
  card.querySelector(".body").textContent = body === headline || body === item.headline ? "" : body;
  return card;
}

function groupEvidence(data) {
  const evidence = data.evidence;
  const take = (predicate) => evidence.filter(predicate);
  const openMeteo = take((item) => item.source === "Open-Meteo Forecast API");
  const nationalWeather = take((item) => item.source === "National Weather Service API");
  const originAirports = take((item) => item.label === "Origin airport weather");
  const destinationAirports = take((item) => item.label === "Destination airport weather");
  const faa = take((item) => item.source === "FAA NAS Status API");
  const roads = take((item) => item.source === "Road511 Traffic Data API");
  const grouped = new Set([...openMeteo, ...nationalWeather, ...originAirports, ...destinationAirports, ...faa, ...roads]);
  const otherEvidence = take((item) => !grouped.has(item));

  return [
    { title: `Forecast (${openMeteo.length})`, description: forecastResult(openMeteo), items: openMeteo },
    { title: `National Weather Service (${nationalWeather.length})`, description: alertResult(nationalWeather), items: nationalWeather },
    { title: `${placeName(data.input.origin)} airports (${originAirports.length})`, description: airportResult(originAirports), items: originAirports },
    { title: `${placeName(data.input.destination)} airports (${destinationAirports.length})`, description: airportResult(destinationAirports), items: destinationAirports },
    { title: `FAA airport status (${faa.length})`, description: faaResult(faa), items: faa },
    { title: `Road closures (${roads.length})`, description: roadResult(roads, data.input.mode), items: roads },
    { title: `Other evidence (${otherEvidence.length})`, description: "Additional source data reviewed for this assessment.", items: otherEvidence }
  ].filter((group) => group.items.length);
}

function placeName(location) {
  return String(location || "").split(",")[0] || "Route";
}

function allUnavailable(items) {
  return items.length && items.every((item) => item.severity === "unknown");
}

function forecastResult(items) {
  if (allUnavailable(items)) return "Forecast data did not respond.";
  const details = items.map((item) => item.details || {});
  const precip = Math.max(...details.map((d) => Number(d.precipitationProbabilityPercent) || 0));
  const wind = Math.max(...details.map((d) => Number(d.maxWindKmh) || 0));
  return `Up to ${Math.round(precip)}% chance of precipitation, wind up to ${Math.round(wind)} km/h.`;
}

function alertResult(items) {
  if (allUnavailable(items)) return "Weather service did not respond.";
  const alerts = items.filter((item) => / alert$/.test(item.label));
  if (!alerts.length) return "No active alerts.";
  return `${alerts.length} active alert${alerts.length === 1 ? "" : "s"}: ${alerts[0].details?.event || alerts[0].headline}.`;
}

function airportResult(items) {
  if (allUnavailable(items)) return "Airport observations did not respond.";
  return items
      .filter((item) => item.details && item.details.station)
      .map((item) => {
        const d = item.details;
        const wind = d.windKt !== undefined && d.windKt !== null ? `, wind ${d.windKt} kt` : "";
        const gust = d.gustKt ? ` gusting ${d.gustKt}` : "";
        return `${d.station}: ${d.flightCategory || "no category"}${wind}${gust}`;
      })
      .join(" · ") || "No nearby stations reported.";
}

function faaResult(items) {
  if (allUnavailable(items)) return "FAA status did not respond.";
  const airports = [...new Set(items.map((item) => item.details?.airport).filter(Boolean))];
  const events = items.filter((item) => item.details?.eventType);
  if (!events.length) return `No delays or closures at ${airports.join(" or ") || "the route airports"}.`;
  return events.map((item) => item.headline).slice(0, 2).join(" · ");
}

function roadResult(items, mode) {
  if (items.some((item) => /not connected/i.test(item.headline))) return "Road data is not connected for this deployment.";
  if (allUnavailable(items)) return "Road data did not respond.";
  const risky = items.filter((item) => ["medium", "high"].includes(item.severity));
  const area = items[0]?.details?.jurisdiction;
  if (!risky.length) return `No closures that affect the score${area ? ` in ${area}` : ""}.`;
  const found = `${risky.length} closure${risky.length === 1 ? "" : "s"} or incident${risky.length === 1 ? "" : "s"} reported right now`;
  return mode === "flight" ? `${found}, not scored for flight trips.` : `${found}.`;
}

function summarizeDetails(details) {
  if (!details) return "";
  if (Array.isArray(details)) {
    return details
        .map((item) => `${item.name || "Period"}: ${item.shortForecast || item.detailedForecast || ""}`)
        .join(" ");
  }
  return summarizeObjectDetails(details);
}

function summarizeObjectDetails(details) {
  return Object.entries(details)
      .filter(([key, value]) => key !== "error" && key !== "reason" && value !== null && value !== undefined && value !== "")
      .slice(0, 8)
      .map(([key, value]) => `${labelize(key)}: ${String(value)}`)
      .join(" · ");
}

function labelize(key) {
  return key.replace(/([A-Z])/g, " $1").replace(/^./, (char) => char.toUpperCase());
}

function tripTypeLabel(mode) {
  return {
    flight: "Flight: airport weather matters most",
    drive: "Driving: route weather matters most",
    general: "Business: mixed travel modes"
  }[mode] || "Travel assessment";
}
