# AGENTS.md

Instructions for AI coding agents working in this repository. Read this before changing code.

## What this project is

`CricketScoringBackend` is a Kotlin/Ktor HTTP server backed by PostgreSQL. It is the server side of a cricket scoring app. Today it implements:

- `GET /health`: checks the database.
- Sign-in with **Google ID tokens**, **Sign in with Apple** and **WhatsApp one-time passcodes (OTP)**. Sign-in starts a **session** and returns a 15-minute HS256 access JWT plus a rotating refresh token.
- Session routes behind Ktor `authenticate("access")`: `/auth/me` (GET, and DELETE for immediate account deletion), `/auth/logout`, `/auth/logout-all`, `/auth/sessions`, `/auth/me/identities`.
- A Flyway schema for the scoring domain (players, teams, rule profiles, tournaments, fixtures, matches, ball-by-ball match events, memberships). **No HTTP routes read or write the domain tables yet.**

This project has **no** LLM calls, prompts, vector search, RAG pipeline, streaming endpoints or MCP server. Don't add docs or code that assume they exist.

## Tech stack

| Area | Choice | Version (from `build.gradle.kts`) |
|---|---|---|
| Language | Kotlin (JVM) | 2.4.20, `jvmToolchain(17)` |
| HTTP server | Ktor on Netty | 3.6.0 |
| Serialization | kotlinx.serialization JSON | 1.9.0 |
| DB access | JetBrains Exposed (`org.jetbrains.exposed.v1.*` packages) | 1.6.1 |
| Database | PostgreSQL | 18 (`postgres:18-alpine` in compose/tests) |
| Pool | HikariCP | 6.3.3 (max pool size 5) |
| Migrations | Flyway | 11.14.1 |
| JWT | `com.auth0:java-jwt` + `ktor-server-auth-jwt` | 4.6.0 / 3.6.0 |
| Apple keys (JWKS) | `com.auth0:jwks-rsa` | 0.24.1 |
| Google tokens | `google-api-client` | 2.7.2 |
| Outbound HTTP | Ktor client (CIO) | 3.6.0 |
| Logging | Logback | 1.5.21 |
| Tests | JUnit 5, `ktor-server-test-host`, Testcontainers (PostgreSQL) | 5.13.4 / 1.21.3 |
| Build | Gradle wrapper | 9.9.0 (daemon JVM 21, see `gradle/gradle-daemon-jvm.properties`) |
| Deploy | Docker + Fly.io (`fly.toml`, region `bom`) | — |

## Commands

Run all commands from the repository root.

```bash
docker compose up -d postgres     # local Postgres on localhost:5433 (db/user/password: cricket_scoring/cricket/cricket)
./gradlew run                     # start the server on 0.0.0.0:8080 (runs Flyway migrations at startup)
./gradlew test                    # run the tests (needs Docker for Testcontainers; tests are skipped without Docker)
./gradlew build                   # compile + test
./gradlew installDist             # build the runnable distribution into build/install/CricketScoringBackend
docker build -t cricket-scoring-backend .   # build the production image
```

There is no linter or formatter task configured. `gradle.properties` sets `kotlin.code.style=official`, so follow the official Kotlin style.

## Source map

```
src/main/kotlin/com/example/cricketscoring/backend/
  Application.kt        main(), Application.module(), plugins, StatusPages, /health, ApiError
  DatabaseConfig.kt     reads DATABASE_URL, or DB_HOST/DB_PORT/DB_NAME + DB_USER/DB_PASSWORD
  DatabaseFactory.kt    Hikari pool, Flyway migrate, Exposed connect, ping()
  Tables.kt             Exposed table objects that mirror the SQL migrations
  JsonbColumn.kt        custom JSONB column type (stored as a String)
  auth/
    AuthConfig.kt       auth env vars and defaults
    AuthRoutes.kt       /auth/* routes
    AuthService.kt      Google, Apple and OTP sign-in logic; records auth events
    OtpGuard.kt         WhatsApp rollout flag, country allowlist, per-IP / per-phone-failure / global limits
    RateLimitRepository.kt rate_limit_buckets fixed-window counters
    AuthEventRecorder.kt auth_events writer (best effort, phones as HMAC)
    WhatsAppWebhook.kt  Meta delivery-status webhook (handshake, signature, status updates)
    UserRepository.kt   users queries (primary_email / primary_phone_e164 contact fields)
    IdentityService.kt  identity -> account rules: find-or-create on sign-in, link, unlink
    IdentityRepository.kt user_identities queries
    OtpChallengeRepository.kt otp_challenges queries (advisory lock, atomic attempt counting)
    JwtService.kt       mint access JWTs (iss, aud, sub, sid, amr) + verifier for the Ktor jwt provider
    SessionService.kt   sessions, refresh rotation + reuse detection, logout, session list
    SessionRepository.kt sessions + refresh_tokens queries
    GoogleVerifier.kt   fun interface + real Google verifier
    AppleVerifier.kt    interface + AppleTokenVerifier (Apple JWKS, iss/aud/exp, nonce, notifications)
    AppleTokenClient.kt Apple code exchange + revoke (ES256 client secret), TokenCipher (AES-GCM at rest)
    AppleTokenRepository.kt apple_tokens queries
    AccountService.kt   immediate account deletion (Apple revoke, then one transaction)
    AccountRepository.kt account-wide cleanup SQL (soft-delete owned data, memberships, OTP history)
    WhatsAppOtpSender.kt fun interface + console and WhatsApp Cloud API senders
    OtpHasher.kt        SecureRandom codes, HMAC-SHA256(OTP_PEPPER, challengeId:code)
    PhoneNumber.kt      E.164 normalization
    AuthDtos.kt         request/response DTOs
    AuthException.kt    typed error -> HTTP status
src/main/resources/
  db/migration/V1__foundation.sql, V2__auth_oauth.sql, V3__otp_challenges.sql, V4__sessions.sql, V5__user_identities.sql, V6__provider_revoked_sessions.sql, V7__account_deletion.sql, V8__abuse_limits_and_events.sql
  logback.xml
src/test/kotlin/.../    AuthIntegrationTest.kt, AbuseAndRolloutTest.kt, TestConfig.kt, AppleIntegrationTest.kt, AccountDeletionTest.kt, AppleTokenCryptoTest.kt, FakeAppleTokenClient.kt, AuthConcurrencyTest.kt, MigrationBackfillTest.kt, FakeGoogle.kt, OtpHasherTest.kt, DatabaseFoundationTest.kt, DatabaseConfigTest.kt
```

## Rules for secrets and API keys

- **Never commit secrets.** All secrets come from environment variables, read in `AuthConfig.fromEnv()` and `DatabaseConfig.fromEnv()`. Don't hardcode tokens, client secrets or passwords in source, tests, `fly.toml`, the Dockerfile or the workflows.
- Secret env vars: `JWT_SECRET`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_APP_SECRET`, `OTP_PEPPER`, `APPLE_PRIVATE_KEY`, `APPLE_TOKEN_ENCRYPTION_KEY`, `DATABASE_URL` (may contain a password), `DB_PASSWORD` / `DATABASE_PASSWORD`. Local values go in the git-ignored `.env` (template: `.env.example`). In production, set them with `fly secrets set ...`. The only CI secret is `FLY_API_TOKEN` (GitHub Actions).
- `JWT_SECRET` defaults to `dev-only-change-me`. That default is for local use only. A production deploy must set it.
- `OTP_PEPPER` is the HMAC key for OTP hashes, separate from `JWT_SECRET`. Rotating it only invalidates pending codes. `AuthConfig.fromEnv()` refuses to start in `WHATSAPP_OTP_MODE=cloud` without it.
- `local.properties` is git-ignored and machine-specific. Don't read values from it into code or commit it.
- In `WHATSAPP_OTP_MODE=console` (the default), OTP codes are **logged in plain text at WARN level**. Never use console mode in production, and never add code that logs codes, tokens or `Authorization` headers in cloud mode.
- Apple refresh tokens are stored only AES-GCM-encrypted (`TokenCipher`, identity id as associated data) and only to revoke them. Never log them.
- Refresh tokens are stored only as SHA-256 hashes. Never log a refresh token, and never store one in plain text.
- Start test configs from `testAuthConfig()` (TestConfig.kt). It lifts per-IP limits, because every Ktor test request comes from one address.
- Tests use fixed fake values (`jwtSecret = "test-secret"`, `otpPepper = "test-pepper"`, `googleClientIds = listOf("test-client-id")`) and fake verifiers/senders. Apple tests sign tokens with a key generated in the test and hand the real `AppleTokenVerifier` a stand-in `JwkProvider`. Keep it that way. Tests must never call Google, Apple or Meta.

## Conventions to follow

- **Authenticated routes**: put them inside `authenticate(ACCESS_AUTH) { }` and read `call.principal<AccessPrincipal>()` (user id + session id). The provider already rejects revoked or expired sessions; don't re-parse the `Authorization` header yourself.
- **Routes**: auth routes are defined once in `authRouteTree` and mounted at `/api/v1/auth` and `/auth`. Add new routes there, not under a hard-coded prefix.
- **Errors**: throw `AuthException(code, message, status)` (or add a sibling typed exception and register it in `StatusPages`). Every error body has the shape `ApiError { code, message }`. Use snake_case codes (`invalid_otp`, `otp_cooldown`, ...).
- **Dependency injection**: constructor injection by hand. `Application.module(config, authDependencies)` takes optional parameters, so tests can pass a Testcontainers `DatabaseConfig` and fake `GoogleVerifier` / `WhatsAppOtpSender`. External integrations are `fun interface`s. Keep new integrations behind the same kind of seam.
- **Database**: every query runs inside Exposed `transaction { }`. Use the `org.jetbrains.exposed.v1.*` imports (Exposed 1.x), not the old `org.jetbrains.exposed.sql.*` ones.
- **Timestamps** are `BIGINT` epoch milliseconds (`*_at_epoch_ms`), from `System.currentTimeMillis()`.
- **IDs**: `users.id` and `memberships.id` are UUIDs. Domain entities (`players`, `teams`, `matches`, ...) use `TEXT` ids, so the server can store the ids the mobile app already generates (see the comment in `Tables.kt`).
- **Domain sync columns**: domain tables carry `version` (optimistic concurrency, default 1), `owner_user_id`, and `deleted_at_epoch_ms` (soft delete). Nested data (rules, squads, toss, ball events) is stored as `JSONB`.
- **Schema changes**: add a new `V<n>__<description>.sql` under `src/main/resources/db/migration/` and update `Tables.kt` to match. Never edit an applied migration. See `DEVELOPMENT.md`.
- Phone numbers are always stored and compared as normalized E.164 (`PhoneNumber.normalize`). Google emails are lowercased.
- **Deleted accounts**: `users.status = DELETED` rows are scrubbed tombstones kept for foreign keys. Anything that attaches data to a user (linking, future domain writes) must take `UserRepository.lockForUpdate` and refuse deleted users, as `IdentityService.link` does; `AccountService.delete` takes the same lock.
- **Accounts vs identities**: `users` is the account and holds only *verified* contact fields (`primary_email`, `primary_phone_e164`, both unique). How someone signs in lives in `user_identities` (`UNIQUE (provider, provider_subject)` and `UNIQUE (user_id, provider)`). Key Google/Apple on `sub`, never on email. Go through `IdentityService` for any sign-in or linking; don't write `user_identities` directly.

## Known gaps (as of this writing)

- `RequestValidation` is installed but no validators are registered.
- The server port (8080) is hardcoded in `main()`.
- Per-IP limits trust the `Fly-Client-IP` header (set by Fly's proxy). If the app is ever served without Fly's proxy in front, clients can spoof it and dodge per-IP limits; switch the IP source then.
- Nothing prunes `auth_events`, `rate_limit_buckets` or `otp_challenges`; they grow forever. Run the cleanup SQL in `DEVELOPMENT.md` on a schedule.
- `AuthEventRecorder.record` joins any enclosing transaction; call it outside transactions or the event is lost on rollback.
- Ktor's in-memory `testApplication` runs blocking handlers one at a time, so race conditions must be tested at the service level with real threads (see `AuthConcurrencyTest`).
