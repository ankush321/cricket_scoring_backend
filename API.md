# API Reference

- Base URL (local): `http://localhost:8080`
- Base URL (Fly.io): `https://cricket-scoring-backend.fly.dev` (app name from `fly.toml`; HTTPS is enforced)
- **Paths:** every auth route is served under **`/api/v1/auth/...`** (use this in new app builds) and, for older builds, under `/auth/...`. The two are identical; this document writes the short `/auth/...` form. The WhatsApp routes also answer to `/whatsapp/send-otp` and `/whatsapp/verify-otp`.
- **Headers the server reads:** `X-App-Version` (e.g. `1.4.0`; needed when `WHATSAPP_OTP_MIN_APP_VERSION` is set), `User-Agent` (shown in the sessions list), `Fly-Client-IP` (set by Fly's proxy; used for per-IP limits).
- Request and response bodies are JSON (`Content-Type: application/json`). Unknown request fields are ignored (`ignoreUnknownKeys = true`).
- **Streaming:** none. Every endpoint returns a single JSON response. There is no SSE, WebSocket or chunked streaming.
- **Authentication:** sign-in returns a short-lived access token (15 min) and a single-use refresh token. Routes marked 🔒 need `Authorization: Bearer <accessToken>`, and the token's session must still be live (a logged-out session is rejected immediately, not when the token expires).

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness and database check |
| GET | `/auth/providers` | Which sign-in methods the app should show |
| POST | `/auth/google` | Sign in with a Google ID token |
| POST | `/auth/apple` | Sign in with an Apple identity token |
| POST | `/auth/apple/notifications` | Apple server-to-server events (signed by Apple) |
| POST | `/auth/whatsapp/otp/request` (or `/send-otp`) | Send a 6-digit login code to a phone over WhatsApp |
| POST | `/auth/whatsapp/otp/verify` (or `/verify-otp`) | Exchange phone + code for tokens |
| POST | `/auth/token/refresh` | Swap a refresh token for a new access + refresh pair |
| GET 🔒 | `/auth/me` | The signed-in user |
| DELETE 🔒 | `/auth/me` | Delete the account, immediately and irreversibly |
| POST 🔒 | `/auth/logout` | End this session |
| POST 🔒 | `/auth/logout-all` | End every session of this user |
| GET 🔒 | `/auth/sessions` | List live sessions (signed-in devices) |
| DELETE 🔒 | `/auth/sessions/{id}` | End one of this user's sessions |
| GET 🔒 | `/auth/me/identities` | List linked sign-in methods |
| POST 🔒 | `/auth/me/identities/google` | Link a Google account to this account |
| POST 🔒 | `/auth/me/identities/apple` | Link an Apple ID to this account |
| POST 🔒 | `/auth/me/identities/whatsapp` | Link a WhatsApp number (OTP-verified) to this account |
| DELETE 🔒 | `/auth/me/identities/{id}` | Unlink a sign-in method (not the last one) |

| GET, POST | `/webhooks/whatsapp` | Meta WhatsApp delivery-status webhook (handshake + signed status updates) |

---

## Error format

Every error response has this body:

```json
{ "code": "invalid_otp", "message": "Invalid OTP." }
```

| `code` | HTTP | When |
|---|---|---|
| `validation_error` | 400 | Blank `idToken`, phone not E.164, OTP not 6 digits, `challengeId` not a UUID, malformed JSON or a missing required field, or `RequestValidationException` |
| `email_not_verified` | 400 | Google account email is not verified |
| `invalid_google_token` | 401 / 400 | Token failed verification (401) or the account has no email (400) |
| `invalid_otp` | 401 | No live challenge (unknown, expired, already used, superseded by a newer code, or `challengeId` mismatch), or wrong code. A wrong-code message says how many attempts are left |
| `unauthorized` | 401 | 🔒 route without a valid access token: missing, malformed, bad signature, wrong `iss`/`aud`, expired, or its session was logged out / revoked / expired |
| `invalid_refresh_token` | 401 | Unknown, expired, already-used (outside the grace window: this also revokes the session) or revoked refresh token |
| `not_found` | 404 | Unknown path, or a `DELETE` for a session or identity that isn't the caller's |
| `otp_cooldown` | 429 | New code requested within the resend cooldown. Has a `Retry-After` header |
| `rate_limited` | 429 | Too many code requests (`OTP_IP_SEND_LIMIT`) or verify attempts (`OTP_IP_VERIFY_LIMIT`) from this IP in the window. Has `Retry-After` |
| `feature_disabled` | 403 | WhatsApp sign-in is off, this phone isn't on the beta allowlist, or the app is older than `WHATSAPP_OTP_MIN_APP_VERSION` (or sent no `X-App-Version`) |
| `unsupported_country` | 400 | Phone's country code isn't in `WHATSAPP_ALLOWED_COUNTRY_CODES` |
| `invalid_signature` | 401 | WhatsApp webhook `X-Hub-Signature-256` missing or wrong |
| `otp_rate_limited` | 429 | More than `OTP_MAX_SENDS_PER_WINDOW` codes in `OTP_WINDOW_SECONDS`, or more than `OTP_MAX_SENDS_PER_DAY` in 24 h. Has a `Retry-After` header |
| `otp_locked` | 429 | This guess used the last attempt. The challenge is dead and the client must request a new code |
| `account_conflict` | 409 | Signing in with a Google account whose verified email belongs to an account that already has a *different* Google account linked |
| `identity_already_linked` | 409 | Linking a Google account or phone that already belongs to a different account |
| `provider_already_linked` | 409 | Linking a second Google account or phone; unlink the current one first |
| `last_identity` | 409 | Unlinking the account's only sign-in method |
| `internal_error` | 500 | Any unhandled exception |
| `otp_delivery_failed` | 502 | WhatsApp Cloud API call failed, timed out or returned non-2xx. Does not start the cooldown, so the client can retry straight away |
| `otp_unavailable` | 503 | The global daily send budget (`OTP_DAILY_SEND_BUDGET`) is used up. `Retry-After` points at the next UTC day. Offer another sign-in method |
| `webhook_not_configured` | 503 | `WHATSAPP_APP_SECRET` / `WHATSAPP_WEBHOOK_VERIFY_TOKEN` not set |
| `google_not_configured` | 503 | Neither `GOOGLE_CLIENT_IDS` nor `GOOGLE_CLIENT_ID` is set |
| `invalid_apple_token` | 401 | Apple token not a JWT, unknown signing key, bad signature, wrong `iss`/`aud`, or expired (60 s leeway) |
| `nonce_mismatch` | 401 | SHA-256 of the `nonce` sent here doesn't equal the token's `nonce` claim |
| `invalid_apple_notification` | 400 | Notification payload isn't a valid Apple-signed JWT with an `events` claim |
| `apple_not_configured` | 503 | `APPLE_CLIENT_IDS` is not set |

---

## `GET /health`

No request body.

**200 OK**
```json
{ "status": "up", "database": "up" }
```

**503 Service Unavailable** (database unreachable)
```json
{ "status": "down", "database": "down" }
```

```bash
curl -s http://localhost:8080/health
```

---

## `GET /auth/providers`

What the sign-in screen should offer. Send `X-App-Version`.

```json
{ "google": true, "apple": true, "whatsapp": { "enabled": true, "beta": true } }
```

`google` / `apple` are true when their client IDs are configured. `whatsapp.enabled` is false when the rollout is `off`, or the app version is below `WHATSAPP_OTP_MIN_APP_VERSION`. In `allowlist` mode it's true and the phone number decides at send time (`feature_disabled` if not allowlisted).

---

## `POST /auth/google`

Verifies a Google ID token whose audience must be one of `GOOGLE_CLIENT_IDS` (Android, iOS and web clients each have their own ID). Then, in order:
1. the account that already has this Google identity (by Google `sub`, never by email), or
2. the account whose verified contact email equals this Google email: the Google identity is attached to it, or
3. a new account.

It then starts a session. `isNewUser` is true only in case 3.

**Request** (`deviceName` is optional and shows up in the sessions list)
```json
{ "idToken": "<Google ID token JWT>", "deviceName": "Pixel 8" }
```

**200 OK**: `AuthResponse`
```json
{
  "user": {
    "id": "3f0b6c1e-2a8f-4d0e-9b8a-1c2d3e4f5a6b",
    "email": "player@example.com",
    "phone": null,
    "displayName": "Player One",
    "linkedProviders": ["GOOGLE"]
  },
  "isNewUser": true,
  "accessToken": "<HS256 JWT>",
  "tokenType": "Bearer",
  "expiresInSeconds": 900,
  "refreshToken": "<opaque, 43 chars>",
  "refreshTokenExpiresInSeconds": 2592000
}
```

Errors: `validation_error` (400), `invalid_google_token` (401/400), `email_not_verified` (400), `google_not_configured` (503).

```bash
curl -s -X POST http://localhost:8080/auth/google \
  -H 'Content-Type: application/json' \
  -d '{"idToken":"<token>"}'
```

---

## `POST /auth/apple`

Sign in with Apple. The app:
1. generates a random nonce and keeps it;
2. passes **SHA-256 (hex) of the nonce** to Apple's sign-in request;
3. sends the identity token and the **raw** nonce here.

The server verifies the token against Apple's published keys (`https://appleid.apple.com/auth/keys`, cached 24 h, refetched when Apple rotates): RS256 signature, `iss` = `https://appleid.apple.com`, `aud` one of `APPLE_CLIENT_IDS`, `exp` (60 s leeway), and the nonce. Then it finds or creates the account like Google: by Apple `sub`, else by a matching **verified, non-relay** email, else new.

**Request**
```json
{
  "identityToken": "<Apple identity token JWT>",
  "nonce": "<raw nonce>",
  "fullName": { "givenName": "Ravi", "familyName": "Kumar" },
  "deviceName": "iPhone 16",
  "authorizationCode": "<one-time code from the same Apple authorisation>"
}
```

`authorizationCode` is optional but recommended. When Apple revocation is configured (`APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_PRIVATE_KEY`, `APPLE_TOKEN_ENCRYPTION_KEY`), the server exchanges it for Apple's refresh token and stores that encrypted, solely so it can revoke the app's Apple access when the user deletes their account or unlinks Apple. A failed exchange never fails the sign-in. Codes are single-use and expire after 5 minutes, so send it straight away.

`fullName` is only given to the app on the user's **first** authorisation (Apple never puts it in the token), so send it whenever the app has it. It names a new account, or replaces a generated "User 1234" name.

Hide My Email: a `@privaterelay.appleid.com` address (or `is_private_email: true`) is stored on the identity but never becomes the account's contact email and never matches another account. Mail to it only arrives if your sending domain is registered with Apple.

**200 OK**: `AuthResponse`, as for Google, with `amr` = `apple`.

Errors: `validation_error` (400, blank token or nonce), `invalid_apple_token` (401), `nonce_mismatch` (401), `account_conflict` (409), `apple_not_configured` (503).

---

## `POST /auth/apple/notifications`

Register this URL in the Apple Developer portal (Sign in with Apple → server-to-server notification endpoint). Apple posts `{"payload": "<JWT>"}`, signed with the same keys and `aud` as identity tokens; its `events` claim names the event and the user's `sub`.

| Event | Effect |
|---|---|
| `consent-revoked` | All the user's sessions are revoked (`PROVIDER_REVOKED`). The Apple identity stays, since re-granting consent returns the same `sub`. |
| `account-delete` | Sessions revoked, and the Apple identity is removed if the account has another sign-in method (the last one is kept). |
| `email-disabled`, `email-enabled` | Acknowledged, no effect. |

**200 OK** for any verified payload, including an unknown `sub`. `invalid_apple_notification` (400) if it isn't Apple-signed.

---

## `POST /auth/whatsapp/otp/request`

Normalizes the phone number: spaces and dashes are removed, and the result must match `^\+[1-9]\d{7,14}$`. The server then checks the per-phone limits, creates a new challenge (any older live code for this phone stops working), and sends the code.

Codes are 6 digits from `SecureRandom` (leading zeros possible). Only `HMAC-SHA256(OTP_PEPPER, challengeId:code)` is stored.

**Request**
```json
{ "phone": "+91 98765-43210" }
```

**200 OK**: `OtpRequestResponse`
```json
{
  "phone": "+919876543210",
  "beta": true,
  "challengeId": "7da0d146-fbbd-4ce9-bc54-cb01e0fea343",
  "expiresInSeconds": 300,
  "resendAfterSeconds": 60,
  "attemptsAllowed": 3,
  "delivery": "console"
}
```

`delivery` is `"console"` when `WHATSAPP_OTP_MODE` is unset or `console`: the code is only written to the server log. It is `"whatsapp_cloud"` when the code was sent through the WhatsApp Cloud API. Send `challengeId` back on verify.

Checks, in order (defaults; all configurable):
1. **Beta rollout:** off / allowlist / on, plus the minimum app version → `feature_disabled` (403).
2. **Country allowlist**, if set → `unsupported_country` (400).
3. **Per IP:** 10 code requests per 10 minutes → `rate_limited` (429).
4. **Per phone:** 10 wrong codes in the current hour block new codes until the hour ends → `otp_rate_limited` (429).
5. **Per phone:** one code per 60 s, 3 per 10 minutes, 10 per 24 hours → `otp_cooldown` / `otp_rate_limited` (429). Failed deliveries don't count.
6. **Global:** 2000 codes per UTC day across all phones → `otp_unavailable` (503). A request refused by an earlier check doesn't use budget.

All counters are shared by every server machine (`rate_limit_buckets`).

The response carries `"beta": true` and the header `X-Auth-Beta: whatsapp-otp`.

Errors: `validation_error` (400), `unsupported_country` (400), `feature_disabled` (403), `rate_limited` (429), `otp_cooldown` (429), `otp_rate_limited` (429), `otp_delivery_failed` (502), `otp_unavailable` (503). The 429s and the 503 carry `Retry-After` in seconds.

```bash
curl -s -X POST http://localhost:8080/auth/whatsapp/otp/request \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210"}'
```

---

## `POST /auth/whatsapp/otp/verify`

**Request**
```json
{ "phone": "+919876543210", "code": "123456", "challengeId": "7da0d146-fbbd-4ce9-bc54-cb01e0fea343", "deviceName": "Pixel 8" }
```

`code` is trimmed and must be exactly 6 digits. `challengeId` is optional so older app builds keep working; without it the phone's current live challenge is used. Clients should send it.

**200 OK**: `AuthResponse`. The account is the one with this WhatsApp identity, else the one whose contact phone matches, else a new one. For a user created by phone, `email` is `null` and `displayName` is `"User " + last 4 digits`:
```json
{
  "user": {
    "id": "9a1d…",
    "email": null,
    "phone": "+919876543210",
    "displayName": "User 3210",
    "linkedProviders": ["WHATSAPP"]
  },
  "isNewUser": true,
  "accessToken": "<HS256 JWT>",
  "tokenType": "Bearer",
  "expiresInSeconds": 900,
  "refreshToken": "<opaque, 43 chars>",
  "refreshTokenExpiresInSeconds": 2592000
}
```

Each verify counts one attempt in a single atomic `UPDATE`, so parallel guesses can't go over the limit. The default is 3 attempts per code (`OTP_MAX_ATTEMPTS`). A wrong guess returns `invalid_otp` with the attempts left. The guess that uses the last attempt returns `otp_locked` and kills the challenge. A correct code works once.

Errors: `validation_error` (400), `feature_disabled` (403), `invalid_otp` (401), `otp_locked` (429), `rate_limited` (429, per-IP verify limit). Every wrong code also counts toward the per-phone hourly block on new codes.

```bash
curl -s -X POST http://localhost:8080/auth/whatsapp/otp/verify \
  -H 'Content-Type: application/json' \
  -d '{"phone":"+919876543210","code":"123456","challengeId":"<from request>"}'
```

---

## `POST /auth/token/refresh`

**Request**
```json
{ "refreshToken": "<from the last sign-in or refresh>" }
```

**200 OK**: `AuthResponse` with a **new** access token and a **new** refresh token. The old refresh token is now used up; store the new one.

Rules:
- Refresh tokens are single use. Each one lasts 30 days from when it was issued (`REFRESH_TOKEN_TTL_SECONDS`), so an app opened at least monthly stays signed in.
- A session lasts at most 90 days from sign-in (`SESSION_MAX_AGE_SECONDS`), however often it refreshes. Near the end, `refreshTokenExpiresInSeconds` shrinks to the time left. After that the user must sign in again.
- **Reuse detection:** presenting a refresh token that was already used revokes the whole session (every device holding a token from it is signed out). That is what happens if a token is copied and both copies get used.
- **Retry grace:** if the same token is presented again within 10 s (`REFRESH_REUSE_GRACE_SECONDS`) and the token it produced hasn't been used yet, the server assumes the client lost the response: it retires that unused token and returns a fresh pair. Clients should still avoid parallel refreshes (serialise them behind one in-flight request).

Errors: `validation_error` (400), `invalid_refresh_token` (401). On 401 the client should drop its tokens and show sign-in.

```bash
curl -s -X POST http://localhost:8080/auth/token/refresh \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<token>"}'
```

---

## `GET /auth/me` 🔒

**200 OK**: `AuthUserDto`
```json
{ "id": "9a1d…", "phone": "+919876543210", "displayName": "User 3210", "linkedProviders": ["WHATSAPP", "GOOGLE"] }
```

Errors: `unauthorized` (401).

---

## `DELETE /auth/me` 🔒

Deletes the account **immediately and irreversibly**. No body. **204 No Content.** The app should ask the user to confirm first.

What happens:
1. If the user signed in with Apple and the server holds an Apple token, it is revoked at Apple (best effort: if Apple is unreachable the deletion still goes ahead and the failure is logged).
2. In one transaction:
   - every session is revoked (`ACCOUNT_DELETED`) and its device name, user agent and IP are cleared;
   - all sign-in methods are removed, along with their stored Apple tokens;
   - the OTP history for the user's phone numbers is deleted;
   - everything the user owns (players, teams, rule profiles, tournaments, matches) is soft-deleted: `deleted_at_epoch_ms` is set and `version` is bumped;
   - the user's memberships, and other users' memberships on resources the user owned, are deleted;
   - the user row becomes a tombstone: `status = DELETED`, no email or phone, name "Deleted user". It is kept only so foreign keys on the soft-deleted data stay valid.

Afterwards every access and refresh token of the account is rejected, and signing in again with the same phone, Google or Apple account creates a **new** account.

Errors: `unauthorized` (401).

---

## `POST /auth/logout` 🔒 and `POST /auth/logout-all` 🔒

No body. **204 No Content.** `logout` revokes the caller's session; `logout-all` revokes every session of the user, including the caller's. Revoked sessions' refresh tokens stop working and their access tokens get `401` straight away.

Errors: `unauthorized` (401).

---

## `GET /auth/sessions` 🔒 and `DELETE /auth/sessions/{id}` 🔒

`GET` returns the user's live sessions, most recently used first:
```json
[
  {
    "id": "0190…",
    "authMethod": "WHATSAPP",
    "deviceName": "Pixel 8",
    "userAgent": "okhttp/4.12.0",
    "createdAtEpochMs": 1763222400000,
    "lastUsedAtEpochMs": 1763226000000,
    "expiresAtEpochMs": 1770998400000,
    "current": true
  }
]
```

`lastUsedAtEpochMs` moves on sign-in and refresh, not on every API call. `DELETE` ends one of the caller's sessions: **204**, or `not_found` (404) if the id isn't one of the caller's live sessions (other users' sessions look the same as missing ones).

---

## Linked sign-in methods 🔒

An account can have one Google, one Apple and one WhatsApp identity. Signing in with any of them opens the same account. Linking is explicit: sign in with one method, then link the other. Sign-in only auto-attaches on a matching *verified* contact (same verified email for Google or Apple, never an Apple private-relay address; same phone for WhatsApp); it never merges accounts.

### `GET /auth/me/identities`

```json
[
  { "id": "0190…", "provider": "WHATSAPP", "isPrivateRelay": false, "phone": "+919876543210", "createdAtEpochMs": 1763222400000, "lastUsedAtEpochMs": 1763226000000 },
  { "id": "0190…", "provider": "GOOGLE", "email": "ravi@example.com", "isPrivateRelay": false, "createdAtEpochMs": 1763222500000, "lastUsedAtEpochMs": 1763222500000 }
]
```

### `POST /auth/me/identities/google`

```json
{ "idToken": "<Google ID token>" }
```

**200 OK**: the linked identity (as in the list). Linking the Google account you already linked returns it again. If the account has no contact email yet, the Google email becomes it; if its name is still the generated "User 1234", the Google name replaces it.

Errors: `invalid_google_token` (401), `identity_already_linked` (409), `provider_already_linked` (409), `google_not_configured` (503).

### `POST /auth/me/identities/apple`

Same body as `POST /auth/apple` without `deviceName` (`authorizationCode` included). **200 OK**: the linked identity. A verified non-relay Apple email becomes the contact email if the account has none.

Errors: as for `/auth/apple`, plus `identity_already_linked` (409) and `provider_already_linked` (409).

### `POST /auth/me/identities/whatsapp`

First call `POST /auth/whatsapp/otp/request` for the phone (same limits as sign-in), then:

```json
{ "phone": "+919876543210", "code": "123456", "challengeId": "<from the request>" }
```

**200 OK**: the linked identity. If the account has no contact phone, this becomes it. A wrong code counts as an attempt exactly like sign-in. If linking fails with a 409, the code is not used up.

Errors: `validation_error` (400), `invalid_otp` (401), `otp_locked` (429), `identity_already_linked` (409), `provider_already_linked` (409).

### `DELETE /auth/me/identities/{id}`

**204 No Content.** The account's contact email or phone is cleared if only that identity vouched for it, so signing in with the removed method later creates a new account instead of re-attaching. Existing sessions are not ended.

Errors: `last_identity` (409), `not_found` (404).

---

## `GET /webhooks/whatsapp` and `POST /webhooks/whatsapp`

Register `https://<host>/webhooks/whatsapp` in the Meta app (WhatsApp → Configuration → Webhook) and subscribe to `messages`.

- **GET** is Meta's one-time handshake: with `hub.mode=subscribe` and `hub.verify_token` equal to `WHATSAPP_WEBHOOK_VERIFY_TOKEN`, the server echoes `hub.challenge` as plain text. Otherwise 403.
- **POST** carries status updates. The server checks `X-Hub-Signature-256` (HMAC-SHA256 of the raw body with `WHATSAPP_APP_SECRET`; 401 `invalid_signature` if wrong). For each `entry[].changes[].value.statuses[]` whose `id` matches a code we sent, it sets the challenge's `delivery_status`. Statuses only move forward (SENT → FAILED → DELIVERED → READ), because Meta may deliver them out of order. Each applied status is recorded as an `OTP_DELIVERY_STATUS` event. Returns 200 for any validly signed body, including statuses for unknown messages.

Both return `webhook_not_configured` (503) until the two settings are set.

---

## Auth events (`auth_events`)

An append-only audit trail and beta funnel. Not exposed over HTTP; query the database.

| `event_type` | When |
|---|---|
| `OTP_SENT` / `OTP_SEND_FAILED` | A code was sent / delivery to Meta failed |
| `OTP_RATE_LIMITED` | A code request or verify was refused by any limit (`error_code` says which) |
| `FEATURE_DISABLED` | WhatsApp refused by the rollout flag |
| `OTP_VERIFY_FAILED` | Wrong code (`invalid_otp`) or lockout (`otp_locked`) |
| `OTP_DELIVERY_STATUS` | Meta reported SENT / DELIVERED / READ / FAILED (`metadata.status`) |
| `LOGIN_SUCCEEDED` | Any sign-in (`provider`, `metadata.newUser`) |
| `REFRESH_REUSE_DETECTED` | A used refresh token was replayed; the session was revoked |
| `ACCOUNT_DELETED` | `DELETE /auth/me` |

WhatsApp events have `is_beta_flow = true`, as do sessions started through WhatsApp. Phones are stored only as `phone_hash` (HMAC with `OTP_PEPPER`), never raw. Example funnel for the last 7 days:

```sql
SELECT date_trunc('day', to_timestamp(created_at_epoch_ms / 1000)) AS day,
       count(*) FILTER (WHERE event_type = 'OTP_SENT') AS sent,
       count(*) FILTER (WHERE event_type = 'OTP_DELIVERY_STATUS' AND metadata->>'status' IN ('DELIVERED', 'READ')) AS delivered,
       count(*) FILTER (WHERE event_type = 'LOGIN_SUCCEEDED' AND provider = 'WHATSAPP') AS logins
FROM auth_events
WHERE created_at_epoch_ms > (extract(epoch FROM now()) - 7 * 86400) * 1000
GROUP BY 1 ORDER BY 1;
```

---

## Access token (JWT)

Minted by `JwtService.mint`:

| Claim | Value |
|---|---|
| `alg` | HS256, signed with `JWT_SECRET` |
| `iss` | `JWT_ISSUER` (default `cricket-scoring-backend`) |
| `aud` | `JWT_AUDIENCE` (default `cricket-scoring-app`) |
| `sub` | user UUID |
| `sid` | session UUID |
| `amr` | sign-in method: `google`, `apple` or `whatsapp` |
| `jti` | random UUID |
| `iat` | issue time |
| `exp` | `iat + ACCESS_TOKEN_TTL_SECONDS` (default 900 = 15 min) |

No email or phone is put in the token. Treat it as opaque on the client: use `expiresInSeconds`, and call `/auth/me` for profile data. The server allows 5 s of clock skew.

**Client storage:** keep the access token in memory. Store the refresh token in the iOS Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) or, on Android, encrypted with an Android Keystore key. Never log either token or put it in a URL.
