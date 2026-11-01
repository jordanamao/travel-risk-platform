---
name: full-stack-app-builder
description: Build a complete, runnable full-stack application from a product idea, using a Java/Spring Boot application base with frontend, SQL persistence, authentication, tests, and deployment configuration. Use for new application builds or substantial application foundations; adapt to the user's chosen stack and domain.
---

# Full-Stack App Builder

Turn the user's product idea into a working application they can run, demonstrate, extend, and explain in an interview. This single file contains the complete workflow; the original Travel Risk Platform repository and prompt archive are not required.

## Establish the application

Use the current request to determine the product name, target users, main workflow, data to save, and roles. If the user supplies only an idea, choose a small coherent first version and state the assumptions. Ask only when an unanswered question materially changes the product or creates an external dependency. A skill supplies the development approach; it cannot supply an unspecified business idea or account credentials.

Inspect an existing repository before editing it and preserve relevant conventions and working features. For a new project, use the following base unless the user selects another stack:

- Java 21 or a suitable supported Java LTS, Maven, and a compatible supported Spring Boot release. Verify dependency compatibility when scaffolding; do not freeze the historical project's dependency versions.
- A responsive HTML/CSS/JavaScript frontend served by Spring Boot, with focused modules for API calls, state, forms, rendering, and user actions. Use React, Angular, or another framework when requested or justified by the interface requirements.
- Spring MVC REST controllers, application services, repositories, JPA entities, validation, and Flyway SQL migrations. Separate API contracts from persistence entities where needed.
- PostgreSQL for production and an easy local database setup. An H2 local profile is acceptable, but verify PostgreSQL-specific migrations and queries against PostgreSQL before claiming compatibility.
- Spring Security for sign-in and server-side access control.
- Docker, a health endpoint, a Maven test command, and GitHub Actions CI.

Create a short implementation plan with concrete acceptance criteria. Implement the application, rather than returning only architecture or scaffolding. Build the main UI-to-API-to-database workflow first, then complete the supporting features below.

## Product behavior and frontend

Map the domain to a complete user journey: sign in, submit valid input, receive a useful result, save or update a record, revisit it, and sign out. Adapt this journey to the requested product instead of copying travel-specific fields.

Provide clear labels, useful defaults, responsive layouts, keyboard-accessible controls, and loading, empty, success, and error states. Group dense results and use collapsible details when useful. Keep implementation terminology out of ordinary user messages. Show actionable source-outage messages when integrations fail. Handle non-JSON HTTP responses without breaking the interface.

Use real backend requests for functional controls. Never present static example values as live results. Identify seeded or simulated data in the interface and documentation.

## Authentication and authorization

Implement username/password sign-in and sign-out for a typical account-based application. Store real account passwords using Spring Security password encoding; keep any demonstration accounts explicitly scoped to a demo profile. Do not reuse the source project's public demo credentials or numbered employee-login rule as production authentication.

Use roles appropriate to the product, such as USER and ADMIN. Derive the current user from the authenticated server context. Enforce ownership on every operation that reads, updates, or deletes a user's records. A separate admin view may expose aggregate or organization-wide data when the product needs it; protect its APIs as well as its UI.

Add JWT authentication when the requested application has API clients or explicitly requires tokens. Configure signature verification, expiration, subject, and authority mapping. Use Spring Security's supported JWT facilities; add a custom once-per-request filter only when its behavior is needed. Never trust unsigned token content.

Add Google OAuth2 when requested. Read client credentials from environment variables and document exact localhost and production callback patterns. Missing optional OAuth configuration must not prevent the base application from starting. Use session CSRF protection where appropriate; if a separate frontend requires CORS, allow its specific origins instead of adding wildcard CORS to every controller.

## Persistence and domain features

Persist the core user-owned records in SQL, including timestamps and ownership. Use versioned Flyway migrations, appropriate constraints and indexes, and server-side validation. Preserve migrations already applied to an existing database; add a new migration for subsequent changes.

Build the domain's useful create/read/update/delete operations and connect them to the frontend. Add history or audit records when users need to review past results; avoid storing unnecessary personal information or credentials.

When meaningful to the product, add persisted change notifications and read/unread state. Define what triggers a notification, how repeated checks avoid duplicates, and whether checks are manual or scheduled. Only offer delivery channels that are actually configured and implemented.

## Integrations, reliability, and performance

Wrap external providers behind focused client/service interfaces. Use bounded timeouts, validate responses, and record source success, failure, freshness, and latency when these affect the result. Distinguish unavailable data from a valid zero/low-risk result. Provide a documented fallback when the product can still function safely with partial data.

Add an administrator monitoring view when external integrations are central to the application. Keep operational details restricted to authorized users.

Cache expensive repeated reads when useful. Start with a local cache if that meets the deployment needs; configure Redis only when requested or justified. Specify keys, TTLs, invalidation, and behavior on provider failure. Include user identity in keys for private data and avoid caching one user's response for another. If exposing cache refresh or eviction endpoints, authorize them and document their behavior. Spring Cache may use `@Cacheable`, `@CachePut`, and `@CacheEvict` where appropriate.

Apply configurable request limits to authentication and expensive endpoints. Return a consistent JSON error and `Retry-After` for rate-limited requests. Explain that in-memory limits and caches are per instance and reset on restart; use a shared mechanism when multiple instances require shared state. Trust forwarded headers only through the intended proxy configuration.

If AI is part of the product, keep its credentials server-side, select the model through configuration, and provide a useful non-AI path when possible. Treat model output as untrusted data and label generated content appropriately. AI summaries do not replace the application's deterministic validation or core calculations.

## Configuration, delivery, and documentation

Provide an `.env.example` with placeholders and a supported way to load local configuration. Ignore `.env`, database files, build output, and local caches. Do not place actual secrets, personal chat transcripts, or account credentials into generated source or documentation. Fail clearly for missing required production configuration without logging secret values.

Create a Docker setup using an appropriate runtime image and a non-root user where supported. Match Java build/runtime versions, use the platform's expected port configuration, and supply a lightweight health endpoint such as `/health`. Give the production database a durable external storage configuration.

Prepare deployment configuration for the user's selected host. Render is a suitable baseline for this Java service when no host is specified, subject to verifying current platform support. Document service settings, database variables, deployed branch, health path, and OAuth callbacks. Prepare deployment files without assuming that a build request authorizes purchases, domain registration, public deployment, or repository publication. Carry out those external actions when the user authorizes them. Do not hardcode the original project's domains, repository visibility, or branch names into future applications.

Configure GitHub Actions to run relevant Maven tests on pushes and pull requests, including feature branches. Keep the README aligned with the implemented behavior: purpose, stack, local start/test commands, environment variables, API examples, roles, database setup, cache behavior, limitations, and deployment instructions. Include actual screenshots of the main views when feasible; do not fabricate screenshots or deployed URLs.

## Verify and hand off

Run the build and meaningful tests for business rules, invalid inputs, authentication, ownership isolation, admin permissions, persistence, and external-provider failures. Verify cache and rate-limit behavior when implemented. Keep automated tests deterministic by stubbing external calls. Exercise the main browser workflow, including failed requests and sign-out, and inspect the layout at desktop and mobile widths when browser tooling is available.

Check that a second user cannot retrieve or modify the first user's records and that an ordinary user cannot call admin endpoints. Check startup both with and without optional integrations. Verify persistence after restart when a persistent database is configured.

Report what is implemented, the exact run/test commands, checks that passed, and anything blocked by missing credentials or infrastructure. Distinguish prepared deployment files from a verified live deployment. Do not claim a complete application while its core workflow is still a mock or a stub.

Give a short interview explanation covering the frontend-to-controller-to-service-to-database flow, the role of authentication, one engineering tradeoff, and how the result was tested. Explain AI assistance honestly without inventing work history or claims of having written every line manually.

## Example invocation

“Use this skill to build an inventory management application for a small business. Users can manage their own products and stock changes; administrators can see overall inventory and low-stock alerts. Use Spring Boot and PostgreSQL. Complete the frontend, backend, database migrations, tests, Docker setup, and deployment instructions.”

Replace the application idea and feature details for each new project. The defaults are a reusable base, not a requirement to add every optional feature to every application.
