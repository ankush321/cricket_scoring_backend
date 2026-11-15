package com.example.cricketscoring.backend.auth

import com.example.cricketscoring.backend.UserIdentities
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/** What a provider vouched for, after its token or OTP was verified. */
data class VerifiedIdentity(
    val provider: AuthMethod,
    val subject: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val isPrivateRelay: Boolean = false,
    val phoneE164: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
) {
    /** An email we may use to match or label an account: verified and not an Apple relay address. */
    val linkableEmail: String? get() = email?.takeIf { emailVerified && !isPrivateRelay }
}

data class IdentityRecord(
    val id: UUID,
    val userId: UUID,
    val provider: AuthMethod,
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val isPrivateRelay: Boolean,
    val phoneE164: String?,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
)

/** `user_identities` queries. Each function joins the caller's transaction when there is one. */
class IdentityRepository {
    /** Serialises find-or-create / link for one provider identity until the surrounding transaction ends. */
    fun lock(provider: AuthMethod, subject: String) {
        transaction {
            exec("SELECT pg_advisory_xact_lock(hashtext(?))", listOf(TextColumnType() to "identity:${provider.name}:$subject")) { }
        }
    }

    fun find(provider: AuthMethod, subject: String): IdentityRecord? = transaction {
        UserIdentities.selectAll()
            .where { (UserIdentities.provider eq provider.name) and (UserIdentities.providerSubject eq subject) }
            .firstOrNull()?.toIdentity()
    }

    fun listForUser(userId: UUID): List<IdentityRecord> = transaction {
        UserIdentities.selectAll()
            .where { UserIdentities.userId eq userId.toKotlinUuid() }
            .orderBy(UserIdentities.createdAtEpochMs to SortOrder.ASC)
            .map { it.toIdentity() }
    }

    fun insert(userId: UUID, identity: VerifiedIdentity, nowEpochMs: Long): IdentityRecord {
        val id = Uuid.random()
        transaction {
            UserIdentities.insert {
                it[UserIdentities.id] = id
                it[UserIdentities.userId] = userId.toKotlinUuid()
                it[provider] = identity.provider.name
                it[providerSubject] = identity.subject
                it[email] = identity.email
                it[emailVerified] = identity.emailVerified
                it[isPrivateRelay] = identity.isPrivateRelay
                it[phoneE164] = identity.phoneE164
                it[displayName] = identity.displayName
                it[avatarUrl] = identity.avatarUrl
                it[createdAtEpochMs] = nowEpochMs
                it[lastUsedAtEpochMs] = nowEpochMs
            }
        }
        return IdentityRecord(
            id.toJavaUuid(), userId, identity.provider, identity.subject, identity.email, identity.emailVerified,
            identity.isPrivateRelay, identity.phoneE164, nowEpochMs, nowEpochMs,
        )
    }

    /** Records a sign-in and refreshes the provider's profile snapshot (emails and names can change). */
    fun touch(id: UUID, identity: VerifiedIdentity, nowEpochMs: Long) {
        transaction {
            UserIdentities.update({ UserIdentities.id eq id.toKotlinUuid() }) {
                it[lastUsedAtEpochMs] = nowEpochMs
                identity.email?.let { email -> it[UserIdentities.email] = email }
                it[emailVerified] = identity.emailVerified
                identity.displayName?.let { name -> it[displayName] = name }
                identity.avatarUrl?.let { url -> it[avatarUrl] = url }
            }
        }
    }

    fun delete(id: UUID, userId: UUID): Boolean = transaction {
        UserIdentities.deleteWhere {
            (UserIdentities.id eq id.toKotlinUuid()) and (UserIdentities.userId eq userId.toKotlinUuid())
        } > 0
    }

    private fun ResultRow.toIdentity() = IdentityRecord(
        id = this[UserIdentities.id].toJavaUuid(),
        userId = this[UserIdentities.userId].toJavaUuid(),
        provider = AuthMethod.valueOf(this[UserIdentities.provider]),
        subject = this[UserIdentities.providerSubject],
        email = this[UserIdentities.email],
        emailVerified = this[UserIdentities.emailVerified],
        isPrivateRelay = this[UserIdentities.isPrivateRelay],
        phoneE164 = this[UserIdentities.phoneE164],
        createdAtEpochMs = this[UserIdentities.createdAtEpochMs],
        lastUsedAtEpochMs = this[UserIdentities.lastUsedAtEpochMs],
    )
}
