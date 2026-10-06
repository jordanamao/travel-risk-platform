const ADMIN_HISTORY_PREVIEW_ROWS = 5;
const ADMIN_SECTION_STORAGE_KEY = "adminDashboardSections";
let adminHistoryRecords = [];
let adminHistoryExpanded = false;

function setupAdminDashboard() {
  const button = document.querySelector("#refresh-admin-dashboard");
  if (!button) return;
  button.addEventListener("click", loadAdminDashboard);
  const clearButton = document.querySelector("#clear-assessment-history");
  if (clearButton) {
    clearButton.addEventListener("click", clearAssessmentHistory);
  }
  const historyToggle = document.querySelector("#admin-history-toggle");
  if (historyToggle) {
    historyToggle.addEventListener("click", () => {
      adminHistoryExpanded = !adminHistoryExpanded;
      renderAdminHistory(adminHistoryRecords);
    });
  }
  const slowCallsTile = document.querySelector("#admin-slow-calls-tile");
  if (slowCallsTile) {
    slowCallsTile.addEventListener("click", showAdminMonitoring);
  }
  setupAdminSections();
  loadAdminDashboard();
}

function setupAdminSections() {
  const saved = readAdminSectionState();
  document.querySelectorAll(".admin-section[data-section]").forEach((section) => {
    const name = section.dataset.section;
    if (Object.prototype.hasOwnProperty.call(saved, name)) {
      section.open = saved[name];
    }
    section.addEventListener("toggle", () => {
      const state = readAdminSectionState();
      state[name] = section.open;
      try {
        localStorage.setItem(ADMIN_SECTION_STORAGE_KEY, JSON.stringify(state));
      } catch {
        // Collapsed sections just won't be remembered.
      }
    });
  });
}

function readAdminSectionState() {
  try {
    return JSON.parse(localStorage.getItem(ADMIN_SECTION_STORAGE_KEY)) || {};
  } catch {
    return {};
  }
}

function showAdminMonitoring() {
  const section = document.querySelector("#admin-monitoring-section");
  if (!section) return;
  section.open = true;
  section.scrollIntoView({ behavior: "smooth", block: "start" });
}

async function loadAdminDashboard() {
  const dashboard = document.querySelector(".admin-dashboard");
  const button = document.querySelector("#refresh-admin-dashboard");
  const table = document.querySelector("#admin-trips");
  const historyTable = document.querySelector("#admin-history");
  const monitoringTable = document.querySelector("#admin-monitoring");
  const monitoringEventsTable = document.querySelector("#admin-monitoring-events");
  if (!table) return;

  table.innerHTML = `<tr><td colspan="7">Loading admin dashboard...</td></tr>`;
  if (historyTable) {
    historyTable.innerHTML = `<tr><td colspan="6">Loading assessment history...</td></tr>`;
  }
  if (monitoringTable) {
    monitoringTable.innerHTML = `<tr><td colspan="6">Loading API monitoring...</td></tr>`;
  }
  if (monitoringEventsTable) {
    monitoringEventsTable.innerHTML = `<tr><td colspan="5">Loading API monitoring events...</td></tr>`;
  }
  if (button) {
    button.disabled = true;
    button.textContent = "Refreshing...";
  }

  try {
    const response = await fetch("/api/admin/dashboard");
    if (response.status === 403) {
      hideAdminDashboard();
      return;
    }
    const data = await readJsonResponse(response, "Unable to load admin dashboard");
    if (!response.ok) throw new Error(data.error || "Unable to load admin dashboard");
    if (dashboard) dashboard.classList.remove("hidden");
    renderAdminDashboard(data);
  } catch (error) {
    table.innerHTML = `<tr><td colspan="7">${error.message}</td></tr>`;
    if (historyTable) {
      historyTable.innerHTML = `<tr><td colspan="6">${error.message}</td></tr>`;
    }
    if (monitoringTable) {
      monitoringTable.innerHTML = `<tr><td colspan="6">${error.message}</td></tr>`;
    }
    if (monitoringEventsTable) {
      monitoringEventsTable.innerHTML = `<tr><td colspan="5">${error.message}</td></tr>`;
    }
  } finally {
    if (button) {
      button.disabled = false;
      button.textContent = "Refresh dashboard";
    }
  }
}

function hideAdminDashboard() {
  const dashboard = document.querySelector(".admin-dashboard");
  if (dashboard) dashboard.classList.add("hidden");
}

function renderAdminDashboard(data) {
  const stats = data.stats || {};
  const monitoring = data.monitoring || {};
  const slowOrFailed = (monitoring.failures || 0) + (monitoring.slowCalls || 0);
  const employees = stats.employees || 0;
  setAdminStat("savedTrips", stats.savedTrips || 0, `by ${employees} ${employees === 1 ? "employee" : "employees"}`);
  setAdminStat("highRiskTrips", stats.highRiskTrips || 0, "saved trips rated High");
  setAdminStat("historyRecords", stats.historyRecords || 0, `${stats.highRiskChecks || 0} rated High since Oct 6 fix`);
  setAdminStat("slowOrFailed", slowOrFailed,
    slowOrFailed ? `${monitoring.slowCalls || 0} slow · ${monitoring.failures || 0} failed` : "No slow or failed calls");

  const table = document.querySelector("#admin-trips");
  table.innerHTML = "";
  const trips = data.trips || [];
  setAdminCount("#admin-trip-count", trips.length);

  if (!trips.length) {
    table.innerHTML = `<tr><td colspan="7">No employee trips have been saved yet.</td></tr>`;
  }

  for (const trip of trips) {
    const row = adminAssessmentRow(trip, trip.updatedAt, false);
    const policyCell = document.createElement("td");
    policyCell.appendChild(policyBadge(trip.policy));
    row.insertBefore(policyCell, row.lastElementChild);
    table.appendChild(row);
  }

  adminHistoryExpanded = false;
  renderAdminHistory(data.history || [], stats.historyRecords);
  renderAdminMonitoring(monitoring);
}

function setAdminStat(name, value, note) {
  const valueNode = document.querySelector(`[data-stat="${name}"]`);
  const noteNode = document.querySelector(`[data-stat-note="${name}"]`);
  if (valueNode) valueNode.textContent = value;
  if (noteNode) noteNode.textContent = note;
}

function setAdminCount(selector, value) {
  const node = document.querySelector(selector);
  if (node) node.textContent = value;
}

function adminAssessmentRow(record, timestamp, scoredBeforeFix) {
  const row = document.createElement("tr");
  const riskLevel = record.riskLevel || "Unscored";
  row.innerHTML = `
    <td><strong class="admin-employee"></strong><span></span></td>
    <td><strong></strong><span></span></td>
    <td class="admin-nowrap"></td>
    <td class="admin-nowrap"></td>
    <td><span class="admin-risk"></span></td>
    <td class="admin-nowrap"></td>
  `;
  const employee = record.employee || { name: record.username };
  row.children[0].querySelector("strong").textContent = employee.name || record.username;
  const employeeDetail = row.children[0].querySelector("span");
  if (employee.detail) {
    employeeDetail.textContent = employee.detail;
  } else {
    employeeDetail.remove();
  }
  row.children[1].querySelector("strong").textContent = `${record.origin} to ${record.destination}`;
  row.children[1].querySelector("span").textContent = record.summary || "Saved assessment snapshot";
  row.children[2].textContent = formatAdminTravelDate(record.date);
  row.children[3].textContent = adminTripTypeLabel(record.mode);
  const risk = row.querySelector(".admin-risk");
  risk.className = `admin-risk ${riskLevel.toLowerCase()}`;
  risk.textContent = `${riskLevel}${record.riskPoints !== null && record.riskPoints !== undefined ? ` · ${record.riskPoints} pts` : ""}`;
  if (scoredBeforeFix) {
    row.classList.add("admin-row-stale");
    const tag = document.createElement("span");
    tag.className = "admin-stale-tag";
    tag.textContent = "Scored before Oct 6 fix";
    tag.title = "Recorded before road closures were deduplicated, so this score may be too high.";
    row.children[4].appendChild(tag);
  }
  row.children[5].textContent = formatAdminTimestamp(timestamp);
  return row;
}

function renderAdminHistory(history, total) {
  adminHistoryRecords = history;
  const table = document.querySelector("#admin-history");
  const toggle = document.querySelector("#admin-history-toggle");
  const note = document.querySelector("#admin-history-note");
  if (!table) return;

  const totalRecords = total ?? history.length;
  setAdminCount("#admin-history-count", totalRecords);
  table.innerHTML = "";
  if (!history.length) {
    table.innerHTML = `<tr><td colspan="6">No assessment history yet.</td></tr>`;
    if (toggle) toggle.classList.add("hidden");
    if (note) note.textContent = "";
    return;
  }

  const visible = adminHistoryExpanded ? history : history.slice(0, ADMIN_HISTORY_PREVIEW_ROWS);
  for (const record of visible) {
    table.appendChild(adminAssessmentRow(record, record.createdAt, record.scoredBeforeFix));
  }

  if (note) {
    const stale = history.filter((record) => record.scoredBeforeFix).length;
    const shown = visible.length >= totalRecords
      ? `Showing all ${totalRecords} checks.`
      : `Showing the ${visible.length} most recent of ${totalRecords} checks.`;
    note.textContent = stale ? `${shown} ${stale} were scored before the Oct 6 road-closure fix and may read too high.` : shown;
  }
  if (toggle) {
    toggle.classList.toggle("hidden", history.length <= ADMIN_HISTORY_PREVIEW_ROWS);
    toggle.textContent = adminHistoryExpanded ? "Show fewer" : `Show all ${history.length}`;
  }
}

function renderAdminMonitoring(monitoring) {
  const table = document.querySelector("#admin-monitoring");
  const note = document.querySelector("#admin-monitoring-note");
  if (!table) return;

  const metrics = monitoring.metrics || [];
  if (note) {
    note.textContent = `Calls slower than ${monitoring.slowCallThresholdMs || 1500} ms count as slow. Counts reset when the app restarts.`;
  }
  table.innerHTML = "";
  if (!metrics.length) {
    table.innerHTML = `<tr><td colspan="6">No API monitoring data yet.</td></tr>`;
  } else {
    for (const metric of metrics) {
      const row = document.createElement("tr");
      row.innerHTML = `
        <td><strong></strong></td>
        <td></td>
        <td></td>
        <td></td>
        <td class="admin-nowrap"></td>
        <td class="admin-nowrap"></td>
      `;
      row.children[0].querySelector("strong").textContent = metric.operation;
      row.children[1].textContent = metric.calls;
      row.children[2].textContent = metric.failures;
      row.children[3].textContent = metric.slowCalls;
      row.children[4].textContent = formatAdminDuration(metric.averageDurationMs);
      row.children[5].textContent = formatAdminDuration(metric.maxDurationMs);
      table.appendChild(row);
    }
  }

  renderAdminMonitoringEvents(monitoring.recentEvents || []);
}

function renderAdminMonitoringEvents(events) {
  const table = document.querySelector("#admin-monitoring-events");
  if (!table) return;
  setAdminCount("#admin-monitoring-event-count", events.length);

  table.innerHTML = "";
  if (!events.length) {
    table.innerHTML = `<tr><td colspan="5">No slow or failed API calls yet.</td></tr>`;
    return;
  }

  for (const event of events) {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td><span class="admin-risk"></span></td>
      <td></td>
      <td class="admin-nowrap"></td>
      <td></td>
      <td class="admin-nowrap"></td>
    `;
    const type = row.querySelector(".admin-risk");
    type.className = `admin-risk ${event.type === "failure" ? "high" : "medium"}`;
    type.textContent = event.type === "failure" ? "Failed" : "Slow";
    row.children[1].textContent = event.operation;
    row.children[2].textContent = formatAdminDuration(event.durationMs);
    row.children[3].textContent = event.error || "-";
    row.children[4].textContent = formatAdminTimestamp(event.createdAt);
    table.appendChild(row);
  }
}

function adminTripTypeLabel(mode) {
  return { flight: "Flight", drive: "Driving", general: "Business" }[mode] || "Trip";
}

function formatAdminTravelDate(value) {
  if (!value) return "-";
  // Travel dates are calendar days, so read them as local dates rather than UTC midnight.
  const [year, month, day] = value.split("-").map(Number);
  return new Date(year, month - 1, day).toLocaleDateString([], { month: "short", day: "numeric" });
}

function formatAdminTimestamp(value) {
  return new Date(value).toLocaleString([], { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
}

function formatAdminDuration(ms) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)} s` : `${ms} ms`;
}

async function clearAssessmentHistory() {
  const button = document.querySelector("#clear-assessment-history");
  if (!button) return;

  const count = document.querySelector("#admin-history-count")?.textContent || "all";
  if (!window.confirm(`Delete ${count} assessment history records for every employee? This cannot be undone.`)) {
    return;
  }

  button.disabled = true;
  button.textContent = "Clearing...";

  try {
    const response = await fetch("/api/admin/assessment-history", { method: "DELETE" });
    const data = await readJsonResponse(response, "Unable to clear assessment history");
    if (!response.ok) throw new Error(data.error || "Unable to clear assessment history");
    await loadAdminDashboard();
    button.textContent = data.deleted ? `Cleared ${data.deleted}` : "History clear";
  } catch {
    button.textContent = "Clear failed";
  } finally {
    setTimeout(() => {
      button.disabled = false;
      button.textContent = "Clear history";
    }, 1400);
  }
}
