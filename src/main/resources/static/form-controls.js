function setupLocationComboboxes() {
  const inputs = document.querySelectorAll("[data-location-input]");

  for (const input of inputs) {
    const wrapper = input.closest(".location-combobox");
    const menu = wrapper.querySelector(".location-menu");
    const toggle = wrapper.querySelector(".location-toggle");
    let activeIndex = -1;

    const closeMenu = () => {
      menu.classList.add("hidden");
      activeIndex = -1;
    };

    const openMenu = (showAll = false) => {
      const query = input.value.trim().toLowerCase();
      const matches = showAll || !query
          ? locationOptions
          : locationOptions.filter((location) => location.toLowerCase().includes(query));

      menu.innerHTML = "";
      const optionsToShow = matches.length ? matches : locationOptions;
      optionsToShow.forEach((location, index) => {
        const option = document.createElement("button");
        option.type = "button";
        option.className = "location-option";
        option.textContent = location;
        option.addEventListener("mousedown", (event) => {
          event.preventDefault();
          input.value = location;
          input.dispatchEvent(new Event("change", { bubbles: true }));
          closeMenu();
        });
        if (index === activeIndex) option.classList.add("active");
        menu.appendChild(option);
      });

      menu.classList.remove("hidden");
    };

    input.addEventListener("focus", () => openMenu(true));
    input.addEventListener("input", () => openMenu(false));
    toggle.addEventListener("click", () => {
      input.focus();
      openMenu(true);
    });

    input.addEventListener("keydown", (event) => {
      const optionCount = menu.querySelectorAll(".location-option").length;
      if (event.key === "Escape") {
        closeMenu();
      } else if (event.key === "ArrowDown") {
        event.preventDefault();
        activeIndex = optionCount ? (activeIndex + 1) % optionCount : -1;
        openMenu(false);
      } else if (event.key === "ArrowUp") {
        event.preventDefault();
        activeIndex = optionCount ? (activeIndex - 1 + optionCount) % optionCount : -1;
        openMenu(false);
      } else if (event.key === "Enter" && activeIndex >= 0) {
        event.preventDefault();
        const option = menu.querySelectorAll(".location-option")[activeIndex];
        if (option) input.value = option.textContent;
        closeMenu();
      }
    });

    document.addEventListener("mousedown", (event) => {
      if (!wrapper.contains(event.target)) closeMenu();
    });
  }
}

function setupAirportPreferences() {
  const modeSelect = form.querySelector('select[name="mode"]');
  const originInput = form.querySelector('input[name="origin"]');
  const destinationInput = form.querySelector('input[name="destination"]');
  const airportPanel = document.querySelector("#airport-preferences");

  const syncVisibility = () => {
    const shouldShow = modeSelect.value !== "drive";
    airportPanel.classList.toggle("hidden", !shouldShow);
  };

  const syncAirports = () => {
    populateAirportSelect("origin", originInput.value);
    populateAirportSelect("destination", destinationInput.value);
  };

  modeSelect.addEventListener("change", syncVisibility);
  originInput.addEventListener("input", syncAirports);
  destinationInput.addEventListener("input", syncAirports);
  originInput.addEventListener("change", syncAirports);
  destinationInput.addEventListener("change", syncAirports);
  syncVisibility();
  syncAirports();
}

function populateAirportSelect(kind, locationValue) {
  const select = document.querySelector(`[data-airport-select="${kind}"]`);
  const options = airportOptionsByCity[locationValue] || [];
  select.innerHTML = "";

  const auto = document.createElement("option");
  auto.value = "";
  auto.textContent = "Auto: nearest airports";
  select.appendChild(auto);

  for (const [value, label] of options) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    select.appendChild(option);
  }
}
