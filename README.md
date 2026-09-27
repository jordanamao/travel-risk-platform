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

## Data Sources

- OpenStreetMap Nominatim for geocoding
- Open-Meteo for forecasts
- National Weather Service for official alerts and forecasts
- Aviation Weather Center for METAR airport observations
