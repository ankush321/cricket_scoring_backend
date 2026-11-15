# Development Guide

## Local environment

```bash
cp .env.example .env            # once; .env is git-ignored
docker compose up -d postgres   # Postgres 18 on localhost:5433, data in the cricket_scoring_pg18 volume
./gradlew run                   # server on :8080, Flyway migrates on start (there is no separate flywayMigrate task)
curl -s localhost:8080/health   # {"status":"up","database":"up"}
```

`docker compose` and `./gradlew run` both read `.env`. `POSTGRES_*` values only apply when the volume is first created, so after changing `DB_USER`/`DB_PASSWORD`/`DB_NAME` run `docker compose down -v` (this deletes local data).

Tests use Testcontainers. `build.gradle.kts` pins `api.version=1.44` for the test JVM because Docker Engine 29+ rejects the older API version Testcontainers 1.21.x sends by default; without it the DB tests are silently skipped.

To override settings, export env vars before `./gradlew run` (they win over `.env`), for example:

```bash
JWT_SECRET=local-secret GOOGLE_CLIENT_IDS=xxx.apps.googleusercontent.com ./gradlew run
```

To get a clean database, remove the volume:

```bash
docker compose down -v
```

### Connecting to the local database

```bash
docker compose exec postgres psql -U cricket -d cricket_scoring
```

Flyway records applied migrations in the `flyway_schema_history` table.

### Running the production image locally

```bash
docker build -t cricket-scoring-backend .
docker run --rm -p 8080:8080 -e DATABASE_URL=postgres://cricket:cricket@host.docker.internal:5433/cricket_scoring cricket-scoring-backend
```

## Testing

```bash
./gradlew test                                     # all tests
./gradlew test --tests '*AuthIntegrationTest'      # one class
```

### How the tests are structured

The tests are integration tests. They run against a real PostgreSQL started by Testcontainers (`postgres:18-alpine`) and use Ktor's `testApplication` in-memory engine:

- `DatabaseFoundationTest`: migrations apply and `/health` reports the database as up.
- `AuthIntegrationTest`: Google sign-in and the WhatsApp OTP flows over HTTP, including cooldown, window limit, lockout, expiry, failed delivery and malformed JSON; and sessions: token claims, `/auth/me`, access-token expiry, refresh rotation, reuse detection, retry grace, 30-day idle and 90-day absolute expiry, logout, logout-all, session list and revoke. A controllable clock (`AuthDependencies.clock`) moves time forward.
- `AuthConcurrencyTest`: races against the services from real threads (parallel OTP sends, wrong guesses and correct guesses; parallel refreshes of one refresh token; parallel first Google sign-ins; parallel unlinks).
- `AppleIntegrationTest`: Sign in with Apple over HTTP with tokens signed in the test by a generated RSA key; the real `AppleTokenVerifier` checks them through a stand-in `JwkProvider`. Covers new account and name handling, relay emails, verified-email attach, every token rejection (aud, iss, exp, key, kid, nonce), linking, and the notification events.
- `AccountDeletionTest`: `DELETE /auth/me` over HTTP; checks every table it touches (users tombstone, sessions, identities, OTP history, soft-deleted owned rows, memberships) and that signing in again makes a new account.
- `AppleTokenCryptoTest`: `TokenCipher` (round trip, wrong row, tampering, wrong key) and the ES256 `client_secret`. `FakeAppleTokenClient` records exchange/revoke calls for `AppleIntegrationTest`.
- `AbuseAndRolloutTest`: per-IP send and verify limits, the per-phone wrong-code block, the global budget, the country allowlist, rollout off / allowlist / minimum app version, `/auth/providers`, `auth_events` contents (including that no raw phone is stored), the delivery webhook (handshake, signature, forward-only statuses) and the `/api/v1` paths. Each test starts on fresh fixed windows by moving the clock a few days ahead.
- `MigrationBackfillTest`: migrates a fresh database to V4, inserts old-shape users, migrates to exactly V5 and checks the backfill. Copy this pattern for any migration that moves data. Ktor's in-memory test engine runs blocking handlers one at a time, so races can't be tested over HTTP.
- `OtpHasherTest`, `DatabaseConfigTest`: unit tests, no Docker needed.

Each class starts its container in `@BeforeAll` and calls `assumeTrue(DockerClientFactory...isDockerAvailable)`. **Without Docker, the tests are skipped and the build still passes.** Before trusting a green run, check the report (`build/reports/tests/test/index.html`) to confirm the tests actually ran.

### Mocking external services

This project makes **no LLM calls**, so there is nothing LLM-related to mock. The external calls are Google token verification and the WhatsApp Cloud API. Both sit behind `fun interface` seams, and `Application.module()` accepts injected dependencies:

```kotlin
fun Application.module(
    config: DatabaseConfig = DatabaseConfig.fromEnv(),
    authDependencies: AuthDependencies = AuthDependencies.fromEnv(),
)
```

In a test, build `AuthDependencies` with lambdas, as `AuthIntegrationTest` does:

```kotlin
val config = AuthConfig.fromEnv { null }.copy(   // ignore the machine's env vars
    jwtSecret = "test-secret",
    otpPepper = "test-pepper",
    googleClientIds = listOf("test-client-id"),
)
// FakeGoogle.kt: accepts "valid-google-token" or googleToken(sub, email, name) -> "fake:sub=...;email=..."
val googleVerifier = fakeGoogleVerifier
val lastOtp = AtomicReference<String?>(null)
val otpSender = WhatsAppOtpSender { _, code -> lastOtp.set(code); OtpDelivery("test") }  // capture the code instead of sending it
val now = AtomicLong(1_800_000_000_000L)   // advance it to test cooldowns and expiry

testApplication {
    application {
        module(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password),
               AuthDependencies(config, googleVerifier, otpSender, clock = now::get))
    }
    // ... call /auth/whatsapp/otp/request, read lastOtp, call /verify
}
```

Guidelines:

- Never let a test reach `graph.facebook.com` or Google. Always inject fakes.
- To test expiry, cooldowns and windows, advance the injected clock rather than sleeping. The clock also drives JWT `iat`/`exp` and the access-token verifier, so moving it forward expires access tokens too.
- `module()` closes its Hikari pool on `ApplicationStopped`. Every `testApplication` builds a new pool, so without that a large test class runs Postgres out of connections. `AuthConfig.copy(...)` can still change any limit.
- If you add another external integration (an LLM client included), give it the same shape: a `fun interface`, a `fromConfig` factory for the real one, a field on `AuthDependencies` or a similar holder, and a lambda fake in tests.
- The Docker-backed test classes each start their own container, and every `testApplication` re-runs `module()`, which calls Flyway `migrate()`. That is idempotent, so data persists across tests within a class. Use unique phone numbers and emails per test to avoid cross-test coupling (for example, the OTP cooldown).

### Gaps worth covering with tests

- Google sign-in auto-attaching by verified email to an account that has no Google identity (reachable once Apple sign-in exists).
- Invalid phone formats.
- The 24 h send cap.
- `CloudWhatsAppOtpSender` request body (body + button components) against a mock HTTP engine.

## Database migrations

Flyway runs automatically in `DatabaseFactory.connect()` at every start-up, from `src/main/resources/db/migration`.

Applied so far:

| Version | File | Summary |
|---|---|---|
| V1 | `V1__foundation.sql` | users, players, teams, team_players, rule_profiles, tournaments, tournament_teams, fixtures, matches, match_events, memberships |
| V2 | `V2__auth_oauth.sql` | email/password nullable; adds `phone_e164`, `google_sub`; `users_has_identity` check; `login_otps` table |
| V3 | `V3__otp_challenges.sql` | drops `login_otps`; adds `otp_challenges` (kept as send history, one live row per phone) |
| V4 | `V4__sessions.sql` | adds `sessions` and `refresh_tokens` |
| V5 | `V5__user_identities.sql` | adds `user_identities`, backfills it from `users.google_sub` / `phone_e164`, drops `google_sub`, `password_hash` and the `users_has_identity` check, renames `email` / `phone_e164` to `primary_email` / `primary_phone_e164` |
| V6 | `V6__provider_revoked_sessions.sql` | adds `PROVIDER_REVOKED` to the allowed `sessions.revoked_reason` values |
| V7 | `V7__account_deletion.sql` | adds `users.status` / `deleted_at_epoch_ms`, the `ACCOUNT_DELETED` session reason, and the `apple_tokens` table |
| V8 | `V8__abuse_limits_and_events.sql` | adds `rate_limit_buckets`, `auth_events`, `sessions.is_beta_flow`, `otp_challenges.delivery_status` |

### Adding a migration

1. Create `src/main/resources/db/migration/V9__short_description.sql`: next integer, double underscore.
2. Update the matching `object` in `Tables.kt` so the Exposed mapping agrees with the SQL. Use `jsonb(...)` from `JsonbColumn.kt` for JSONB columns.
3. Follow the existing conventions:
   - timestamps are `BIGINT ..._epoch_ms`
   - domain ids are `TEXT`, user and membership ids are `UUID`
   - syncable tables get `owner_user_id`, `version INTEGER NOT NULL DEFAULT 1`, `deleted_at_epoch_ms`, and an `(owner_user_id, updated_at_epoch_ms)` index
4. Run `./gradlew test`. Testcontainers applies every migration to a fresh database.
5. **Never edit or rename a migration that has been applied anywhere**, Fly included. Flyway checksum validation would fail at start-up. Write a new migration instead.

Migrations run on every machine start. On Fly, with `min_machines_running = 0`, they also run on cold start, so keep them fast and backward compatible with the previous app version.

## Project conventions

- Kotlin official code style (`kotlin.code.style=official`). No formatter or linter task is configured.
- Wrap DB access in Exposed `transaction {}`, with the `org.jetbrains.exposed.v1.*` imports.
- Surface client errors as `AuthException(code, message, HttpStatusCode)` (or a new typed exception registered in `StatusPages`), so responses keep the `{code, message}` shape.
- Secrets only come from env vars. See `AGENTS.md`.

## CI/CD

- `ci.yml`: JDK 17 (Temurin) + `gradle/actions/setup-gradle`, then `./gradlew test --no-daemon`. Docker is available on `ubuntu-latest`, so the Testcontainers tests run in CI.
- `deploy.yml`: `docker build` on push to `main`. It runs `flyctl deploy --remote-only` only when `FLY_API_TOKEN` is set, and for a manual run only when `mode=release` is chosen.

## Housekeeping

Nothing prunes the auth bookkeeping tables yet. Until a scheduled job exists, run this periodically (for example from `fly ssh console` or a cron machine):

```sql
DELETE FROM rate_limit_buckets WHERE window_start_epoch_ms < (extract(epoch FROM now()) - 2 * 86400) * 1000;
DELETE FROM otp_challenges     WHERE created_at_epoch_ms   < (extract(epoch FROM now()) - 30 * 86400) * 1000;
DELETE FROM auth_events        WHERE created_at_epoch_ms   < (extract(epoch FROM now()) - 180 * 86400) * 1000;
```

