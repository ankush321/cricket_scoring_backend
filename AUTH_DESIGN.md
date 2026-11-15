# Auth Design: Google, Apple, WhatsApp OTP (Beta)

Status: **all five phases and account deletion implemented** (2026-11-15/16). Phase 1 (OTP hardening) fixed findings 1–8, 12 and 13. Phase 2 (sessions) fixed finding 9: 15-minute PII-free access tokens, rotating refresh tokens with reuse detection, logout / logout-all / session list. Phase 2 differs from §5 in two ways: routes live under `/auth/*` (the `/api/v1` move is phase 5), and **every** authenticated request checks the session is live, not only sensitive routes, so logout takes effect immediately. Phase 3 (identities) fixed findings 10 and 11: `user_identities` with backfill, linking rules, `/auth/me/identities`, multiple Google client IDs. Phase 3 differs from §1–2 in that V5 renames `users.email`/`phone_e164` to `primary_email`/`primary_phone_e164` and drops `google_sub`/`password_hash` in the same migration (no separate cleanup migration), and it adds no `status`/`deleted_at` columns yet. Phase 4 (Apple) added `/auth/apple`, Apple linking and `/auth/apple/notifications` (`consent-revoked` / `account-delete` revoke sessions; `account-delete` also removes the identity unless it is the last). Account deletion (`DELETE /auth/me`, V7) followed, and with it Apple token revocation: the optional `authorizationCode` is exchanged for Apple's refresh token, stored AES-256-GCM-encrypted in `apple_tokens`, and revoked at Apple on deletion or when Apple is unlinked.

**Decision (2026-11-16), owned data on deletion:** deletion is **immediate**, with no grace period. Cricket data the user owns (players, teams, rule profiles, tournaments, matches) is **soft-deleted**. The user's memberships, and other users' memberships on those resources, are deleted. The `users` row is kept as a scrubbed tombstone (`status = DELETED`, no email or phone, name "Deleted user") so foreign keys stay valid. Collaborators lose access to the deleted owner's resources. A failed Apple revoke is logged and does not block deletion. Phase 5 is not started. Phase 5 (beta ops) added `OtpGuard` (rollout flag, allowlist, minimum app version, country allowlist, per-IP limits, per-phone wrong-code block, global daily budget), `/auth/providers`, `auth_events`, the WhatsApp delivery webhook, and `/api/v1/auth/*` with `/auth/*` kept as aliases. It differs from §6–8 in three ways. The budget defaults to 2000/day but `0` disables it. The country allowlist is empty (all countries) by default. Linking routes are at `/api/v1/auth/me/identities` rather than `/api/v1/me/identities`. Not built: CAPTCHA / Play Integrity / App Attest, alerting, and a pruning job (see DEVELOPMENT.md → Housekeeping).
Stack assumptions: Ktor 3.6, Exposed 1.6, Flyway, PostgreSQL 18, `com.auth0:java-jwt`. Conventions from `AGENTS.md` still apply (epoch-ms `BIGINT` timestamps, `AuthException` → `ApiError`, `fun interface` seams for every external call, no secrets in source).

---

## 0. Review of the current login code

| # | Finding | Where | Severity |
|---|---|---|---|
| 1 | OTP codes come from `kotlin.random.Random`, which is not a CSPRNG. Codes are predictable in principle. | `AuthService.generateOtp` | High |
| 2 | Wrong-guess counting is a read-then-write in two transactions (`findOtp` → compare → `incrementOtpAttempts`, which itself reads then writes). Parallel guesses all pass the `attemptCount >= max` check, so the attempt cap can be exceeded by firing requests concurrently. | `AuthService.verifyWhatsAppOtp`, `UserRepository.incrementOtpAttempts` | High |
| 3 | A resend resets `attempt_count` to 0 and the only limit is a 60 s cooldown. An attacker gets 5 guesses per minute, forever: ~7,200 guesses/day ≈ 0.7 % chance per day of taking over any phone, plus unbounded WhatsApp spend. | `UserRepository.upsertOtp`, `AuthConfig` | High |
| 4 | No per-IP or global send limits. One client can request OTPs for thousands of numbers (cost inflation / SMS-pumping style abuse). | — | High |
| 5 | OTP row is written **before** the WhatsApp send. If Meta fails, the user gets `502` and is then blocked by the cooldown for 60 s. | `AuthService.requestWhatsAppOtp` | Medium |
| 6 | Meta **authentication** templates with a copy-code / one-tap button require a `button` component parameter as well as the `body` one. The sender only sends `body`, so a standard authentication template will be rejected. Graph API version (`v21.0`) and language (`en`) are hard-coded; the HTTP client has no timeouts; `runBlocking` blocks a request thread. | `CloudWhatsAppOtpSender` | Medium |
| 7 | OTP hash is `SHA-256(code:JWT_SECRET)` compared with `!=` (not constant-time), and the OTP pepper is the JWT secret, so rotating one rotates the other. | `OtpHasher`, `AuthService` | Medium |
| 8 | Concurrent first logins for the same phone or Google account race to `INSERT` and the loser gets a unique-violation `500`. | `UserRepository.createOrUpdatePhoneUser`, `createGoogleUser` | Medium |
| 9 | One access token, 7-day lifetime, no refresh token, no revocation, no `iss`/`aud`, and email/phone (PII) inside the JWT. A leaked token is valid for a week and cannot be killed. | `JwtService` | Medium |
| 10 | Account model can't link providers: `google_sub` and `phone_e164` are columns on `users`. A user who signs in with WhatsApp and later with Google gets **two accounts**. Adding Apple means another column. | `V2__auth_oauth.sql` | Medium (design) |
| 11 | Only one Google client ID is accepted. Android, iOS and web each have their own OAuth client, so at most one platform can sign in. | `AuthConfig.googleClientId` | Medium |
| 12 | Malformed JSON returns `500 internal_error` (`StatusPages` catches `Throwable` before `BadRequestException`). | `Application.kt` | Low |
| 13 | Default `OTP_MAX_ATTEMPTS` is 5; the target is 3. | `AuthConfig` | Low |

What is already right and should be kept: E.164 normalisation, Google token verification via `GoogleIdTokenVerifier` (it fetches and caches Google's JWKS and checks `iss`/`aud`/`exp`), rejecting unverified Google emails, OTPs stored hashed with a short TTL, and the `GoogleVerifier` / `WhatsAppOtpSender` seams that keep tests offline.

---

## 1. Database schema

New migrations `V3__identities_sessions.sql` (and later ones). Existing tables stay; data is backfilled, then the old columns are dropped in a later migration once the app has moved over.

### 1.1 Entity overview

```
users 1───* user_identities        (google | apple | whatsapp)
users 1───* sessions 1───* refresh_tokens
otp_challenges                     (one row per OTP sent; not tied to a user until verified)
rate_limit_buckets                 (shared counters for per-IP / global limits)
auth_events                        (append-only audit + beta analytics)
```

### 1.2 `users` (changed)

The user is the account; identities are how you prove you own it.

```sql
-- keep: id UUID PK, display_name, created_at_epoch_ms, updated_at_epoch_ms
ALTER TABLE users
    ADD COLUMN primary_email          TEXT,      -- lowercase, verified only
    ADD COLUMN primary_phone_e164     TEXT,      -- E.164, verified only
    ADD COLUMN avatar_url             TEXT,
    ADD COLUMN status                 TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    ADD COLUMN deleted_at_epoch_ms    BIGINT;

CREATE UNIQUE INDEX users_primary_email_uq ON users (primary_email)
    WHERE primary_email IS NOT NULL AND deleted_at_epoch_ms IS NULL;
CREATE UNIQUE INDEX users_primary_phone_uq ON users (primary_phone_e164)
    WHERE primary_phone_e164 IS NOT NULL AND deleted_at_epoch_ms IS NULL;
```

- `primary_email` / `primary_phone_e164` are **contact** fields, unique among live accounts, and only ever set from a *verified* source (Google `email_verified`, Apple non-relay verified email, WhatsApp OTP). Partial indexes let a deleted account release its email/phone.
- `email`, `password_hash`, `google_sub`, `phone_e164` move out: backfill into `user_identities` / primary fields in V3, drop in a later migration.

### 1.3 `user_identities` (new)

```sql
CREATE TABLE user_identities (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),   -- PG 18 built-in, time-ordered
    user_id               UUID NOT NULL REFERENCES users (id),
    provider              TEXT NOT NULL CHECK (provider IN ('GOOGLE', 'APPLE', 'WHATSAPP')),
    provider_subject      TEXT NOT NULL,   -- Google sub | Apple sub | E.164 phone
    email                 TEXT,            -- as reported by the provider, lowercase
    email_verified        BOOLEAN NOT NULL DEFAULT FALSE,
    is_private_relay      BOOLEAN NOT NULL DEFAULT FALSE,  -- Apple Hide My Email
    phone_e164            TEXT,
    profile_json          JSONB,           -- name/picture snapshot, never tokens
    created_at_epoch_ms   BIGINT NOT NULL,
    last_used_at_epoch_ms BIGINT NOT NULL,
    UNIQUE (provider, provider_subject)
);
CREATE INDEX user_identities_user_idx  ON user_identities (user_id);
CREATE INDEX user_identities_email_idx ON user_identities (email) WHERE email_verified;
```

- `UNIQUE (provider, provider_subject)` is the duplicate-account guard: one Google account / Apple ID / phone can belong to exactly one user.
- Key Google and Apple on `sub`, never on email. Emails change; `sub` doesn't.
- For WhatsApp, `provider_subject` is the normalised E.164 number.

Backfill:

```sql
INSERT INTO user_identities (user_id, provider, provider_subject, email, email_verified, created_at_epoch_ms, last_used_at_epoch_ms)
SELECT id, 'GOOGLE', google_sub, email, TRUE, created_at_epoch_ms, updated_at_epoch_ms FROM users WHERE google_sub IS NOT NULL;
INSERT INTO user_identities (user_id, provider, provider_subject, phone_e164, created_at_epoch_ms, last_used_at_epoch_ms)
SELECT id, 'WHATSAPP', phone_e164, phone_e164, created_at_epoch_ms, updated_at_epoch_ms FROM users WHERE phone_e164 IS NOT NULL;
UPDATE users SET primary_email = email, primary_phone_e164 = phone_e164;
```

### 1.4 `otp_challenges` (replaces `login_otps`)

```sql
CREATE TABLE otp_challenges (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    phone_e164            TEXT NOT NULL,
    purpose               TEXT NOT NULL CHECK (purpose IN ('LOGIN', 'LINK')),
    link_user_id          UUID REFERENCES users (id),         -- set when purpose = LINK
    channel               TEXT NOT NULL DEFAULT 'WHATSAPP',
    code_hash             BYTEA NOT NULL,                     -- HMAC-SHA256(OTP_PEPPER, id || code)
    attempt_count         INTEGER NOT NULL DEFAULT 0,
    max_attempts          INTEGER NOT NULL,                   -- 3
    expires_at_epoch_ms   BIGINT NOT NULL,                    -- created + 300 s
    consumed_at_epoch_ms  BIGINT,                             -- success OR invalidation
    consumed_reason       TEXT CHECK (consumed_reason IN ('VERIFIED', 'MAX_ATTEMPTS', 'SUPERSEDED', 'EXPIRED', 'SEND_FAILED')),
    provider_message_id   TEXT,                               -- Meta wamid, for delivery webhooks
    delivery_status       TEXT,                               -- SENT | DELIVERED | READ | FAILED
    request_ip            INET,
    created_at_epoch_ms   BIGINT NOT NULL
);
-- at most one live challenge per phone
CREATE UNIQUE INDEX otp_challenges_live_uq ON otp_challenges (phone_e164) WHERE consumed_at_epoch_ms IS NULL;
-- window counting for rate limits
CREATE INDEX otp_challenges_phone_time_idx ON otp_challenges (phone_e164, created_at_epoch_ms);
```

- **Rows are kept** after use (pruned after 30 days). That history *is* the per-phone rate limit: "how many challenges did this phone get in the last 10 minutes" is one indexed `COUNT`. Today's `login_otps` deletes rows, which is why a resend resets the attempt budget.
- Binding the challenge `id` into the HMAC means a hash can't be replayed against another challenge.

### 1.5 `sessions` and `refresh_tokens` (new)

```sql
CREATE TABLE sessions (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),  -- goes into the JWT as `sid`
    user_id               UUID NOT NULL REFERENCES users (id),
    auth_method           TEXT NOT NULL,                      -- GOOGLE | APPLE | WHATSAPP
    is_beta_flow          BOOLEAN NOT NULL DEFAULT FALSE,
    device_name           TEXT,
    user_agent            TEXT,
    ip_at_creation        INET,
    created_at_epoch_ms   BIGINT NOT NULL,
    last_used_at_epoch_ms BIGINT NOT NULL,
    expires_at_epoch_ms   BIGINT NOT NULL,                    -- absolute cap, e.g. 90 days
    revoked_at_epoch_ms   BIGINT,
    revoked_reason        TEXT                                -- LOGOUT | LOGOUT_ALL | REUSE_DETECTED | ADMIN | ACCOUNT_DELETED
);
CREATE INDEX sessions_user_idx ON sessions (user_id) WHERE revoked_at_epoch_ms IS NULL;

CREATE TABLE refresh_tokens (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    session_id            UUID NOT NULL REFERENCES sessions (id),
    token_hash            BYTEA NOT NULL UNIQUE,              -- SHA-256 of the opaque token
    parent_id             UUID REFERENCES refresh_tokens (id),
    created_at_epoch_ms   BIGINT NOT NULL,
    expires_at_epoch_ms   BIGINT NOT NULL,                    -- sliding, e.g. 30 days
    used_at_epoch_ms      BIGINT                              -- set when rotated
);
CREATE INDEX refresh_tokens_session_idx ON refresh_tokens (session_id);
```

### 1.6 `rate_limit_buckets` and `auth_events` (new)

```sql
-- fixed-window counters; works across several Fly machines without Redis
CREATE TABLE rate_limit_buckets (
    bucket_key            TEXT NOT NULL,      -- e.g. 'otp_send:ip:203.0.113.7'
    window_start_epoch_ms BIGINT NOT NULL,
    count                 INTEGER NOT NULL,
    PRIMARY KEY (bucket_key, window_start_epoch_ms)
);

CREATE TABLE auth_events (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id               UUID,
    event_type            TEXT NOT NULL,      -- OTP_SENT, OTP_VERIFY_FAILED, LOGIN_SUCCEEDED, REFRESH_REUSE_DETECTED, ...
    provider              TEXT,
    is_beta_flow          BOOLEAN NOT NULL DEFAULT FALSE,
    outcome               TEXT NOT NULL,      -- OK | DENIED | ERROR
    error_code            TEXT,
    ip                    INET,
    phone_hash            BYTEA,              -- HMAC of the phone, never the raw number
    metadata              JSONB,
    created_at_epoch_ms   BIGINT NOT NULL
);
CREATE INDEX auth_events_type_time_idx ON auth_events (event_type, created_at_epoch_ms);
```

Increment a bucket atomically:

```sql
INSERT INTO rate_limit_buckets AS b (bucket_key, window_start_epoch_ms, count) VALUES (?, ?, 1)
ON CONFLICT (bucket_key, window_start_epoch_ms) DO UPDATE SET count = b.count + 1
RETURNING b.count;
```

---

## 2. Account linking rules

The rule of thumb: **only link on verified data, and never auto-link on an email the provider can't vouch for.**

| Sign-in with | Look up | If not found |
|---|---|---|
| Google | `user_identities (GOOGLE, sub)` | If `email_verified` and a live user has the same `primary_email` → attach a GOOGLE identity to that user. Otherwise create a user. |
| Apple | `user_identities (APPLE, sub)` | If Apple returns a verified, **non-relay** email matching a live user's `primary_email` → attach. If the email is `@privaterelay.appleid.com` → **never** auto-link; create a user. |
| WhatsApp | `user_identities (WHATSAPP, +E.164)` | If a live user has the same `primary_phone_e164` → attach. Otherwise create a user. |

- Everything else (e.g. "I signed up with WhatsApp, now I want Google on the same account") goes through **explicit linking** while signed in: `POST /api/v1/me/identities/{provider}` with proof of the new identity. The server attaches it to the caller's user.
- If that identity already belongs to a *different* user → `409 identity_already_linked`. Merging two accounts with data is a separate, manual flow (out of scope).
- Unlinking: allowed unless it's the user's last identity (`409 last_identity`).
- Every "find or create" runs in **one transaction** using `INSERT ... ON CONFLICT (provider, provider_subject) DO NOTHING RETURNING ...` followed by a `SELECT` on conflict, so two concurrent first logins resolve to the same user instead of a `500` (fixes finding 8).

---

## 3. Provider token verification

### 3.1 Google

- Keep `GoogleIdTokenVerifier` (handles JWKS fetch + key rotation + caching, checks `iss ∈ {accounts.google.com, https://accounts.google.com}`, `aud`, `exp`).
- Accept a **list** of audiences: `GOOGLE_CLIENT_IDS=android-id,ios-id,web-id` (finding 11).
- Require `email_verified = true` before using the email for linking (already done). A Google account without a verified email can still sign in; it just isn't linked by email.
- Optional: client passes a `nonce`; verify `payload.nonce` matches, to stop token replay from another app.

### 3.2 Apple

Client sends the `identityToken` (JWT) and the one-time `authorizationCode`; name and email arrive from the client **only on the first** authorisation and must be saved then.

Verification (`AppleVerifier` behind a `fun interface`, like Google):

1. Parse header → `kid`. Fetch `https://appleid.apple.com/auth/keys` (JWKS); cache keys by `kid` for ~24 h, refetch on unknown `kid` (Apple rotates). `com.auth0:jwks-rsa` does this and pairs with the `java-jwt` already in the build.
2. Verify `RS256` signature, `iss == https://appleid.apple.com`, `aud ∈ APPLE_CLIENT_IDS` (iOS bundle ID; Services ID for web/Android), `exp` in the future (allow ~60 s skew).
3. Verify `nonce`: the client generates a random nonce, sends `SHA-256(nonce)` to Apple, and sends the **raw** nonce to us. We check `sha256(raw) == token.nonce`.
4. Read `sub` (stable per Apple developer team), `email`, `email_verified`, `is_private_email`. Apple sends the booleans as either `true` or `"true"`: accept both.

Private relay emails (`*@privaterelay.appleid.com`):
- Store on the identity with `is_private_relay = true`. You may use it as the account's contact email if the user has none, but **never** to auto-link.
- Sending mail to it only works from domains registered in the Apple Developer portal (Sign in with Apple → email sources).

Server-to-server and account deletion:
- `POST /api/v1/auth/apple/notifications`: Apple posts a signed JWT (verify like step 1–2) for `email-disabled`, `email-enabled`, `consent-revoked`, `account-delete`. On `consent-revoked`/`account-delete`, revoke all sessions for that identity's user.
- App Store review requires in-app account deletion; for SIWA users, also revoke the Apple token: exchange the `authorizationCode` at `https://appleid.apple.com/auth/token` (client secret = ES256 JWT signed with your `.p8` key; key in `APPLE_PRIVATE_KEY`, a secret), keep the Apple refresh token **encrypted**, and call `/auth/revoke` on deletion. This is the only reason to store any Apple token.

---

## 4. WhatsApp OTP (Beta)

### 4.1 Send flow (`POST /api/v1/auth/whatsapp/send-otp`)

1. Feature flag check (§6). Normalise phone. Check country allowlist (`WHATSAPP_ALLOWED_COUNTRY_CODES=91`) — blocks premium-rate/IRSF destinations.
2. Rate limits (§7) in this order, cheapest first: per-IP bucket → per-phone cooldown → per-phone window → global daily budget.
3. In **one transaction**: mark any live challenge for the phone `SUPERSEDED`, insert a new challenge with `code_hash = HMAC-SHA256(OTP_PEPPER, challengeId || code)`. Code = `SecureRandom().nextInt(1_000_000)` zero-padded to 6 digits.
4. Send via `OtpSender` (outside the transaction). On failure → mark challenge `SEND_FAILED` **and don't count it against the cooldown** → `502 otp_delivery_failed` (fixes finding 5). On success → store `wamid` as `provider_message_id`.
5. Respond `202` with `challengeId`. Never echo the code, never log it outside console mode.

Meta Cloud API specifics:
- Use an **AUTHENTICATION**-category template (Meta adds the security disclaimer and copy-code/one-tap button). Send both components:

  ```json
  {"messaging_product":"whatsapp","to":"919876543210","type":"template",
   "template":{"name":"login_otp","language":{"code":"en"},
     "components":[
       {"type":"body","parameters":[{"type":"text","text":"123456"}]},
       {"type":"button","sub_type":"url","index":"0","parameters":[{"type":"text","text":"123456"}]}]}}
  ```
- Config: `WHATSAPP_GRAPH_VERSION`, `WHATSAPP_TEMPLATE_LANGUAGE`. Set connect/request timeouts (e.g. 3 s / 10 s) on the Ktor client, and make `sendOtp` a `suspend fun` instead of `runBlocking`.
- Delivery webhook `POST /api/v1/webhooks/whatsapp` (verify `X-Hub-Signature-256` with the app secret) updates `delivery_status`. That gives the beta real delivery/read metrics.
- Gateways (Twilio, Gupshup) are just other `OtpSender` implementations selected by `OTP_PROVIDER=meta|twilio|gupshup|console`.

### 4.2 Verify flow (`POST /api/v1/auth/whatsapp/verify-otp`)

One atomic statement does the attempt accounting, so concurrent guesses can't exceed the cap (fixes finding 2):

```sql
UPDATE otp_challenges
   SET attempt_count = attempt_count + 1
 WHERE id = :challengeId AND phone_e164 = :phone
   AND consumed_at_epoch_ms IS NULL
   AND expires_at_epoch_ms > :now
   AND attempt_count < max_attempts
RETURNING code_hash, attempt_count, max_attempts;
```

- No row → `401 invalid_otp` (expired, used, unknown, or locked; same message for all).
- Compare with `MessageDigest.isEqual` (constant time).
- Match → set `consumed_reason = 'VERIFIED'`, find-or-create user (§2), create session, issue tokens. All in one transaction.
- Mismatch and `attempt_count == max_attempts` → mark `MAX_ATTEMPTS`; response `429 otp_locked`. Otherwise `401 invalid_otp` with `attemptsRemaining`.

---

## 5. Sessions and tokens

| Token | Format | Lifetime | Stored server-side |
|---|---|---|---|
| Access | JWT HS256 (move to ES256 + `kid` when another service needs to verify) | **15 min** | No |
| Refresh | Opaque 256-bit random, base64url | 30 days sliding, 90 days absolute (session cap) | `SHA-256` hash only |

Access-token claims: `iss`, `aud`, `sub` (user id), `sid` (session id), `amr` (`google`/`apple`/`whatsapp`), `iat`, `exp`, `jti`. **No email or phone** (finding 9).

Refresh rotation with reuse detection (`POST /api/v1/auth/token/refresh`):

1. Hash the presented token, look it up with `SELECT ... FOR UPDATE`.
2. Not found / expired / session revoked → `401 invalid_refresh_token`.
3. **Already used** (`used_at` set) → someone replayed an old token: revoke the whole session (`REUSE_DETECTED`), log an `auth_event`, `401`.
4. Otherwise set `used_at`, insert the child token (`parent_id`), bump `sessions.last_used_at`, return a new access + refresh pair.
5. Allow a ~10 s grace window where reusing the *immediately previous* token returns the same child (mobile apps retry on flaky networks).

Revocation:
- `POST /api/v1/auth/logout` revokes the current session (`sid` from the access token).
- `POST /api/v1/auth/logout-all` revokes every session of the user.
- `GET /api/v1/auth/sessions` / `DELETE /api/v1/auth/sessions/{id}` for a "signed-in devices" screen.
- Revoked sessions stop refreshing immediately; their access tokens die within 15 min. For sensitive routes (account deletion, unlinking), also check `sessions.revoked_at` for the `sid`.

Secrets: `JWT_SECRET` (signing), `OTP_PEPPER` (OTP HMAC, **separate** from JWT — finding 7), `APPLE_PRIVATE_KEY`, `WHATSAPP_APP_SECRET`. All via `fly secrets set`.

Client storage guidelines:
- iOS: Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
- Android: encrypt with an Android Keystore key, store in DataStore. Never plain `SharedPreferences`.
- Keep the access token in memory only; persist the refresh token.
- Never log tokens or the `Authorization` header; never put tokens in URLs.

---

## 6. WhatsApp beta: feature flag and tracking

| Config | Values | Effect |
|---|---|---|
| `WHATSAPP_OTP_ROLLOUT` | `off` \| `allowlist` \| `on` | `off` → send/verify return `403 feature_disabled` |
| `WHATSAPP_OTP_ALLOWLIST` | comma-separated E.164 numbers or prefixes | used when rollout = `allowlist` |
| `WHATSAPP_OTP_MIN_APP_VERSION` | e.g. `1.4.0` | compared with the `X-App-Version` request header |

- `GET /api/v1/auth/providers` returns what the client should show, so the app hides the WhatsApp button when it's off: `{"google":true,"apple":true,"whatsapp":{"enabled":true,"beta":true}}`.
- WhatsApp responses carry `"beta": true` and the header `X-Auth-Beta: whatsapp-otp`.
- Sessions created through it get `is_beta_flow = true`; every OTP step writes an `auth_event` with `is_beta_flow = true`. Funnel query: `OTP_SENT → OTP_DELIVERED → LOGIN_SUCCEEDED` by day, plus cost per successful login.
- A server-side flag beats a client-sent header for gating: clients can't opt themselves in.

---

## 7. Rate limiting and OTP abuse prevention

| Limit | Default | Response |
|---|---|---|
| Per phone: resend cooldown | 1 per 60 s | `429 otp_cooldown` + `Retry-After` |
| Per phone: window | **3 sends / 10 min**, 10 / 24 h | `429 otp_rate_limited` + `Retry-After` |
| Per challenge: wrong codes | **3**, then challenge invalidated | `429 otp_locked` |
| Per phone: failed verifies | 10 / hour → block sends to that phone for 1 h | `429 otp_rate_limited` |
| Per IP: sends | 10 / 10 min | `429 rate_limited` |
| Per IP: verifies | 30 / 10 min | `429 rate_limited` |
| Global: sends | daily budget, e.g. `OTP_DAILY_SEND_BUDGET=2000`; alert at 80 % | `503 otp_unavailable` |
| Destination | country-code allowlist | `400 unsupported_country` |

Why this is enough: with 3 sends × 3 attempts per 10 min, an attacker gets at most 9 guesses per 10 minutes against a 1,000,000-code space (≈ 1 in 111,000 per window), and the 24 h cap stops it from scaling. Compare finding 3.

Other practices:
- Per-IP keys use the client IP from `Fly-Client-IP` (set by Fly's proxy) — don't trust a raw `X-Forwarded-For`.
- Same error for "unknown phone" and "wrong code" so the endpoint can't be used to discover registered numbers.
- Add a CAPTCHA or Play Integrity / App Attest token on `send-otp` if abuse shows up in `auth_events`; the per-IP and global limits buy time until then.
- Prune `otp_challenges`, `rate_limit_buckets` and `auth_events` with a daily job.
- Alert on: send volume spikes, delivery-failure rate, verify-failure rate, `REUSE_DETECTED` events.

---

## 8. REST API (v1)

All under `/api/v1`. Errors use the existing `ApiError { code, message }` shape. The current `/auth/*` routes stay as aliases until the app has moved over.

Common success body for all sign-in endpoints and refresh:

```json
{
  "user": { "id": "uuid", "displayName": "Ravi", "email": "r@x.com", "phone": "+9198...", "avatarUrl": null },
  "accessToken": "eyJ...", "tokenType": "Bearer", "accessTokenExpiresIn": 900,
  "refreshToken": "opaque", "refreshTokenExpiresIn": 2592000,
  "isNewUser": false,
  "linkedProviders": ["GOOGLE", "WHATSAPP"]
}
```

| Method & path | Request | Success | Main errors |
|---|---|---|---|
| `POST /auth/google` | `{ idToken, nonce? , deviceName? }` | `200` sign-in body | `400 validation_error`, `401 invalid_google_token`, `503 google_not_configured` |
| `POST /auth/apple` | `{ identityToken, authorizationCode, nonce, fullName?: {givenName, familyName}, deviceName? }` | `200` sign-in body | `401 invalid_apple_token`, `401 nonce_mismatch`, `503 apple_not_configured` |
| `POST /auth/whatsapp/send-otp` | `{ phone }` | `202 { challengeId, expiresInSeconds: 300, resendAfterSeconds: 60, attemptsAllowed: 3, channel: "WHATSAPP", beta: true }` | `400 validation_error`/`unsupported_country`, `403 feature_disabled`, `429 otp_cooldown`/`otp_rate_limited`/`rate_limited`, `502 otp_delivery_failed`, `503 otp_unavailable` |
| `POST /auth/whatsapp/verify-otp` | `{ challengeId, phone, code }` | `200` sign-in body (+ `beta: true`) | `401 invalid_otp` (+ `attemptsRemaining`), `429 otp_locked` |
| `POST /auth/token/refresh` | `{ refreshToken }` | `200` sign-in body (new pair) | `401 invalid_refresh_token` |
| `POST /auth/logout` | bearer | `204` | `401 unauthorized` |
| `POST /auth/logout-all` | bearer | `204` | `401 unauthorized` |
| `GET /auth/sessions` | bearer | `200 [{ id, deviceName, authMethod, createdAt, lastUsedAt, current }]` | `401` |
| `DELETE /auth/sessions/{id}` | bearer | `204` | `404 not_found` |
| `GET /auth/providers` | — | `200 { google, apple, whatsapp: { enabled, beta } }` | — |
| `GET /me/identities` | bearer | `200 [{ id, provider, email, isPrivateRelay, phone, createdAt }]` | `401` |
| `POST /me/identities/google` \| `apple` | bearer + same body as sign-in | `201` identity | `409 identity_already_linked` |
| `POST /me/identities/whatsapp` | bearer + `{ challengeId, phone, code }` (challenge sent with `purpose=LINK`) | `201` identity | `409 identity_already_linked` |
| `DELETE /me/identities/{id}` | bearer | `204` | `409 last_identity` |
| `POST /auth/apple/notifications` | Apple signed payload | `200` | `400` |
| `POST /webhooks/whatsapp` | Meta payload + `X-Hub-Signature-256` | `200` | `401` bad signature |

Ktor wiring: install `ktor-server-auth-jwt` with a `jwt("access")` provider that checks `iss`/`aud`/`exp` and exposes `sub`/`sid` as a principal, wrap protected routes in `authenticate("access") { }`, and map `BadRequestException` → `400 validation_error` in `StatusPages` (finding 12).

---

## 9. Suggested implementation order

Each phase is shippable on its own and keeps the current mobile app working.

1. **OTP hardening** ✅ done (findings 1–8, 12, 13; per-IP/global limits stay in phase 5): `SecureRandom`, `OTP_PEPPER` + HMAC + constant-time compare, `otp_challenges` with atomic attempt counting, 3 attempts, 3-per-10-min window, send-failure handling, Meta button component + timeouts + `suspend` sender, 400 for bad JSON. Tests for each limit.
2. **Sessions** ✅ done: `sessions` + `refresh_tokens`, 15-min access tokens without PII, refresh rotation with reuse detection, logout / logout-all / sessions list, `authenticate("access")`.
3. **Identities** ✅ done: `user_identities` + backfill, linking rules, race-safe find-or-create, `/me/identities`, multiple Google client IDs.
4. **Apple** ✅ done, including token revoke on account deletion: `AppleVerifier` with JWKS cache + nonce, `/auth/apple`, notifications endpoint, token revoke on account deletion.
5. **Beta ops** ✅ done: rollout flag + allowlist, `/auth/providers`, `auth_events`, per-IP + global limits, WhatsApp delivery webhook, `/api/v1` paths with `/auth/*` aliases.
