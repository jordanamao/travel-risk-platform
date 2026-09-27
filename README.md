# Travel Risk Platform

Spring Boot version of the travel disruption risk app. It serves the existing dashboard UI and exposes the same `/api/analyze` contract from a Java backend.

## Run Locally

```bash
mvn spring-boot:run
```

Then open `http://localhost:8080`.

## Optional AI Summary

The platform works without an OpenAI key by using a local summary fallback. To enable OpenAI-generated summaries:

```bash
export OPENAI_API_KEY=your_key_here
export OPENAI_MODEL=gpt-6-astra
```

## Authentication

The dashboard is protected by Spring Security. For local demos, sign in with `employee` / `travel-risk-demo`, or override the credentials:

```bash
export TRAVEL_RISK_USERNAME=your-user
export TRAVEL_RISK_PASSWORD=your-password
export TRAVEL_RISK_JWT_SECRET=replace-with-at-least-32-characters
```

API clients can request a JWT:

```bash
curl -X POST http://localhost:8080/api/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username":"employee","password":"travel-risk-demo"}'
```

Then call protected endpoints with `Authorization: Bearer <token>`.

OAuth2 login is supported through Google. Create a Google OAuth client for a web application and add this redirect URI:

```text
http://localhost:8080/login/oauth2/code/google
```

Then create a local `.env` file:

```properties
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
```

The `.env` file is ignored by Git. Start the app with:

```bash
mvn spring-boot:run
```

The login page will use `/oauth2/authorization/google` for Google sign-in.

## Data Sources

- OpenStreetMap Nominatim for geocoding
- Open-Meteo for forecasts
- National Weather Service for official alerts and forecasts
- Aviation Weather Center for METAR airport observations
