# Data Flow

> **Scope note:** this repository has **no** data ingestion pipeline, embeddings, vector search or RAG. It contains no LLM client and no vector database dependency (see `build.gradle.kts`). This document traces the data flows that do exist: start-up and migrations, the health check, Google sign-in and WhatsApp OTP sign-in.

## 1. Start-up: config → pool → migrations → routes

```mermaid
sequenceDiagram
    participant Main as main()
    participant M as Application.module()
    participant DC as DatabaseConfig.fromEnv()
    participant AD as AuthDependencies.fromEnv()
    participant DF as DatabaseFactory
    participant PG as PostgreSQL

    Main->>M: embeddedServer(Netty, 8080)
    M->>DC: DATABASE_URL or DB_* env vars
    M->>AD: AuthConfig.fromEnv(), GoogleVerifier.fromConfig, WhatsAppOtpSender.fromConfig
    Note over AD: cloud mode without token or phone id<br/>throws, so start-up fails
    M->>DF: connect(config)
    DF->>PG: Hikari pool (max 5)
    DF->>PG: Flyway migrate classpath:db/migration
    DF->>DF: Exposed Database.connect(dataSource)
    M->>M: install plugins, register routes
```

Connection resolution (`DatabaseConfig.kt`). User/password env vars are `DB_USER`/`DB_PASSWORD`, falling back to `DATABASE_USER`/`DATABASE_PASSWORD`, then `cricket`/`cricket`.

| `DATABASE_URL` | Result |
|---|---|
| unset or blank | `jdbc:postgresql://${DB_HOST:-localhost}:${DB_PORT:-5433}/${DB_NAME:-cricket_scoring}`, with the user/password env vars |
| starts with `jdbc:` | used as-is, with the user/password env vars |
| `postgres://user:pass@host:port/db?params` or `postgresql://...` | rewritten to `jdbc:postgresql://host:port/db?params`. Credentials come from the URL (falling back to the env vars), port defaults to 5432, query string is kept |

## 2. Health check

`GET /health` → `DatabaseFactory.ping()` runs `SELECT 1` in a transaction. Any exception counts as "down".

- DB reachable → `200 {"status":"up","database":"up"}`
- otherwise → `503 {"status":"down","database":"down"}`

Fly.io polls this every 30 s (`fly.toml`).

## 3. Google sign-in

```mermaid
sequenceDiagram
    participant C as Client
    participant R as POST /auth/google
    participant S as AuthService
    participant G as GoogleVerifier
    participant I as IdentityService

    C->>R: {"idToken": "..."}
    R->>S: signInWithGoogle(idToken)
    S->>S: blank? -> 400 validation_error
    S->>G: verify(idToken)
    Note over G: audience in GOOGLE_CLIENT_IDS<br/>none set -> 503 google_not_configured<br/>invalid -> 401 invalid_google_token<br/>no email -> 400, unverified -> 400 email_not_verified
    G-->>S: GoogleProfile(sub, email lowercased, name, picture)
    S->>I: signIn(GOOGLE, sub, email, verified, name)
    Note over I: one transaction, advisory lock on GOOGLE:sub
    alt user_identities has (GOOGLE, sub)
        I->>I: touch identity (last used, email/name snapshot)
    else a user has primary_email = email
        alt that user already has another GOOGLE identity
            I-->>C: 409 account_conflict
        end
        I->>I: insert identity for that user; replace generated "User 1234" name
    else
        I->>I: insert users (primary_email, display_name = name or email local-part) + identity
    end
    I-->>S: ResolvedUser(user, isNewUser)
    S->>S: SessionService.start(userId, GOOGLE, device info)
    Note over S: INSERT sessions + first refresh_tokens row (SHA-256 of token)<br/>access JWT exp = now + ACCESS_TOKEN_TTL_SECONDS (900)
    S-->>C: 200 AuthResponse (accessToken + refreshToken)
```

Data written: on a new account, `users` (random UUID, `primary_email`, `display_name`, timestamps) and a `user_identities` row (`GOOGLE`, `sub`, email, name, picture). On every sign-in the identity's `last_used_at_epoch_ms` and profile snapshot are updated.

## 4. WhatsApp OTP sign-in

### 4a. Request a code

```mermaid
sequenceDiagram
    participant C as Client
    participant S as AuthService
    participant R as OtpChallengeRepository
    participant W as WhatsAppOtpSender

    C->>S: POST /auth/whatsapp/otp/request {"phone"}
    S->>S: OtpGuard: rollout flag + app version (403), country (400), per-IP bucket (429), per-phone wrong-code block (429)
    S->>S: PhoneNumber.normalize (strip spaces/dashes, ^\+[1-9]\d{7,14}$) else 400
    S->>S: code = SecureRandom 6 digits, challengeId = random UUID
    rect rgb(240, 240, 240)
    Note over S,R: one transaction
    S->>R: lockPhone (pg_advisory_xact_lock)
    S->>R: countedSendTimes(phone, last 24 h), SEND_FAILED excluded
    alt last send < 60 s ago
        S-->>C: 429 otp_cooldown + Retry-After
    else >= 3 sends in 10 min, or >= 10 in 24 h
        S-->>C: 429 otp_rate_limited + Retry-After
    end
    S->>S: OtpGuard.reserveGlobalSend (daily budget, same transaction) else 503 otp_unavailable
    S->>R: replaceLive: old live row -> SUPERSEDED, insert new row with HMAC(OTP_PEPPER, challengeId:code)
    end
    S->>W: sendOtp(phone, code)
    alt console mode
        W->>W: log.warn("OTP for {phone} is {code}")
    else cloud mode
        W->>W: POST graph.facebook.com/{WHATSAPP_GRAPH_VERSION}/{id}/messages<br/>(body + button parameters, 3 s connect / 10 s request timeout)
    end
    alt send failed
        S->>R: consume(challengeId, SEND_FAILED)
        S-->>C: 502 otp_delivery_failed (no cooldown started)
    end
    S->>R: recordDelivery(channel, wamid)
    S-->>C: 200 {"phone","challengeId","expiresInSeconds","resendAfterSeconds","attemptsAllowed","delivery"}
```

The row is written before delivery so the limits and the send are serialised per phone. A failed delivery is marked `SEND_FAILED`, which the limits ignore.

### 4b. Verify a code

```mermaid
flowchart TD
    A["POST /auth/whatsapp/otp/verify {phone, code, challengeId?}"] --> B{normalize phone}
    B -- bad --> E400a[400 validation_error]
    B --> C{"code matches ^\d{6}$, challengeId a UUID if present"}
    C -- no --> E400b[400 validation_error]
    C --> D["Atomic UPDATE: attempt_count += 1<br/>WHERE live AND not expired AND attempt_count < max_attempts<br/>(AND id = challengeId) RETURNING row"]
    D -- no row --> E401a[401 invalid_otp]
    D --> H{"HMAC matches (constant-time)?"}
    H -- no, attempts left --> E401c["401 invalid_otp (N attempt(s) left)"]
    H -- no, last attempt --> Lock[consumed_reason = MAX_ATTEMPTS] --> E429[429 otp_locked]
    H -- yes --> V["one transaction: consumed_reason = VERIFIED,<br/>IdentityService.signIn(WHATSAPP, phone):<br/>identity, else user with primary_phone_e164, else new user"]
    V --> J["SessionService.start: session + refresh token,<br/>mint 15-min access JWT"] --> OK[200 AuthResponse]
```

OTP defaults (`AuthConfig.kt`): length 6 (fixed), TTL 300 s, resend cooldown 60 s, 3 sends per 10 min, 10 sends per 24 h, 3 attempts per code.

## 5. Domain data (schema only)

The scoring tables (`players`, `teams`, `team_players`, `rule_profiles`, `tournaments`, `tournament_teams`, `fixtures`, `matches`, `match_events`, `memberships`) are created by `V1__foundation.sql` and mapped in `Tables.kt`. **Nothing reads or writes them yet.** Their shape points to a planned client→server sync:

- client-generated `TEXT` ids
- `version` for optimistic concurrency
- `updated_at_epoch_ms` indexes per owner, for incremental pulls
- `deleted_at_epoch_ms` for soft deletes
- `match_events` as an append-only, per-match ordered log (`UNIQUE (match_id, sequence)`) with JSONB payloads

This is an inference from the schema, not implemented behavior.

## 5. Sessions and token refresh

```mermaid
flowchart TD
    A["POST /auth/token/refresh {refreshToken}"] --> H["SHA-256(token)"]
    H --> L["SELECT refresh_tokens ... FOR UPDATE<br/>(one transaction)"]
    L -- not found --> X1[401 invalid_refresh_token]
    L --> S{"session revoked or past its 90-day cap,<br/>or token past its 30 days?"}
    S -- yes --> X1
    S --> U{token already used?}
    U -- no --> M[mark token used]
    U -- "yes, within 10 s and its child is unused" --> R[retire the unused child]
    U -- "yes, otherwise" --> V["revoke session (REUSE_DETECTED), commit"] --> X1
    M --> N["insert child token (parent_id = this token),<br/>expires = min(now + 30 d, session end); touch session"]
    R --> N
    N --> OK["200 AuthResponse with new access + refresh token"]
```

Every 🔒 request: the Ktor `jwt("access")` provider checks signature, `iss`, `aud` and `exp` (5 s leeway), then `SessionService.isActive(sub, sid)` checks the `sessions` row is not revoked and not expired (one primary-key lookup). Logout, logout-all and session revocation set `revoked_at_epoch_ms`, so they take effect on the next request.

## 6. Linking and unlinking sign-in methods

- `POST /auth/me/identities/google`: verify the Google token as for sign-in, then `IdentityService.link`: under the identity lock, if `(GOOGLE, sub)` belongs to the caller, return it; to someone else, `409 identity_already_linked`; if the caller already has a Google identity, `409 provider_already_linked`; otherwise insert it, and set `primary_email` if the caller has none and nobody else owns it.
- `POST /auth/me/identities/whatsapp`: the same OTP check as sign-in (atomic attempt count), then in one transaction mark the challenge VERIFIED and link `(WHATSAPP, phone)`. If linking throws, the transaction rolls back and the code stays unused.
- `DELETE /auth/me/identities/{id}`: `SELECT users ... FOR UPDATE` (so two parallel unlinks can't remove both of the last two), refuse if it's the only identity, delete it, and clear `primary_phone_e164` / `primary_email` if no remaining identity vouches for them.

## 7. Sign in with Apple and Apple notifications

```mermaid
sequenceDiagram
    participant App
    participant Apple
    participant S as AuthService
    participant V as AppleTokenVerifier
    participant K as Apple JWKS (cached)
    participant I as IdentityService

    App->>App: rawNonce = random
    App->>Apple: authorize(nonce = sha256hex(rawNonce))
    Apple-->>App: identityToken (+ fullName on first authorisation only)
    App->>S: POST /auth/apple {identityToken, nonce: rawNonce, fullName?}
    S->>V: verifyIdentityToken(token, rawNonce)
    V->>K: key for header kid (refetch if unknown, rate-limited)
    V->>V: RS256 signature, iss, aud in APPLE_CLIENT_IDS, exp (60 s leeway)
    V->>V: token.nonce == sha256hex(rawNonce)
    V-->>S: sub, email, email_verified, is_private_email
    S->>I: signIn(APPLE, sub, email, verified, relay?, name)
    Note over I: same rules as Google; a relay address never matches or becomes primary_email
    S-->>App: 200 AuthResponse (amr = apple)
```

Notifications: Apple posts `{"payload": JWT}` to `/auth/apple/notifications`. `AppleTokenVerifier.verifyNotification` applies the same key / `iss` / `aud` / `exp` checks and parses the `events` claim (a JSON string). For `consent-revoked` and `account-delete`, `IdentityService.onProviderRevoked` finds the user by `(APPLE, sub)` under a user-row lock (for `account-delete` it also removes the Apple identity unless it is the account's last), then `SessionService.revokeAllForUser` sets `revoked_reason = PROVIDER_REVOKED` on every live session.

## 8. Account deletion

```mermaid
flowchart TD
    A["DELETE /auth/me (🔒)"] --> R["AccountService.delete"]
    R --> T{"apple_tokens for this user?"}
    T -- yes --> D["decrypt (AES-GCM, identity id as AAD)<br/>POST appleid.apple.com/auth/revoke<br/>(best effort, failure logged)"]
    T -- no --> X
    D --> X["one transaction: SELECT users ... FOR UPDATE"]
    X --> Y{already DELETED?}
    Y -- yes --> OK[204]
    Y -- no --> S["revoke sessions (ACCOUNT_DELETED), clear device_name / user_agent / ip"]
    S --> M["delete memberships: the user's, and others' on resources the user owns"]
    M --> O["soft-delete players, teams, rule_profiles, tournaments, matches<br/>(deleted_at = now, version + 1)"]
    O --> I["delete user_identities (cascades apple_tokens), delete otp_challenges for the user's phones"]
    I --> U["users: status DELETED, deleted_at, primary_email / phone NULL, name 'Deleted user'"]
    U --> OK
```

Apple tokens come from the optional `authorizationCode` on `/auth/apple` and `/auth/me/identities/apple`: after the identity is resolved, `AppleTokenClient.exchangeCode` calls `appleid.apple.com/auth/token` with an ES256 client secret for the token's `aud`, and the refresh token is upserted encrypted into `apple_tokens`. Unlinking an Apple identity (when it isn't the last) revokes its token the same way before deleting it.

## 9. Abuse limits, events and the delivery webhook

- Every limit counter is a row in `rate_limit_buckets (bucket_key, window_start)`, bumped with one `INSERT ... ON CONFLICT DO UPDATE ... RETURNING count`. Windows are fixed and epoch-aligned (the daily one is a UTC day). Per-IP counters commit on their own, so refused requests still count; the global budget is bumped inside the send transaction, so a request refused by a per-phone check rolls it back.
- Wrong codes (`invalid_otp`, `otp_locked`) bump `otp_verify_fail:phone:<hmac>` for the hour; code requests check it before anything else per phone.
- `AuthService` records `auth_events` after each outcome, outside transactions. `SessionService` records `REFRESH_REUSE_DETECTED`.
- `POST /webhooks/whatsapp`: HMAC check over the raw body, then for each status `UPDATE otp_challenges SET delivery_status = ? WHERE provider_message_id = ? AND rank(current) < rank(new)`, and an `OTP_DELIVERY_STATUS` event per applied update.

