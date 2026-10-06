// "What should I do instead?": re-scored options for a Medium or High result.
let alternativesRequest = 0;

async function loadAlternatives(data) {
  const section = document.querySelector("#alternatives");
  if (!section) return;
  const request = ++alternativesRequest;
  if (!["High", "Medium"].includes(data.score.level)) {
    section.classList.add("hidden");
    return;
  }

  section.className = `alternatives ${data.score.level.toLowerCase()} loading`;
  document.querySelector("#alternatives-title").textContent = "Re-scoring safer options...";
  document.querySelector("#alternatives-meta").textContent = "";
  document.querySelector("#alternatives-note").textContent = "";
  document.querySelector("#alternatives-list").innerHTML = `
    <div class="alternative-skeleton"></div>
    <div class="alternative-skeleton"></div>
  `;

  const { origin, destination, date, mode, originAirport, destinationAirport } = data.input;
  const params = new URLSearchParams({ origin, destination, date, mode, originAirport: originAirport || "", destinationAirport: destinationAirport || "" });
  try {
    const response = await fetch(`/api/analyze/alternatives?${params}`);
    const result = await readJsonResponse(response, "Unable to check alternatives");
    if (!response.ok) throw new Error(result.error || "Unable to check alternatives");
    if (request !== alternativesRequest) return;
    renderAlternatives(result);
  } catch (error) {
    if (request !== alternativesRequest) return;
    // A saved trip in the past or a source outage just leaves this section out.
    section.classList.add("hidden");
  }
}

function renderAlternatives(result) {
  const section = document.querySelector("#alternatives");
  const list = document.querySelector("#alternatives-list");
  const count = result.options.length;
  section.classList.remove("loading");
  document.querySelector("#alternatives-title").textContent = count
      ? `${count} safer option${count === 1 ? "" : "s"} found`
      : "No safer option found";
  document.querySelector("#alternatives-meta").textContent =
      `Your plan: ${result.current.level} · ${result.current.points} pts · ${result.checked} options re-scored`;
  document.querySelector("#alternatives-note").textContent = result.note || "";

  list.innerHTML = "";
  if (!count) {
    const empty = document.createElement("p");
    empty.className = "alternatives-empty";
    empty.textContent = result.checked
        ? "A day earlier or later, other times of day, and nearby airports all score the same or worse. The risk comes from conditions that a change of plan would not avoid, so build in extra time instead."
        : "Live forecast data for nearby dates and times could not be reached, so no alternatives were scored. Try again shortly.";
    list.appendChild(empty);
    return;
  }

  result.options.forEach((option, index) => list.appendChild(alternativeCard(option, index === 0)));
}

function alternativeCard(option, best) {
  const level = option.score.level.toLowerCase();
  const card = document.createElement("article");
  card.className = `alternative-card ${level}`;
  card.innerHTML = `
    <div class="alternative-main">
      <div class="alternative-heading">
        <span class="alternative-icon" aria-hidden="true"></span>
        <div>
          <strong></strong>
          <span class="alternative-when"></span>
        </div>
      </div>
      <ul class="alternative-reasons"></ul>
    </div>
    <div class="alternative-side">
      <div class="alternative-score">
        <span class="alternative-level"></span>
        <span class="alternative-points"></span>
      </div>
      <span class="alternative-saved"></span>
    </div>
  `;
  card.querySelector(".alternative-icon").innerHTML = alternativeIcon(option.kind);
  card.querySelector(".alternative-heading strong").textContent = option.title;
  card.querySelector(".alternative-when").textContent = option.when;
  const reasons = card.querySelector(".alternative-reasons");
  for (const reason of option.reasons) {
    const item = document.createElement("li");
    item.textContent = reason;
    reasons.appendChild(item);
  }
  if (best) {
    const badge = document.createElement("span");
    badge.className = "alternative-best";
    badge.textContent = "Best option";
    card.querySelector(".alternative-heading div").prepend(badge);
  }
  const levelBadge = card.querySelector(".alternative-level");
  levelBadge.className = `alternative-level ${level}`;
  levelBadge.textContent = `${option.score.level} risk`;
  card.querySelector(".alternative-points").textContent = `${option.score.points} pts`;
  card.querySelector(".alternative-saved").textContent = `${option.pointsSaved} fewer points than your plan`;

  if (option.checkable) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Check this option";
    button.addEventListener("click", () => checkAlternative(option.input));
    card.querySelector(".alternative-side").appendChild(button);
  }
  return card;
}

function alternativeIcon(kind) {
  const paths = {
    airport: '<path d="M2.5 19h19"/><path d="m3 13 3.4-1 3.1 2.1 8.4-4.6a2 2 0 0 1 2.7.8 1 1 0 0 1-.4 1.3L9.5 17 3 13Z"/><path d="m8.5 8.5 5.2 1.6"/>',
    time: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    date: '<rect x="3.5" y="5" width="17" height="15" rx="2"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
    combined: '<path d="M12 3 4.5 6v5.5c0 4.6 3.2 8.2 7.5 9.5 4.3-1.3 7.5-4.9 7.5-9.5V6L12 3Z"/><path d="m8.8 12.2 2.2 2.2 4.4-4.6"/>'
  };
  const key = kind === "combined" ? "combined" : kind.endsWith("airport") ? "airport" : kind === "time-of-day" ? "time" : "date";
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${paths[key]}</svg>`;
}

// Loads the option into the form and runs the full check, so the user sees its complete evidence.
function checkAlternative(input) {
  form.elements.date.value = input.date;
  form.elements.originAirport.value = input.originAirport || "";
  form.elements.destinationAirport.value = input.destinationAirport || "";
  window.scrollTo({ top: 0, behavior: "smooth" });
  form.requestSubmit();
}
