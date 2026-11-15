package com.example.cricketscoring.backend.auth

import com.example.cricketscoring.backend.AppleTokens
import com.example.cricketscoring.backend.UserIdentities
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

data class StoredAppleToken(val identityId: UUID, val clientId: String, val cipher: ByteArray)

/** `apple_tokens` queries. Rows disappear with their identity (ON DELETE CASCADE). */
class AppleTokenRepository {
    fun upsert(identityId: UUID, clientId: String, cipher: ByteArray, nowEpochMs: Long) {
        transaction {
            AppleTokens.upsert(onUpdateExclude = listOf(AppleTokens.createdAtEpochMs)) {
                it[AppleTokens.identityId] = identityId.toKotlinUuid()
                it[AppleTokens.clientId] = clientId
                it[refreshTokenCipher] = cipher
                it[createdAtEpochMs] = nowEpochMs
                it[updatedAtEpochMs] = nowEpochMs
            }
        }
    }

    fun find(identityId: UUID): StoredAppleToken? = transaction {
        AppleTokens.selectAll().where { AppleTokens.identityId eq identityId.toKotlinUuid() }.firstOrNull()?.let {
            StoredAppleToken(it[AppleTokens.identityId].toJavaUuid(), it[AppleTokens.clientId], it[AppleTokens.refreshTokenCipher])
        }
    }

    fun findForUser(userId: UUID): List<StoredAppleToken> = transaction {
        AppleTokens.join(UserIdentities, JoinType.INNER, AppleTokens.identityId, UserIdentities.id)
            .selectAll()
            .where { UserIdentities.userId eq userId.toKotlinUuid() }
            .map { StoredAppleToken(it[AppleTokens.identityId].toJavaUuid(), it[AppleTokens.clientId], it[AppleTokens.refreshTokenCipher]) }
    }
}
