package com.example.cricketscoring.backend.auth

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** Device details recorded on a session, for the "signed-in devices" list. */
data class ClientInfo(
    val deviceName: String? = null,
    val userAgent: String? = null,
    val ip: String? = null,
    /** From the `X-App-Version` header. */
    val appVersion: String? = null,
)

/** The authenticated caller, from a verified access token whose session is still live. */
data class AccessPrincipal(val userId: UUID, val sessionId: UUID)

data class IssuedTokens(
    val userId: UUID,
    val sessionId: UUID,
    val access: AuthToken,
    val refreshToken: String,
    val refreshTokenExpiresInSeconds: Long,
)

/**
 * Sessions with rotating refresh tokens.
 *
 * Every refresh marks the presented token used and issues a child. Presenting a used token again
 * means it was copied, so the whole session is revoked (reuse detection). The one exception is a
 * retry within [AuthConfig.refreshReuseGraceSeconds] while the child is still unused: the client
 * probably lost the response, so the unused child is retired and a fresh one issued.
 */
class SessionService(
    private val config: AuthConfig,
    private val sessions: SessionRepository,
    private val jwtService: JwtService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val events: AuthEventRecorder? = null,
) {
    private val random = SecureRandom()

    fun start(userId: UUID, method: AuthMethod, client: ClientInfo): IssuedTokens {
        val now = clock()
        val sessionId = Uuid.random()
        val sessionExpiresAt = now + config.sessionMaxAgeSeconds * 1_000
        val refresh = newRefreshToken()
        val refreshExpiresAt = minOf(now + config.refreshTokenTtlSeconds * 1_000, sessionExpiresAt)
        transaction {
            sessions.insertSession(sessionId, userId.toKotlinUuid(), method, client.trimmed(), now, sessionExpiresAt)
            sessions.insertRefreshToken(Uuid.random(), sessionId, hash(refresh), null, now, refreshExpiresAt)
        }
        return issued(userId, sessionId.toJavaUuid(), method, refresh, refreshExpiresAt, now)
    }

    fun refresh(rawToken: String): IssuedTokens {
        val token = rawToken.trim()
        if (token.isEmpty() || token.length > 128) throw invalidRefresh()
        val now = clock()
        // Decide inside the transaction, throw after it: a reuse-triggered revoke must commit.
        val outcome: RefreshOutcome = transaction {
            val row = sessions.findTokenForUpdate(hash(token)) ?: return@transaction RefreshOutcome.Invalid
            val session = sessions.findSessionForUpdate(row.sessionId) ?: return@transaction RefreshOutcome.Invalid
            if (session.revokedAtEpochMs != null || session.expiresAtEpochMs <= now || row.expiresAtEpochMs <= now) {
                return@transaction RefreshOutcome.Invalid
            }
            val usedAt = row.usedAtEpochMs
            if (usedAt != null) {
                val unusedChild = sessions.findUnusedChild(row.id)
                val withinGrace = now - usedAt <= config.refreshReuseGraceSeconds * 1_000
                if (!withinGrace || unusedChild == null) {
                    sessions.revoke(session.id, null, SessionRevokedReason.REUSE_DETECTED, now)
                    return@transaction RefreshOutcome.Reused(session.userId.toJavaUuid())
                }
                sessions.markTokenUsed(unusedChild.id, now)
            } else {
                sessions.markTokenUsed(row.id, now)
            }
            val refresh = newRefreshToken()
            val refreshExpiresAt = minOf(now + config.refreshTokenTtlSeconds * 1_000, session.expiresAtEpochMs)
            sessions.insertRefreshToken(Uuid.random(), session.id, hash(refresh), row.id, now, refreshExpiresAt)
            sessions.touchSession(session.id, now)
            RefreshOutcome.Issued(
                issued(session.userId.toJavaUuid(), session.id.toJavaUuid(), session.authMethod, refresh, refreshExpiresAt, now),
            )
        }
        return when (outcome) {
            is RefreshOutcome.Issued -> outcome.tokens
            is RefreshOutcome.Reused -> {
                events?.record(AuthEventType.REFRESH_REUSE_DETECTED, AuthEventOutcome.DENIED, userId = outcome.userId)
                throw invalidRefresh()
            }
            RefreshOutcome.Invalid -> throw invalidRefresh()
        }
    }

    fun isActive(principal: AccessPrincipal): Boolean =
        sessions.isActive(principal.sessionId.toKotlinUuid(), principal.userId.toKotlinUuid(), clock())

    fun logout(principal: AccessPrincipal) {
        sessions.revoke(principal.sessionId.toKotlinUuid(), principal.userId.toKotlinUuid(), SessionRevokedReason.LOGOUT, clock())
    }

    fun logoutAll(principal: AccessPrincipal) {
        sessions.revokeAllForUser(principal.userId.toKotlinUuid(), SessionRevokedReason.LOGOUT_ALL, clock())
    }

    /** Ends every session of [userId], e.g. after the provider says consent was revoked. */
    fun revokeAllForUser(userId: UUID, reason: SessionRevokedReason): Int =
        sessions.revokeAllForUser(userId.toKotlinUuid(), reason, clock())

    fun list(principal: AccessPrincipal): List<SessionDto> =
        sessions.listActive(principal.userId.toKotlinUuid(), clock()).map { row ->
            SessionDto(
                id = row.id.toString(),
                authMethod = row.authMethod.name,
                deviceName = row.deviceName,
                userAgent = row.userAgent,
                createdAtEpochMs = row.createdAtEpochMs,
                lastUsedAtEpochMs = row.lastUsedAtEpochMs,
                expiresAtEpochMs = row.expiresAtEpochMs,
                current = row.id.toJavaUuid() == principal.sessionId,
            )
        }

    fun revoke(principal: AccessPrincipal, rawSessionId: String) {
        val id = runCatching { Uuid.parse(rawSessionId) }.getOrNull()
        val revoked = id != null &&
            sessions.revoke(id, principal.userId.toKotlinUuid(), SessionRevokedReason.REVOKED_BY_USER, clock())
        if (!revoked) throw AuthException("not_found", "No such active session.", HttpStatusCode.NotFound)
    }

    private fun issued(userId: UUID, sessionId: UUID, method: AuthMethod, refresh: String, refreshExpiresAt: Long, now: Long) =
        IssuedTokens(
            userId = userId,
            sessionId = sessionId,
            access = jwtService.mint(userId, sessionId, method),
            refreshToken = refresh,
            refreshTokenExpiresInSeconds = (refreshExpiresAt - now) / 1_000,
        )

    /** 256 random bits, base64url without padding (43 chars). */
    private fun newRefreshToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())

    private fun invalidRefresh() =
        AuthException("invalid_refresh_token", "Refresh token is invalid, expired or revoked. Sign in again.", HttpStatusCode.Unauthorized)

    private fun ClientInfo.trimmed() = copy(
        deviceName = deviceName?.trim()?.take(100)?.takeIf { it.isNotEmpty() },
        userAgent = userAgent?.take(512),
        ip = ip?.take(64),
    )

    private sealed interface RefreshOutcome {
        data class Issued(val tokens: IssuedTokens) : RefreshOutcome
        data object Invalid : RefreshOutcome
        data class Reused(val userId: UUID) : RefreshOutcome
    }
}
