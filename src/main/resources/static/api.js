async function readJsonResponse(response, fallbackMessage) {
  const contentType = response.headers.get("content-type") || "";
  const body = await response.text();

  if (!contentType.includes("application/json")) {
    const htmlResponse = body.trim().startsWith("<");
    if (response.redirected || response.url.includes("/login") || htmlResponse) {
      throw new Error("We could not load this section. Your session may have expired, or the server is still restarting. Refresh the page and sign in again if needed.");
    }
    throw new Error(`${fallbackMessage}. The server returned an unexpected response. Please refresh and try again.`);
  }

  try {
    return body ? JSON.parse(body) : {};
  } catch {
    throw new Error(`${fallbackMessage}. The server response could not be read. Please refresh and try again.`);
  }
}
