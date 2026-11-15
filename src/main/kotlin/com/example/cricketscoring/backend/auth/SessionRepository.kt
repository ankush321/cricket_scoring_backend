package com.example.cricketscoring.backend.auth

import com.example.cricketscoring.backend.RefreshTokens
import com.example.cricketscoring.backend.Sessions
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

data class SessionRow(
    val id: Uuid,
    val userId: Uuid,
    val authMethod: AuthMethod,
    val deviceName: String?,
    val userAgent: String?,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val revokedAtEpochMs: Long?,
)

data class RefreshTokenRow(
    val id: Uuid,
    val sessionId: Uuid,
    val expiresAtEpochMs: Long,
    val usedAtEpochMs: Long?,
)

enum class SessionRevokedReason { LOGOUT, LOGOUT_ALL, REVOKED_BY_USER, REUSE_DETECTED, PROVIDER_REVOKED, ACCOUNT_DELETED }

/** `sessions` / `refresh_tokens` queries. Each function joins the caller's transaction when there is one. */
class SessionRepository {
    fun insertSession(
        id: Uuid,
        userId: Uuid,
        method: AuthMethod,
        client: ClientInfo,
        nowEpochMs: Long,
        expiresAtEpochMs: Long,
    ) {
        transaction {
            Sessions.insert {
                it[Sessions.id] = id
                it[Sessions.userId] = userId
                it[authMethod] = method.name
                it[deviceName] = client.deviceName
                it[userAgent] = client.userAgent
                it[ipAtCreation] = client.ip
                it[createdAtEpochMs] = nowEpochMs
                it[lastUsedAtEpochMs] = nowEpochMs
                it[Sessions.expiresAtEpochMs] = expiresAtEpochMs
                it[isBetaFlow] = method == AuthMethod.WHATSAPP
            }
        }
    }

    fun insertRefreshToken(id: Uuid, sessionId: Uuid, tokenHash: ByteArray, parentId: Uuid?, nowEpochMs: Long, expiresAtEpochMs: Long) {
        transaction {
            RefreshTokens.insert {
                it[RefreshTokens.id] = id
                it[RefreshTokens.sessionId] = sessionId
                it[RefreshTokens.tokenHash] = tokenHash
                it[RefreshTokens.parentId] = parentId
                it[createdAtEpochMs] = nowEpochMs
                it[RefreshTokens.expiresAtEpochMs] = expiresAtEpochMs
            }
        }
    }

    /** Locks the token row until the surrounding transaction ends, so concurrent refreshes of one token run one at a time. */
    fun findTokenForUpdate(tokenHash: ByteArray): RefreshTokenRow? = transaction {
        RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq tokenHash }.forUpdate().firstOrNull()?.toToken()
    }

    fun findUnusedChild(parentId: Uuid): RefreshTokenRow? = transaction {
        RefreshTokens.selectAll()
            .where { (RefreshTokens.parentId eq parentId) and RefreshTokens.usedAtEpochMs.isNull() }
            .firstOrNull()?.toToken()
    }

    fun markTokenUsed(id: Uuid, nowEpochMs: Long) {
        transaction {
            RefreshTokens.update({ RefreshTokens.id eq id }) { it[usedAtEpochMs] = nowEpochMs }
        }
    }

    fun findSession(id: Uuid): SessionRow? = transaction {
        Sessions.selectAll().where { Sessions.id eq id }.firstOrNull()?.toSession()
    }

    /** Like [findSession] but locks the row, so a refresh and a revoke of the same session can't interleave. */
    fun findSessionForUpdate(id: Uuid): SessionRow? = transaction {
        Sessions.selectAll().where { Sessions.id eq id }.forUpdate().firstOrNull()?.toSession()
    }

    /** Drops device details from every session of [userId] (account deletion). */
    fun scrubDeviceInfo(userId: Uuid) {
        transaction {
            Sessions.update({ Sessions.userId eq userId }) {
                it[deviceName] = null
                it[userAgent] = null
                it[ipAtCreation] = null
            }
        }
    }

    fun touchSession(id: Uuid, nowEpochMs: Long) {
        transaction {
            Sessions.update({ Sessions.id eq id }) { it[lastUsedAtEpochMs] = nowEpochMs }
        }
    }

    fun isActive(id: Uuid, userId: Uuid, nowEpochMs: Long): Boolean = transaction {
        Sessions.selectAll()
            .where {
                (Sessions.id eq id) and (Sessions.userId eq userId) and
                    Sessions.revokedAtEpochMs.isNull() and (Sessions.expiresAtEpochMs greater nowEpochMs)
            }
            .any()
    }

    /** Revokes one live session of [userId]. Returns false if there was no such live session. */
    fun revoke(id: Uuid, userId: Uuid?, reason: SessionRevokedReason, nowEpochMs: Long): Boolean = transaction {
        Sessions.update({
            val base = (Sessions.id eq id) and Sessions.revokedAtEpochMs.isNull()
            if (userId == null) base else base and (Sessions.userId eq userId)
        }) {
            it[revokedAtEpochMs] = nowEpochMs
            it[revokedReason] = reason.name
        } > 0
    }

    fun revokeAllForUser(userId: Uuid, reason: SessionRevokedReason, nowEpochMs: Long, exceptSessionId: Uuid? = null): Int = transaction {
        Sessions.update({
            val base = (Sessions.userId eq userId) and Sessions.revokedAtEpochMs.isNull()
            if (exceptSessionId == null) base else base and (Sessions.id neq exceptSessionId)
        }) {
            it[revokedAtEpochMs] = nowEpochMs
            it[revokedReason] = reason.name
        }
    }

    fun listActive(userId: Uuid, nowEpochMs: Long): List<SessionRow> = transaction {
        Sessions.selectAll()
            .where { (Sessions.userId eq userId) and Sessions.revokedAtEpochMs.isNull() and (Sessions.expiresAtEpochMs greater nowEpochMs) }
            .orderBy(Sessions.lastUsedAtEpochMs to SortOrder.DESC)
            .map { it.toSession() }
    }

    private fun ResultRow.toToken() = RefreshTokenRow(
        id = this[RefreshTokens.id],
        sessionId = this[RefreshTokens.sessionId],
        expiresAtEpochMs = this[RefreshTokens.expiresAtEpochMs],
        usedAtEpochMs = this[RefreshTokens.usedAtEpochMs],
    )

    private fun ResultRow.toSession() = SessionRow(
        id = this[Sessions.id],
        userId = this[Sessions.userId],
        authMethod = AuthMethod.valueOf(this[Sessions.authMethod]),
        deviceName = this[Sessions.deviceName],
        userAgent = this[Sessions.userAgent],
        createdAtEpochMs = this[Sessions.createdAtEpochMs],
        lastUsedAtEpochMs = this[Sessions.lastUsedAtEpochMs],
        expiresAtEpochMs = this[Sessions.expiresAtEpochMs],
        revokedAtEpochMs = this[Sessions.revokedAtEpochMs],
    )
}
