setupLocationComboboxes();
setupAirportPreferences();
setupRouteSwapToggle();
setupResultActions();
setupSavedTrips();
setupAdminDashboard();
setState("empty");

form.addEventListener("submit", async (event) => {
  event.preventDefault();

  const params = new URLSearchParams(new FormData(form));
  setState("loading");

  try {
    const response = await fetch(`/api/analyze?${params}`);
    const data = await readJsonResponse(response, "Unable to analyze trip");
    if (!response.ok) {
      throw new Error(data.error || "Unable to analyze trip");
    }
    latestAssessment = data;
    renderResults(data);
    setState("results");
    syncSaveTripButton();
    compareDates({ automatic: true });
  } catch (error) {
    errorBox.textContent =
        error.message === "Failed to fetch"
            ? "Could not reach the local Spring Boot server. Refresh http://localhost:8080 and try again."
            : error.message;
    setState("error");
  }
});
