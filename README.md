# Cricket Scoring Backend

The Kotlin/Ktor + PostgreSQL backend for a cricket scoring app. It currently provides:

- **Sign-in** with Google or with a WhatsApp one-time code. Both return a JWT access token.
- **Health check** at `GET /health`.
- **Database schema** for players, teams, tournaments, fixtures, matches and ball-by-ball events, managed by Flyway. API routes for this data are not built yet.

## Prerequisites

- **JDK 17+.** The build uses a Kotlin JVM toolchain of 17. The Gradle daemon is configured for JDK 21 and Gradle can provision it automatically.
- **Docker** for the local PostgreSQL container and for the tests (Testcontainers).
- No global Gradle install is needed. Use the included `./gradlew`.

## Quickstart

1. Start PostgreSQL. It listens on host port **5433**, with database `cricket_scoring` and user/password `cricket`/`cricket`.

   ```bash
   docker compose up -d postgres
   ```

2. Run the server. Flyway applies migrations at start-up.

   ```bash
   ./gradlew run
   ```

3. Check it.

   ```bash
   curl -s http://localhost:8080/health
   ```

   Expected: `{"status":"up","database":"up"}`

4. Try WhatsApp OTP sign-in in console mode (the default). The code is printed in the server log instead of being sent.

   ```bash
   curl -s -X POST http://localhost:8080/auth/whatsapp/otp/request -H 'Content-Type: application/json' -d '{"phone":"+919876543210"}'
   ```

   Look for `OTP for +919876543210 is NNNNNN` in the server output, then:

   ```bash
   curl -s -X POST http://localhost:8080/auth/whatsapp/otp/verify -H 'Content-Type: application/json' -d '{"phone":"+919876543210","code":"NNNNNN","challengeId":"<challengeId from the request response>"}'
   ```

## Configuration

Every setting comes from an environment variable. The defaults work for local development. For local work, `cp .env.example .env`: `docker compose` and `./gradlew run` both read `.env` (real env vars win). `.env` is git-ignored.

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` | unset | Takes precedence over `DB_HOST`/`DB_PORT`/`DB_NAME`. Accepts `jdbc:postgresql://...` or `postgres://user:pass@host:port/db?params` (query params such as `sslmode` are kept) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5433` / `cricket_scoring` | Used when `DATABASE_URL` is unset |
| `DB_USER` | `cricket` | Falls back to `DATABASE_USER`. Ignored if `DATABASE_URL` carries credentials |
| `DB_PASSWORD` | `cricket` | Secret. Falls back to `DATABASE_PASSWORD` |
| `JWT_SECRET` | `dev-only-change-me` | **Secret. Must be set in production.** |
| `OTP_PEPPER` | `dev-only-otp-pepper` | **Secret.** HMAC key for stored OTP hashes. The server refuses to start in `cloud` mode without it |
| `JWT_EXPIRY_SECONDS` | `604800` (7 days) | |
| `GOOGLE_CLIENT_ID` | unset | Without it, `/auth/google` returns 503 |
| `WHATSAPP_OTP_MODE` | `console` | `console` logs codes. `cloud` sends them through the WhatsApp Cloud API |
| `WHATSAPP_ACCESS_TOKEN` | unset | Secret. Required in `cloud` mode (the server will not start without it) |
| `WHATSAPP_PHONE_NUMBER_ID` | unset | Required in `cloud` mode |
| `WHATSAPP_OTP_TEMPLATE_NAME` | `login_otp` | Approved AUTHENTICATION-category template |
| `WHATSAPP_TEMPLATE_LANGUAGE` | `en` | Template language code |
| `WHATSAPP_TEMPLATE_HAS_BUTTON` | `true` | Also send the code as the copy-code / one-tap button parameter. Set `false` only for a template without a button |
| `WHATSAPP_GRAPH_VERSION` | `v21.0` | Meta Graph API version |
| `OTP_TTL_SECONDS` | `300` | |
| `OTP_RESEND_COOLDOWN_SECONDS` | `60` | |
| `OTP_MAX_ATTEMPTS` | `3` | Wrong guesses per code |
| `OTP_MAX_SENDS_PER_WINDOW` | `3` | Codes per phone per window |
| `OTP_WINDOW_SECONDS` | `600` | |
| `OTP_MAX_SENDS_PER_DAY` | `10` | Codes per phone per 24 h |

The server always listens on `0.0.0.0:8080`.

## Tests

```bash
./gradlew test
```

The tests start a throwaway `postgres:18-alpine` container through Testcontainers. If Docker isn't running, they are **skipped**, not failed.

## Deployment

- **CI** (`.github/workflows/ci.yml`) runs `./gradlew test` on pull requests and on pushes to `main`/`master`.
- **Deploy** (`.github/workflows/deploy.yml`) builds the Docker image on every push to `main`. It deploys to Fly.io (`fly.toml`, app `cricket-scoring-backend`, region `bom`) only when the `FLY_API_TOKEN` repository secret is set. A manual run defaults to `rehearsal` (build only).
- Set the runtime secrets on Fly, for example:

  ```bash
  fly secrets set DATABASE_URL=... JWT_SECRET=... GOOGLE_CLIENT_ID=...
  ```

## Documentation

| File | Contents |
|---|---|
| [AGENTS.md](AGENTS.md) | Rules and commands for AI coding agents |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Components, data model, deployment diagrams |
| [DATA_FLOW.md](DATA_FLOW.md) | Start-up, health and sign-in flows step by step |
| [API.md](API.md) | Endpoints, payloads, error codes |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Local dev, testing with fakes, migrations |
| [PROMPTS.md](PROMPTS.md) | Message templates (no LLM prompts in this project) |
| [MCP.md](MCP.md) | MCP status (not used) |
