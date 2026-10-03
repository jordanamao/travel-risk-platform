function setupAdminDashboard() {
  const button = document.querySelector("#refresh-admin-dashboard");
  if (!button) return;
  button.addEventListener("click", loadAdminDashboard);
  const clearButton = document.querySelector("#clear-assessment-history");
  if (clearButton) {
    clearButton.addEventListener("click", clearAssessmentHistory);
  }
  loadAdminDashboard();
}

async function loadAdminDashboard() {
  const dashboard = document.querySelector(".admin-dashboard");
  const button = document.querySelector("#refresh-admin-dashboard");
  const table = document.querySelector("#admin-trips");
  const historyTable = document.querySelector("#admin-history");
  const monitoringTable = document.querySelector("#admin-monitoring");
  const monitoringEventsTable = document.querySelector("#admin-monitoring-events");
  if (!table) return;

  table.innerHTML = `<tr><td colspan="6">Loading admin dashboard...</td></tr>`;
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
    table.innerHTML = `<tr><td colspan="6">${error.message}</td></tr>`;
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
  const values = [
    stats.savedTrips || 0,
    stats.employees || 0,
    stats.highRiskTrips || 0,
    stats.unreadAlerts || 0,
    stats.historyRecords || 0,
    monitoring.failures || 0,
    monitoring.slowCalls || 0
  ];
  document.querySelectorAll("#admin-stats strong").forEach((node, index) => {
    node.textContent = values[index];
  });

  const table = document.querySelector("#admin-trips");
  table.innerHTML = "";
  const trips = data.trips || [];

  if (!trips.length) {
    table.innerHTML = `<tr><td colspan="6">No employee trips have been saved yet.</td></tr>`;
    renderAdminHistory(data.history || []);
    return;
  }

  for (const trip of trips) {
    const row = document.createElement("tr");
    const riskLevel = trip.riskLevel || "Unscored";
    row.innerHTML = `
      <td></td>
      <td><strong></strong><span></span></td>
      <td></td>
      <td></td>
      <td><span class="admin-risk"></span></td>
      <td></td>
    `;
    row.children[0].textContent = trip.username;
    row.children[1].querySelector("strong").textContent = `${trip.origin} to ${trip.destination}`;
    row.children[1].querySelector("span").textContent = trip.summary || "Saved assessment snapshot";
    row.children[2].textContent = trip.date;
    row.children[3].textContent = tripTypeLabel(trip.mode);
    const risk = row.querySelector(".admin-risk");
    risk.className = `admin-risk ${riskLevel.toLowerCase()}`;
    risk.textContent = `${riskLevel}${trip.riskPoints !== null && trip.riskPoints !== undefined ? ` · ${trip.riskPoints} pts` : ""}`;
    row.children[5].textContent = formatNotificationTime(trip.updatedAt);
    table.appendChild(row);
  }

  renderAdminHistory(data.history || []);
  renderAdminMonitoring(data.monitoring || {});
}

function renderAdminHistory(history) {
  const table = document.querySelector("#admin-history");
  if (!table) return;

  table.innerHTML = "";
  if (!history.length) {
    table.innerHTML = `<tr><td colspan="6">No assessment history yet.</td></tr>`;
    return;
  }

  for (const record of history) {
    const row = document.createElement("tr");
    const riskLevel = record.riskLevel || "Unscored";
    row.innerHTML = `
      <td></td>
      <td><strong></strong><span></span></td>
      <td></td>
      <td></td>
      <td><span class="admin-risk"></span></td>
      <td></td>
    `;
    row.children[0].textContent = record.username;
    row.children[1].querySelector("strong").textContent = `${record.origin} to ${record.destination}`;
    row.children[1].querySelector("span").textContent = record.summary || "Assessment snapshot stored for review";
    row.children[2].textContent = record.date;
    row.children[3].textContent = tripTypeLabel(record.mode);
    const risk = row.querySelector(".admin-risk");
    risk.className = `admin-risk ${riskLevel.toLowerCase()}`;
    risk.textContent = `${riskLevel}${record.riskPoints !== null && record.riskPoints !== undefined ? ` · ${record.riskPoints} pts` : ""}`;
    row.children[5].textContent = formatNotificationTime(record.createdAt);
    table.appendChild(row);
  }
}

function renderAdminMonitoring(monitoring) {
  const table = document.querySelector("#admin-monitoring");
  const count = document.querySelector("#admin-monitoring-metric-count");
  if (!table) return;

  const metrics = monitoring.metrics || [];
  if (count) count.textContent = metrics.length;
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
        <td></td>
        <td></td>
      `;
      row.children[0].querySelector("strong").textContent = metric.operation;
      row.children[1].textContent = metric.calls;
      row.children[2].textContent = metric.failures;
      row.children[3].textContent = metric.slowCalls;
      row.children[4].textContent = `${metric.averageDurationMs} ms`;
      row.children[5].textContent = `${metric.maxDurationMs} ms`;
      table.appendChild(row);
    }
  }

  renderAdminMonitoringEvents(monitoring.recentEvents || []);
}

function renderAdminMonitoringEvents(events) {
  const table = document.querySelector("#admin-monitoring-events");
  const count = document.querySelector("#admin-monitoring-event-count");
  if (!table) return;
  if (count) count.textContent = events.length;

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
      <td></td>
      <td></td>
      <td></td>
    `;
    const type = row.querySelector(".admin-risk");
    type.className = `admin-risk ${event.type === "failure" ? "high" : "medium"}`;
    type.textContent = event.type;
    row.children[1].textContent = event.operation;
    row.children[2].textContent = `${event.durationMs} ms`;
    row.children[3].textContent = event.error || "-";
    row.children[4].textContent = formatNotificationTime(event.createdAt);
    table.appendChild(row);
  }
}

async function clearAssessmentHistory() {
  const button = document.querySelector("#clear-assessment-history");
  if (!button) return;

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
