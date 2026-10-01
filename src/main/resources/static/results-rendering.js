function setState(state) {
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
      `${data.input.date} · ${tripTypeLabel(data.input.mode)}`;
  document.querySelector("#risk-summary").textContent = data.summary;
  document.querySelector("#recommendation").textContent = data.recommendation;
  document.querySelector("#uncertainty").textContent =
      `${data.uncertainty} ${summaryModeText(data.ai)}`;

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
  updateRadarMap(data.input.destination);
  renderGlance(data);
  renderImpactSplit(data);
  renderScoreBreakdown(data.signals);
  renderNextSteps(data);
  renderWhySummary(data);
  renderSignals(data.signals);

  renderSources(data.sources);

  renderEvidence(data.evidence);
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

  for (const signal of topSignals) {
    const pill = document.createElement("strong");
    pill.textContent = shortSignalLabel(signal);
    container.appendChild(pill);
  }
}

function renderFreshness(data) {
  const sourceCount = new Set(data.evidence.map((item) => item.source)).size;
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
    row.querySelector("strong").textContent = item.input.date;
    row.querySelector("span").textContent =
        `${decisionForLevel(item.score.level)} · ${item.score.points} pts · ${item.score.level}`;
    row.querySelector("p").textContent = item.signals.length
        ? item.signals.slice(0, 2).map(shortSignalLabel).join(", ")
        : "No major scored signals";
    container.appendChild(row);
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
    `Date: ${data.input.date}`,
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
    `${data.uncertainty} ${summaryModeText(data.ai)}`
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

  container.innerHTML = segments.map((segment) => `
    <article class="impact-card ${segment.level.toLowerCase()}">
      <span>${segment.label}</span>
      <strong>${segment.level}</strong>
      <p>${segment.detail}</p>
    </article>
  `).join("");
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
  const total = categories.reduce((sum, item) => sum + item.points, 0) || 1;

  container.innerHTML = `
    <div class="breakdown-header">
      <strong>Risk source breakdown</strong>
      <span>${total} scored points</span>
    </div>
    <div class="breakdown-bars">
      ${categories.map((item) => `
        <div class="breakdown-row">
          <span>${item.label}</span>
          <div><i style="width: ${Math.max(4, (item.points / total) * 100)}%"></i></div>
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
  const steps = level === "High"
      ? [
        "Check airline and airport delay boards before committing to departure.",
        "Confirm alternate departure time or routing with the traveler.",
        "Recheck alerts and airport weather within 6 hours of travel."
      ]
      : level === "Medium"
      ? [
        "Build extra buffer into the trip plan.",
        "Recheck weather and airport status before leaving.",
        "Keep backup ground transportation available."
      ]
      : [
        "Proceed with the current plan.",
        "Recheck conditions before departure.",
        "Keep the assessment link or report for reference."
      ];

  list.innerHTML = steps.map((step) => `<li>${step}</li>`).join("");
}

function updateRadarMap(destination) {
  const iframe = document.querySelector("#radar-iframe");
  if (!iframe) return;

  let lat = 39.8283;
  let lon = -98.5795;
  let zoom = 5;

  const dest = destination.toLowerCase();
  if (dest.includes("san francisco") || dest.includes("sfo")) {
    lat = 37.7749; lon = -122.4194; zoom = 7;
  } else if (dest.includes("new york") || dest.includes("jfk") || dest.includes("lga") || dest.includes("ewr")) {
    lat = 40.7128; lon = -74.0060; zoom = 7;
  } else if (dest.includes("chicago") || dest.includes("ord")) {
    lat = 41.8781; lon = -87.6298; zoom = 7;
  } else if (dest.includes("los angeles") || dest.includes("lax")) {
    lat = 34.0522; lon = -118.2437; zoom = 7;
  }

  iframe.src = `https://embed.windy.com/embed2.html?lat=${lat}&lon=${lon}&zoom=${zoom}&level=surface&overlay=radar&menu=&message=true&marker=true&calendar=now&pressure=&type=map&location=coordinates&detail=&metricWind=default&metricTemp=default&radarRange=-1`;
}

function renderSources(sources) {
  const container = document.querySelector("#sources");
  container.innerHTML = "";
  for (const source of sources) {
    const item = document.createElement("article");
    item.className = "source-row";
    item.innerHTML = `
      <div>
        <p class="source-name"></p>
        <p class="source-purpose"></p>
      </div>
      <a target="_blank" rel="noreferrer"></a>
    `;
    item.querySelector(".source-name").textContent = source.name;
    item.querySelector(".source-purpose").textContent = source.purpose;
    const link = item.querySelector("a");
    link.href = source.url;
    link.textContent = "Open source";
    container.appendChild(item);
  }
}

function renderGlance(data) {
  const alertCount = data.signals.filter((signal) => signal.type === "official-alert").length;
  const airportIssueCount = data.signals.filter((signal) => signal.type === "aviation-weather").length;
  const forecastIssueCount = data.signals.filter((signal) =>
      ["weather", "wind"].includes(signal.type)
  ).length;
  const sourceCount = new Set(data.evidence.map((item) => item.source)).size;
  const unavailableEvidence = data.evidence.filter((item) => item.severity === "unknown");
  const unavailableSourceCount = new Set(unavailableEvidence.map((item) => item.source)).size;

  const metrics = [
    {
      label: "Risk Score",
      val: `${data.score.level} risk`,
      sub: `${data.score.points} pts · ${data.score.confidence} confidence`,
      sev: data.score.level.toLowerCase()
    },
    {
      label: "Signals",
      val: `${data.signals.length} detected`,
      sub: `${alertCount} alerts · ${airportIssueCount} airport · ${forecastIssueCount} forecast`,
      sev: data.signals.length ? data.score.level.toLowerCase() : "low"
    },
    {
      label: "Evidence",
      val: `${data.evidence.length} items`,
      sub: `${sourceCount} external sources checked`,
      sev: "info"
    },
    {
      label: "Source Health",
      val: unavailableSourceCount ? `${unavailableSourceCount} partial` : "All available",
      sub: sourceHealthSummary(sourceCount, unavailableEvidence),
      sev: unavailableSourceCount ? "unknown" : "low"
    }
  ];

  const container = document.querySelector("#glance");
  container.innerHTML = metrics.map(m => `
    <div class="card metric-card">
      <span class="meta">${m.label}</span>
      <strong class="metric-val">${m.val}</strong>
      <span class="body">${m.sub}</span>
      <span class="severity ${m.sev}">${m.sev}</span>
    </div>
  `).join('') + `
    <p class="summary-mode-note">${summaryModeText(data.ai)} ${unavailableSourceCount ? "Unavailable source details are preserved in the evidence section so the assessment remains auditable." : "All checked sources returned usable data for this assessment."}</p>
  `;
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
  return `${severityText}${flightBonus}. Evidence: ${signal.evidence || "source evidence"}.`;
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
    const wrapper = document.createElement("details");
    wrapper.className = "evidence-group signal-group";
    if (index === 0) wrapper.open = true;
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
    for (const signal of group.items) {
      body.appendChild(createSignalCard(signal));
    }
    container.appendChild(wrapper);
  });
}

function createSignalCard(signal) {
  const card = document.createElement("article");
  card.className = "card";
  card.innerHTML = `
    <p class="meta"></p>
    <p><strong></strong></p>
    <p class="body"></p>
    ${signal.points !== undefined ? `<p class="field-note points"></p>` : ""}
    <span class="severity ${signal.severity}">${signal.severity}</span>
  `;
  card.querySelector(".meta").textContent = `${signal.type} · ${signal.severity}`;
  card.querySelector("strong").textContent = signal.message;
  card.querySelector(".body").textContent = signal.evidence || "";
  const points = card.querySelector(".points");
  if (points) points.textContent = pointExplanation(signal);
  return card;
}

function pointExplanation(signal) {
  const points = signal.points || 0;
  const label = `Adds ${points} point${points === 1 ? "" : "s"} to the risk score.`;
  if (signal.type === "aviation-weather" && points === 4 && signal.severity === "medium") {
    return `${label} 3 medium-risk points + 1 flight-focused airport bonus.`;
  }
  if (signal.type === "aviation-weather" && points === 7 && signal.severity === "high") {
    return `${label} 6 high-risk points + 1 flight-focused airport bonus.`;
  }
  if (signal.severity === "high") return `${label} High-risk signal = 6 points.`;
  if (signal.severity === "medium") return `${label} Medium-risk signal = 3 points.`;
  if (signal.severity === "low") return `${label} Low-risk signal = 1 point.`;
  return label;
}

function shortPointReason(signal) {
  if (signal.type === "aviation-weather" && signal.points > 0) {
    return " (flight bonus included)";
  }
  return "";
}

function shortSignalLabel(signal) {
  if (signal.type === "weather") return "Heavy precipitation forecast";
  if (signal.type === "wind") return "Wind forecast";
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
  const forecast = [];
  const officialAlerts = [];
  const airportWeather = [];
  const otherSignals = [];

  for (const signal of signals) {
    if (["weather", "wind"].includes(signal.type)) {
      forecast.push(signal);
    } else if (signal.type === "official-alert") {
      officialAlerts.push(signal);
    } else if (signal.type === "aviation-weather") {
      airportWeather.push(signal);
    } else {
      otherSignals.push(signal);
    }
  }

  return [
    {
      title: `Forecast weather signals (${forecast.length})`,
      description: "Open-Meteo precipitation or wind conditions that affect the score.",
      items: forecast
    },
    {
      title: `Official alert signals (${officialAlerts.length})`,
      description: "National Weather Service alerts that affect the score.",
      items: officialAlerts
    },
    {
      title: `Airport weather signals (${airportWeather.length})`,
      description: "Airport observations such as MVFR, IFR, LIFR, gusts, or visibility that affect flight risk.",
      items: airportWeather
    },
    {
      title: `Other signals (${otherSignals.length})`,
      description: "Additional detected risk signals.",
      items: otherSignals
    }
  ].filter((group) => group.items.length);
}

function renderEvidence(evidence) {
  const container = document.querySelector("#evidence");
  container.innerHTML = "";

  const groups = groupEvidence(evidence);
  groups.forEach((group, index) => {
    const wrapper = document.createElement("details");
    wrapper.className = "evidence-group";
    if (index === 0) wrapper.open = true;
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
    for (const item of group.items) {
      body.appendChild(createEvidenceCard(item));
    }
    container.appendChild(wrapper);
  });
}

function createEvidenceCard(item) {
  const card = document.createElement("article");
  card.className = "card";
  card.innerHTML = `
      <p class="meta"></p>
      <p><strong></strong></p>
      <p class="body"></p>
      <span class="severity ${item.severity || "unknown"}">${item.severity || "unknown"}</span>
    `;
  card.querySelector(".meta").textContent = `${item.source} · ${item.label}`;
  card.querySelector("strong").textContent = item.headline;
  card.querySelector(".body").textContent = summarizeDetails(item.details);
  return card;
}

function groupEvidence(evidence) {
  const openMeteo = [];
  const nationalWeather = [];
  const otherEvidence = [];
  const originAirports = [];
  const destinationAirports = [];

  for (const item of evidence) {
    if (item.source === "Open-Meteo Forecast API") {
      openMeteo.push(item);
    } else if (item.source === "National Weather Service API") {
      nationalWeather.push(item);
    } else if (item.label === "Origin airport weather") {
      originAirports.push(item);
    } else if (item.label === "Destination airport weather") {
      destinationAirports.push(item);
    } else {
      otherEvidence.push(item);
    }
  }

  return [
    {
      title: `Open-Meteo forecast (${openMeteo.length})`,
      description: "Daily forecast data for the origin, midpoint, and destination.",
      items: openMeteo
    },
    {
      title: `National Weather Service (${nationalWeather.length})`,
      description: "Official point forecasts and active alerts near the route endpoints.",
      items: nationalWeather
    },
    {
      title: `Origin airport weather (${originAirports.length})`,
      description: aviationCategoryGuide(),
      items: originAirports
    },
    {
      title: `Destination airport weather (${destinationAirports.length})`,
      description: aviationCategoryGuide(),
      items: destinationAirports
    },
    {
      title: `Other evidence (${otherEvidence.length})`,
      description: "Additional source data reviewed for this assessment.",
      items: otherEvidence
    }
  ].filter((group) => group.items.length);
}

function aviationCategoryExplanation(category) {
  return {
    VFR: "VFR means Visual Flight Rules: good flying conditions with clear enough visibility for pilots to fly by visual reference.",
    MVFR: "MVFR means Marginal Visual Flight Rules: reduced visibility or lower clouds, requiring more caution because conditions are getting worse.",
    IFR: "IFR means Instrument Flight Rules: poor visibility or low clouds, so pilots rely more on instruments and delays or cancellations are more likely.",
    LIFR: "LIFR means Low Instrument Flight Rules: very poor visibility or very low clouds; highest disruption risk."
  }[category] || "";
}

function aviationCategoryGuide() {
  return "Nearest airport observations. VFR means good flying conditions; MVFR means reduced visibility or lower clouds; IFR and LIFR mean poor conditions with higher disruption risk.";
}

function summarizeDetails(details) {
  if (!details) return "";
  if (!Array.isArray(details) && details.flightCategory) {
    const explanation = aviationCategoryExplanation(details.flightCategory);
    const base = summarizeObjectDetails(details);
    return explanation ? `${base} · ${explanation}` : base;
  }
  if (Array.isArray(details)) {
    return details
        .map((item) => `${item.name || "Period"}: ${item.shortForecast || item.detailedForecast || ""}`)
        .join(" ");
  }
  return summarizeObjectDetails(details);
}

function summarizeObjectDetails(details) {
  return Object.entries(details)
      .filter(([, value]) => value !== null && value !== undefined && value !== "")
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

function summaryModeText(ai) {
  if (ai.used) return "Summary generated with the OpenAI API.";
  if (ai.error) return "Summary generated locally because the OpenAI API is unavailable.";
  return "Summary generated locally. OpenAI API is disabled.";
}
