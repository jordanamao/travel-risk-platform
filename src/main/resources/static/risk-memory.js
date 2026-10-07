// "For you": the employee's own risk memory and tolerance, shown beside the company result,
// plus profile preferences applied to the trip form (home city, trip type, frequent routes).

let profilePreferences = null;

function renderPersonalResult(data) {
  const container = document.querySelector("#personal-result");
  if (!container) return;
  const personal = data.personal;
  if (!personal) {
    container.classList.add("hidden");
    container.innerHTML = "";
    return;
  }

  container.className = `personal-result ${personal.status}`;
  container.innerHTML = `
    <div class="personal-result-top">
      <p class="policy-result-label">For you</p>
      <a class="personal-result-edit" href="/profile">Edit profile</a>
    </div>
    <div class="personal-result-body">
      <strong class="personal-badge"></strong>
      <div>
        <p class="personal-detail"></p>
        <ul></ul>
      </div>
    </div>
  `;
  container.querySelector(".personal-badge").textContent = personal.headline;
  container.querySelector(".personal-detail").textContent = personal.detail;
  const list = container.querySelector("ul");
  for (const note of personal.notes || []) {
    const item = document.createElement("li");
    item.textContent = note;
    list.appendChild(item);
  }
}

async function setupProfilePreferences() {
  try {
    const response = await fetch("/api/profile");
    const profile = await readJsonResponse(response, "Unable to load your profile");
    if (!response.ok) return;
    profilePreferences = profile;
  } catch {
    return;
  }
  applyHomePreferences(profilePreferences);
  renderFrequentRoutes(profilePreferences.frequentRoutes || []);
}

// Only fills the form while it still shows the built-in defaults, so nothing the user typed is lost.
function applyHomePreferences(profile) {
  if (latestAssessment) return;
  const origin = form.elements.origin;
  if (profile.homeCity && origin.value === origin.defaultValue) {
    origin.value = profile.homeCity;
    if (form.elements.destination.value === profile.homeCity) {
      form.elements.destination.value = locationOptions.find((city) => city !== profile.homeCity) || "";
    }
    origin.dispatchEvent(new Event("change", { bubbles: true }));
    form.elements.destination.dispatchEvent(new Event("change", { bubbles: true }));
  }
  const mode = formMode(profile.preferredMode);
  if (mode && form.elements.mode.value === "flight") {
    form.elements.mode.value = mode;
    form.elements.mode.dispatchEvent(new Event("change", { bubbles: true }));
  }
}

function renderFrequentRoutes(routes) {
  const container = document.querySelector("#frequent-routes");
  if (!container) return;
  container.innerHTML = "";
  container.classList.toggle("hidden", !routes.length);
  if (!routes.length) return;

  const label = document.createElement("span");
  label.className = "frequent-routes-label";
  label.textContent = "Your frequent routes";
  container.appendChild(label);

  const list = document.createElement("div");
  list.className = "frequent-routes-list";
  for (const route of routes) {
    const chip = document.createElement("button");
    chip.type = "button";
    chip.className = "frequent-route-chip";
    chip.title = `Check ${route.origin} to ${route.destination} (${tripTypeLabel(formMode(route.mode))})`;
    chip.textContent = `${shortCity(route.origin)} → ${shortCity(route.destination)}`;
    chip.addEventListener("click", () => checkFrequentRoute(route));
    list.appendChild(chip);
  }
  container.appendChild(list);
}

function checkFrequentRoute(route) {
  form.elements.origin.value = route.origin;
  form.elements.destination.value = route.destination;
  form.elements.mode.value = formMode(route.mode) || "flight";
  for (const name of ["origin", "destination", "mode"]) {
    form.elements[name].dispatchEvent(new Event("change", { bubbles: true }));
  }
  form.elements.originAirport.value = "";
  form.elements.destinationAirport.value = "";
  form.requestSubmit();
}

// Saved data says "driving" in places; the form's option is "drive".
function formMode(mode) {
  if (!mode) return "";
  return mode === "driving" ? "drive" : mode;
}

function shortCity(value) {
  return String(value || "").split(",")[0];
}
