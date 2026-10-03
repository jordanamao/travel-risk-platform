function setupResultActions() {
  document.querySelector("#save-trip").addEventListener("click", saveLatestTrip);
  syncSaveTripButton();

  document.querySelector("#copy-summary").addEventListener("click", async () => {
    if (!latestAssessment) return;
    const text = buildReportText(latestAssessment);
    try {
      await navigator.clipboard.writeText(text);
      flashButton("#copy-summary", "Copied");
    } catch {
      flashButton("#copy-summary", "Copy failed");
    }
  });

  document.querySelector("#download-report").addEventListener("click", () => {
    if (!latestAssessment) return;
    const blob = new Blob([buildReportText(latestAssessment)], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `travel-risk-${latestAssessment.input.date}.txt`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  });

  document.querySelector("#compare-dates").addEventListener("click", compareDates);
}

function setupSavedTrips() {
  document.querySelector("#refresh-saved-trips").addEventListener("click", loadSavedTrips);
  document.querySelector("#check-alerts").addEventListener("click", checkAlerts);
  loadSavedTrips();
  loadNotifications();
}

async function saveLatestTrip() {
  if (!latestAssessment) return;
  const button = document.querySelector("#save-trip");
  button.disabled = true;
  button.textContent = "Saving...";

  try {
    const response = await fetch("/api/trips", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ assessment: latestAssessment })
    });
    const data = await readJsonResponse(response, "Unable to save trip");
    if (!response.ok) throw new Error(data.error || "Unable to save trip");
    await loadSavedTrips();
    await loadAdminDashboard();
    button.textContent = "Saved";
  } catch (error) {
    button.textContent = "Save failed";
  } finally {
    setTimeout(() => {
      button.disabled = !latestAssessment;
      button.textContent = "Save trip";
    }, 1200);
  }
}

function syncSaveTripButton() {
  const button = document.querySelector("#save-trip");
  if (!button) return;
  button.disabled = !latestAssessment;
}

async function loadSavedTrips() {
  const container = document.querySelector("#saved-trips");
  container.innerHTML = `<p class="field-note">Loading saved trips...</p>`;

  try {
    const response = await fetch("/api/trips");
    const trips = await readJsonResponse(response, "Unable to load saved trips");
    if (!response.ok) throw new Error(trips.error || "Unable to load saved trips");
    renderSavedTrips(trips);
  } catch (error) {
    container.innerHTML = `<p class="error-inline">${error.message}</p>`;
  }
}

async function checkAlerts() {
  const button = document.querySelector("#check-alerts");
  button.disabled = true;
  button.textContent = "Checking...";

  try {
    const response = await fetch("/api/trips/alerts/check", { method: "POST" });
    const data = await readJsonResponse(response, "Unable to check alerts");
    if (!response.ok) throw new Error(data.error || "Unable to check alerts");
    await loadSavedTrips();
    await loadNotifications();
    await loadAdminDashboard();
    button.textContent = data.changedTrips ? `${data.changedTrips} changed` : "No changes";
  } catch (error) {
    button.textContent = "Check failed";
  } finally {
    setTimeout(() => {
      button.disabled = false;
      button.textContent = "Check alerts";
    }, 1400);
  }
}

async function loadNotifications() {
  const container = document.querySelector("#notifications");
  const count = document.querySelector("#notification-count");
  container.innerHTML = `<p class="field-note">Loading notifications...</p>`;

  try {
    const response = await fetch("/api/notifications");
    const notifications = await readJsonResponse(response, "Unable to load notifications");
    if (!response.ok) throw new Error(notifications.error || "Unable to load notifications");
    renderNotifications(notifications);
    const unread = notifications.filter((item) => !item.read).length;
    count.textContent = `${unread} unread`;
  } catch (error) {
    container.innerHTML = `<p class="error-inline">${error.message}</p>`;
    count.textContent = "Unavailable";
  }
}

function renderNotifications(notifications) {
  const container = document.querySelector("#notifications");
  container.innerHTML = "";

  if (!notifications.length) {
    container.innerHTML = `<p class="field-note">No risk-change alerts yet.</p>`;
    return;
  }

  for (const notification of notifications.slice(0, 5)) {
    const item = document.createElement("article");
    item.className = `notification-item ${notification.read ? "read" : "unread"}`;
    item.innerHTML = `
      <div>
        <strong></strong>
        <p></p>
        <span></span>
      </div>
      ${notification.read ? "" : `<button type="button">Mark read</button>`}
    `;
    item.querySelector("strong").textContent =
        `${notification.origin} to ${notification.destination}`;
    item.querySelector("p").textContent = notification.message;
    item.querySelector("span").textContent =
        `${notification.date} · ${formatNotificationTime(notification.createdAt)}`;
    const button = item.querySelector("button");
    if (button) {
      button.addEventListener("click", () => markNotificationRead(notification.id));
    }
    container.appendChild(item);
  }
}

async function markNotificationRead(id) {
  const response = await fetch(`/api/notifications/${id}/read`, { method: "POST" });
  if (response.ok) {
    await loadNotifications();
    await loadAdminDashboard();
  }
}

function formatNotificationTime(value) {
  return new Date(value).toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit"
  });
}

function renderSavedTrips(trips) {
  const container = document.querySelector("#saved-trips");
  container.innerHTML = "";

  if (!trips.length) {
    container.innerHTML = `<p class="field-note">No saved trips yet. Run an assessment, then save it here.</p>`;
    return;
  }

  for (const trip of trips) {
    const item = document.createElement("article");
    item.className = "saved-trip";

    const body = document.createElement("button");
    body.type = "button";
    body.className = "saved-trip-main";
    body.innerHTML = `
      <strong></strong>
      <span></span>
      <p></p>
    `;
    body.querySelector("strong").textContent = `${trip.origin} to ${trip.destination}`;
    body.querySelector("span").textContent =
        `${trip.date} · ${tripTypeLabel(trip.mode)} · ${trip.riskLevel || "Unscored"}${trip.riskPoints !== null && trip.riskPoints !== undefined ? ` (${trip.riskPoints} pts)` : ""}`;
    body.querySelector("p").textContent = trip.summary || "Saved assessment snapshot";
    body.addEventListener("click", () => loadSavedTripIntoDashboard(trip));

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "saved-trip-delete";
    remove.textContent = "Delete";
    remove.addEventListener("click", () => deleteSavedTrip(trip.id));

    item.append(body, remove);
    container.appendChild(item);
  }
}

async function deleteSavedTrip(id) {
    const response = await fetch(`/api/trips/${id}`, { method: "DELETE" });
  if (response.ok) {
    await loadSavedTrips();
    await loadNotifications();
    await loadAdminDashboard();
  }
}

function loadSavedTripIntoDashboard(trip) {
  const assessment = trip.assessment;
  if (!assessment) return;

  form.elements.origin.value = trip.origin;
  form.elements.destination.value = trip.destination;
  form.elements.date.value = trip.date;
  form.elements.mode.value = trip.mode;
  form.elements.origin.dispatchEvent(new Event("change", { bubbles: true }));
  form.elements.destination.dispatchEvent(new Event("change", { bubbles: true }));
  form.elements.mode.dispatchEvent(new Event("change", { bubbles: true }));
  form.elements.originAirport.value = trip.originAirport || "";
  form.elements.destinationAirport.value = trip.destinationAirport || "";

  latestAssessment = assessment;
  renderResults(assessment);
  setState("results");
  syncSaveTripButton();
}

function flashButton(selector, label) {
  const button = document.querySelector(selector);
  const original = button.textContent;
  button.textContent = label;
  setTimeout(() => {
    button.textContent = original;
  }, 1400);
}
