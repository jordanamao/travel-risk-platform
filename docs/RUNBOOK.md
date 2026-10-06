# Runbook

What to do when something goes wrong with the Travel Risk Platform in production
(`https://travel-risk-platform.onrender.com`, a Render Docker web service backed by Render Postgres).

## Where to look first

| Question | Where |
| --- | --- |
| Is the app up? | `GET /health` returns `{"status":"UP"}`. Render uses this as its health check and restarts the service if it fails. |
| Which data sources are failing right now? | `GET /api/admin/source-health` (admin sign-in). Shows each source's last status (`ok`, `degraded`, `unavailable`, `not_configured`), last call time in ms, and how many checks in a row it has failed. |
| Which API calls are slow or failing? | Admin dashboard on the main page (monitoring section), or the Render **Logs** tab. |
| Did an alert fire? | Render **Logs**, search for `ALERT`. If `ALERT_WEBHOOK_URL` is set, the same alert is in that Slack channel. |

## Alerts

Alerts are always written to the application log on one line, so any log search or log drain can match them:

```text
ERROR ... ALERT key=source-down:FAA NAS Status API message="FAA NAS Status API has been unavailable for 2 checks in a row. ..."
WARN  ... ALERT_RESOLVED key=source-down:FAA NAS Status API message="FAA NAS Status API is responding again."
```

| Alert key | Fires when | Resolves when |
| --- | --- | --- |
| `source-down:<source>` | A source failed every call in `ALERT_FAILURE_THRESHOLD` trip checks in a row (default 2). | The next check where it answers. |
| `source-slow:<source>` | A source took at least `ALERT_SLOW_SOURCE_MS` (default 5000 ms) in one check. | The next check under the threshold. |

An open alert repeats at most once per `ALERT_COOLDOWN` (default 15 minutes), so a flapping source can't flood the channel.

Other log lines worth searching for:

- `source_unavailable` / `source_degraded`: every check where a source failed, before the alert threshold is reached.
- `slow_api_call`: an endpoint or service method over `SLOW_API_THRESHOLD_MS` (default 1500 ms).
- `api_call_failed`: an endpoint threw an error.
- `alert_webhook_failed`: the webhook could not be reached. The alert is still in the log.

**Turning on Slack (or email) alerts:** create a Slack incoming webhook and set `ALERT_WEBHOOK_URL` in Render. Alerts are posted as
`{"text": "..."}`, which Slack accepts as is. For email, point it at any webhook-to-email relay (for example a Zapier or Make
webhook) that accepts that body. With no URL set, alerts are log-only.

## When a data source is down

A trip check never fails because one source is down. Each source's calls are caught, the source is marked `unavailable`
in the response's `dataSources` list, its evidence card shows "No response", the result's confidence drops to `low`, and
the Limits text names what was not reachable. Degraded results are **not cached**, so the next check retries the source
instead of serving stale gaps for the cache lifetime.

| Source | Used for | When it's down | What to do |
| --- | --- | --- | --- |
| OpenStreetMap Nominatim | Geocoding cities that are not in the built-in list of 20 US cities | Built-in cities still work. Other places can't be located, so that check returns an error (a `429` from Nominatim tells the user to try a listed city). | Nothing to fix on our side. Wait it out; suggest a listed city. |
| Open-Meteo | Origin, destination and midpoint forecasts | No forecast or wind signals. Score can read lower than reality. | Check `https://status.open-meteo.com`. Users should recheck later. |
| National Weather Service | Official alerts and point forecasts | No official-alert signals. | Check `https://api.weather.gov/alerts/active` in a browser. NWS often returns 5xx briefly; wait. |
| Aviation Weather Center | METAR airport weather | No airport weather signals for flights. | Check `https://aviationweather.gov/api/data/metar?ids=KJFK&format=json`. |
| FAA NAS Status | Ground stops, delay programs, closures | No FAA signals. | Check `https://nasstatus.faa.gov`. Tell travelers to check their airline. |
| Road511 | Road closures for driving trips | No road signals. Shows `not_configured` instead if `ROAD511_API_KEY` is not set. | Check the key is set and valid; check `https://www.road511.com`. |
| OpenAI Responses API | Optional written summary | The built-in summary is used instead; the score is unaffected. Shows `not_configured` if `OPENAI_API_KEY` is not set. | Check the key, quota and `https://status.openai.com`. |

Timeouts: every outbound call uses `HTTP_CONNECT_TIMEOUT_MS` (default 5000) and `HTTP_READ_TIMEOUT_MS` (default 10000),
so one hung source can't hold a check open indefinitely.

## When the whole app is down

1. Open the service in the Render dashboard and check **Events** for a failed deploy or a failed health check.
2. Read the **Logs** for the first error after startup. Common causes:
   - `travel-risk.jwt.secret must be at least 32 bytes`: `TRAVEL_RISK_JWT_SECRET` is missing or short.
   - Flyway or `Schema-validation` errors: a migration did not apply. `JPA_DDL_AUTO=validate` refuses to start on a schema mismatch by design.
   - Postgres connection errors: check the `travel-risk-platform-db` database is available and `DATABASE_URL` is linked.
3. The free plan spins the service down after about 15 minutes idle. The first request after that takes 30 to 60 seconds; this is normal.

## Deploy, redeploy and roll back

Render deploys automatically on every push to `main`. `main` requires the **Maven Tests** and **Docker Build** checks, so only
green pull requests reach production.

- **Redeploy the current version:** Render dashboard, service, **Manual Deploy**, then **Deploy latest commit**. Use this after
  changing environment variables (they apply on restart) or to clear the in-memory cache.
- **Roll back fast:** Render dashboard, **Events**, find the last good deploy and choose **Rollback**. This redeploys that build
  without touching Git. Note that auto-deploy will ship the next push to `main` as usual.
- **Roll back in Git (lasting fix):** open a pull request that reverts the bad merge (`git revert -m 1 <merge-commit>`) and merge
  it once CI is green.
- **Database:** migrations in `src/main/resources/db/migration` only add tables or columns, so rolling the app back does not
  require a database rollback. Never edit an applied migration; add a new one.

## Demo data

Set `DEMO_DATA_ENABLED=true` (or run with the `demo` Spring profile) to load demo data on startup: four employees
(`employee1` to `employee4@email.com`, password `TRAVEL_RISK_PASSWORD`), eight saved trips across Low, Medium and High, check
history, and one unread "risk went from Medium to High" alert for `employee1@email.com`.

- Trips are dated relative to the day the app starts, so they always fall inside the 15-day forecast window.
- On every start the seeder removes only the rows it created earlier and reloads them. Trips employees saved themselves, and
  demo trips someone re-checked with live data, are kept.
- It is off by default and never runs in tests unless a test turns it on.
- To clear demo data from production, set `DEMO_DATA_ENABLED=false`, redeploy, and use the admin dashboard's **Clear history** button,
  or delete the employees' saved trips from the app.

## Environment variables

Required in production:

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | Render Postgres connection string (linked from `travel-risk-platform-db`). Converted to JDBC settings by `docker-entrypoint.sh`. |
| `TRAVEL_RISK_JWT_SECRET` | Signs API tokens. At least 32 characters. Render generates one. |
| `JPA_DDL_AUTO=validate` | Lets Flyway own the schema and fails fast on drift. |
| `SPRING_CACHE_TYPE=simple` | In-memory cache. Use `redis` with `REDIS_HOST`/`REDIS_PORT` if a Redis instance is added. |

Sign-in:

| Variable | Default | Purpose |
| --- | --- | --- |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | empty | Google sign-in. |
| `TRAVEL_RISK_ADMIN_USERNAME`, `TRAVEL_RISK_ADMIN_PASSWORD` | empty | Admin account. Both required for the admin dashboard. |
| `TRAVEL_RISK_EMPLOYEE_USERNAME_PATTERN` | `employee[0-9]+@email\.com` | Which usernames can sign in as employees. |
| `TRAVEL_RISK_PASSWORD` | `travel-risk-demo` | Shared employee password for the demo. |

Data sources:

| Variable | Default | Purpose |
| --- | --- | --- |
| `ROAD511_API_KEY` | empty | Enables road closure checks. |
| `OPENAI_API_KEY`, `OPENAI_MODEL` | empty, `gpt-6-astra` | Optional AI-written summary. |
| `HTTP_CONNECT_TIMEOUT_MS`, `HTTP_READ_TIMEOUT_MS` | `5000`, `10000` | Outbound call timeouts. |

Monitoring and alerts:

| Variable | Default | Purpose |
| --- | --- | --- |
| `ALERT_WEBHOOK_URL` | empty (log only) | Slack incoming webhook (or compatible) for alerts. |
| `ALERT_FAILURE_THRESHOLD` | `2` | Failed checks in a row before `source-down` fires. |
| `ALERT_SLOW_SOURCE_MS` | `5000` | Source call time that fires `source-slow`. |
| `ALERT_COOLDOWN` | `15m` | Minimum gap between repeats of the same open alert. |
| `SLOW_API_THRESHOLD_MS` | `1500` | Logs `slow_api_call` and counts slow calls on the dashboard. |

Demo and limits:

| Variable | Default | Purpose |
| --- | --- | --- |
| `DEMO_DATA_ENABLED` | `false` | Load demo data on startup (see above). |
| `RATE_LIMIT_ENABLED` and `RATE_LIMIT_*` | on, 30 checks and 10 token requests per minute | Per-user rate limits. See the README. |
