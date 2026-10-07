const profileForm = document.querySelector("#profile-form");
const profileStatus = document.querySelector("#profile-status");
let frequentRoutes = [];
let suggestedRoutes = [];
const MAX_FREQUENT_ROUTES = 5;

document.querySelector("#profile-cities").append(...locationOptions.map((city) => {
  const option = document.createElement("option");
  option.value = city;
  return option;
}));

profileForm.addEventListener("submit", saveProfile);
document.querySelector("#add-route").addEventListener("click", addRouteFromInputs);
loadProfile();

async function loadProfile() {
  try {
    const response = await fetch("/api/profile");
    const profile = await readJsonResponse(response, "Unable to load your profile");
    if (!response.ok) throw new Error(profile.error || "Unable to load your profile");
    renderProfile(profile);
  } catch (error) {
    showStatus(error.message, "error");
  }
}

function renderProfile(profile) {
  document.querySelector("#profile-name").textContent = profile.displayName || profile.username;
  document.querySelector("#profile-account").textContent =
      [profile.email, profile.displayName ? profile.username : null].filter(Boolean).join(" · ") || "Signed in";

  profileForm.elements.homeCity.value = profile.homeCity || "";
  profileForm.elements.preferredMode.value = profile.preferredMode === "driving" ? "drive" : (profile.preferredMode || "");
  renderToleranceOptions(profile.toleranceOptions || [], profile.riskTolerance);

  frequentRoutes = (profile.frequentRoutes || []).map((route) => ({ ...route }));
  suggestedRoutes = profile.riskMemory?.suggestedRoutes || [];
  renderRouteList();

  const notifications = profile.notifications || {};
  profileForm.elements.alertEmail.checked = notifications.email !== false;
  profileForm.elements.alertSlack.checked = notifications.slack !== false;
  profileForm.elements.alertMinLevel.value = notifications.minLevel || "any";
  const channels = profile.alertChannels || [];
  document.querySelector("#email-note").textContent = channels.includes("email")
      ? (profile.email ? `to ${profile.email}` : "to the team inbox")
      : "(not set up on this server)";
  document.querySelector("#slack-note").textContent = channels.includes("Slack")
      ? "to the team channel"
      : "(not set up on this server)";

  renderMemory(profile.riskMemory);
}

function renderToleranceOptions(options, selected) {
  const container = document.querySelector("#tolerance-options");
  container.innerHTML = "";
  for (const option of options) {
    const label = document.createElement("label");
    label.className = `tolerance-option ${option.id}`;
    label.innerHTML = `
      <input type="radio" name="riskTolerance" />
      <span class="tolerance-name"></span>
      <span class="tolerance-line"></span>
      <span class="tolerance-description"></span>
    `;
    const input = label.querySelector("input");
    input.value = option.id;
    input.checked = option.id === selected;
    label.querySelector(".tolerance-name").textContent = option.label;
    label.querySelector(".tolerance-line").textContent = `Flags from ${option.flagAt} points`;
    label.querySelector(".tolerance-description").textContent = option.description;
    container.appendChild(label);
  }
}

function renderRouteList() {
  const list = document.querySelector("#frequent-route-list");
  list.innerHTML = "";
  if (!frequentRoutes.length) {
    list.innerHTML = `<li class="field-note">No pinned routes yet.</li>`;
  }
  frequentRoutes.forEach((route, index) => {
    const item = document.createElement("li");
    item.innerHTML = `<span><strong></strong><small></small></span><button type="button" class="link-button">Remove</button>`;
    item.querySelector("strong").textContent = `${route.origin} to ${route.destination}`;
    item.querySelector("small").textContent = modeLabel(route.mode);
    item.querySelector("button").addEventListener("click", () => {
      frequentRoutes.splice(index, 1);
      renderRouteList();
    });
    list.appendChild(item);
  });

  const addButton = document.querySelector("#add-route");
  addButton.disabled = frequentRoutes.length >= MAX_FREQUENT_ROUTES;
  renderSuggestions();
}

function renderSuggestions() {
  const container = document.querySelector("#route-suggestions");
  const pinned = new Set(frequentRoutes.map(routeKey));
  const open = suggestedRoutes.filter((route) => !pinned.has(routeKey(route)));
  container.innerHTML = "";
  container.classList.toggle("hidden", !open.length || frequentRoutes.length >= MAX_FREQUENT_ROUTES);
  if (!open.length) return;

  const label = document.createElement("span");
  label.textContent = "You check these often:";
  container.appendChild(label);
  for (const route of open) {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "frequent-route-chip suggestion";
    chip.textContent = `+ ${route.origin} to ${route.destination}`;
    chip.addEventListener("click", () => {
      frequentRoutes.push({ ...route });
      renderRouteList();
    });
    container.appendChild(chip);
  }
}

function addRouteFromInputs() {
  const origin = document.querySelector("#route-origin");
  const destination = document.querySelector("#route-destination");
  const mode = document.querySelector("#route-mode");
  if (!origin.value.trim() || !destination.value.trim()) {
    showStatus("Enter an origin and a destination for the route.", "error");
    return;
  }
  if (origin.value.trim().toLowerCase() === destination.value.trim().toLowerCase()) {
    showStatus("A route's origin and destination must be different.", "error");
    return;
  }
  frequentRoutes.push({ origin: origin.value.trim(), destination: destination.value.trim(), mode: mode.value });
  origin.value = "";
  destination.value = "";
  showStatus("");
  renderRouteList();
}

function renderMemory(memory) {
  const summary = document.querySelector("#memory-summary");
  const body = document.querySelector("#memory-routes");
  body.innerHTML = "";
  if (!memory || !memory.checks) {
    summary.textContent = "No risk checks yet. Each check you run is remembered here and shapes your future results.";
    body.innerHTML = `<tr><td colspan="4">Nothing to remember yet.</td></tr>`;
    return;
  }
  summary.textContent =
      `${memory.checks} ${memory.checks === 1 ? "check" : "checks"} and ${memory.savedTrips} saved ${memory.savedTrips === 1 ? "trip" : "trips"} in the last ${memory.windowDays} days. Routes that were Medium or High for you are flagged when you check them again.`;
  for (const route of memory.routes || []) {
    const row = document.createElement("tr");
    row.innerHTML = `<td></td><td></td><td></td><td></td>`;
    const cells = row.querySelectorAll("td");
    cells[0].textContent = `${route.origin} to ${route.destination}`;
    cells[1].textContent = route.checks;
    cells[2].innerHTML = route.riskyChecks
        ? `<span class="memory-risky"></span>`
        : `<span class="memory-clean">None</span>`;
    if (route.riskyChecks) {
      cells[2].querySelector("span").textContent =
          `${route.riskyChecks} (last ${route.lastRiskyLevel}, ${route.lastRiskyDate})`;
    }
    cells[3].innerHTML = `<span class="admin-risk"></span>`;
    const pill = cells[3].querySelector("span");
    pill.textContent = `${route.lastLevel || "Unscored"} · ${route.lastDate}`;
    pill.classList.add((route.lastLevel || "unscored").toLowerCase());
    body.appendChild(row);
  }
}

async function saveProfile(event) {
  event.preventDefault();
  const button = document.querySelector("#save-profile");
  button.disabled = true;
  showStatus("Saving...");
  const body = {
    homeCity: profileForm.elements.homeCity.value,
    preferredMode: profileForm.elements.preferredMode.value,
    riskTolerance: profileForm.querySelector('input[name="riskTolerance"]:checked')?.value || "balanced",
    frequentRoutes,
    notifications: {
      email: profileForm.elements.alertEmail.checked,
      slack: profileForm.elements.alertSlack.checked,
      minLevel: profileForm.elements.alertMinLevel.value || "any"
    }
  };
  try {
    const response = await fetch("/api/profile", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    const profile = await readJsonResponse(response, "Unable to save your profile");
    if (!response.ok) throw new Error(profile.error || "Unable to save your profile");
    renderProfile(profile);
    showStatus("Saved. Your next risk check uses these settings.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    button.disabled = false;
  }
}

function showStatus(message, kind = "") {
  profileStatus.textContent = message;
  profileStatus.className = `profile-status ${kind}`;
}

function routeKey(route) {
  return [route.origin, route.destination].map((value) => value.trim().toLowerCase()).sort().join("|");
}

function modeLabel(mode) {
  if (mode === "drive" || mode === "driving") return "Driving";
  if (mode === "general") return "Business";
  return "Flight";
}
