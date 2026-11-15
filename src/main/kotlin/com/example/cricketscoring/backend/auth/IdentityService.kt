package com.example.cricketscoring.backend.auth

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

data class ResolvedUser(val user: UserRecord, val isNewUser: Boolean)

/**
 * Maps provider identities to accounts.
 *
 * Rules (see AUTH_DESIGN.md §2):
 * - Sign-in finds the account by (provider, subject). Failing that it attaches to the account whose
 *   *verified* contact matches: same verified, non-relay email for Google/Apple, same phone for WhatsApp.
 *   Otherwise it creates an account.
 * - Anything else (WhatsApp account adds Google, ...) is an explicit [link] by a signed-in user.
 * - One identity per provider per account; an identity belongs to exactly one account.
 *
 * Each operation runs in one transaction under a per-identity advisory lock, so concurrent first
 * sign-ins resolve to one account. Callers may wrap it in a larger transaction.
 */
class IdentityService(
    private val users: UserRepository,
    private val identities: IdentityRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun signIn(identity: VerifiedIdentity): ResolvedUser = transaction {
        val now = clock()
        identities.lock(identity.provider, identity.subject)

        identities.find(identity.provider, identity.subject)?.let { existing ->
            identities.touch(existing.id, identity, now)
            val user = users.findById(existing.userId) ?: error("identity ${existing.id} has no user")
            check(!user.deleted) { "identity ${existing.id} points at deleted user ${user.id}" }
            return@transaction ResolvedUser(user, isNewUser = false)
        }

        val match = when (identity.provider) {
            AuthMethod.GOOGLE, AuthMethod.APPLE -> identity.linkableEmail?.let(users::findByPrimaryEmail)
            AuthMethod.WHATSAPP -> identity.phoneE164?.let(users::findByPrimaryPhone)
        }
        if (match != null) {
            if (identities.listForUser(match.id).any { it.provider == identity.provider }) {
                // e.g. a second Google account with the same email as one already linked.
                throw AuthException(
                    "account_conflict",
                    "This email belongs to an account that signs in with a different ${identity.provider.label()} account.",
                    HttpStatusCode.Conflict,
                )
            }
            identities.insert(match.id, identity, now)
            identity.displayName?.let { users.replaceGeneratedDisplayName(match.id, it, now) }
            return@transaction ResolvedUser(users.findById(match.id)!!, isNewUser = false)
        }

        // A unique violation here (another provider created the same contact concurrently) makes
        // Exposed retry the transaction, and the retry takes the "match" branch.
        val user = users.insert(
            id = UUID.randomUUID(),
            email = identity.linkableEmail,
            phoneE164 = identity.phoneE164,
            displayName = identity.displayName?.takeIf { it.isNotBlank() }
                ?: identity.linkableEmail?.substringBefore('@')
                ?: identity.phoneE164?.let(UserRepository::generatedName)
                ?: "Player",
            nowEpochMs = now,
        )
        identities.insert(user.id, identity, now)
        ResolvedUser(user, isNewUser = true)
    }

    /** Attaches [identity] to the signed-in user. Idempotent if it's already theirs. */
    fun link(userId: UUID, identity: VerifiedIdentity): IdentityRecord = transaction {
        val now = clock()
        identities.lock(identity.provider, identity.subject)
        // Same lock account deletion takes: a link can't land on an account that's being deleted.
        val owner = users.lockForUpdate(userId)
        if (owner == null || owner.deleted) {
            throw AuthException("unauthorized", "Account no longer exists.", HttpStatusCode.Unauthorized)
        }
        identities.find(identity.provider, identity.subject)?.let { existing ->
            if (existing.userId == userId) return@transaction existing
            throw AuthException(
                "identity_already_linked",
                "That ${identity.provider.label()} account is already linked to a different account.",
                HttpStatusCode.Conflict,
            )
        }
        if (identities.listForUser(userId).any { it.provider == identity.provider }) {
            throw AuthException(
                "provider_already_linked",
                "A ${identity.provider.label()} account is already linked. Unlink it first.",
                HttpStatusCode.Conflict,
            )
        }
        val linked = identities.insert(userId, identity, now)
        identity.linkableEmail?.let { users.setPrimaryEmailIfFree(userId, it, now) }
        identity.phoneE164?.let { users.setPrimaryPhoneIfFree(userId, it, now) }
        identity.displayName?.let { users.replaceGeneratedDisplayName(userId, it, now) }
        linked
    }

    /**
     * Removes one of the user's identities, never the last. Contact fields that only that identity
     * vouched for are cleared, so signing in with it again can't silently re-attach to this account.
     */
    fun unlink(userId: UUID, rawIdentityId: String) {
        val identityId = runCatching { UUID.fromString(rawIdentityId) }.getOrNull() ?: throw identityNotFound()
        transaction {
            val now = clock()
            val user = users.lockForUpdate(userId) ?: throw identityNotFound()
            val all = identities.listForUser(userId)
            val target = all.firstOrNull { it.id == identityId } ?: throw identityNotFound()
            if (all.size == 1) {
                throw AuthException("last_identity", "You can't remove your only way to sign in.", HttpStatusCode.Conflict)
            }
            remove(user, all, target, now)
        }
    }

    /**
     * The provider says this identity is no longer usable (Apple: consent revoked or Apple ID deleted).
     * When [removeIdentity] is set and the account has another way in, the identity is removed;
     * the account's last identity is always kept so the account isn't orphaned.
     * Returns the account's user id, or null if the identity is unknown.
     */
    fun onProviderRevoked(provider: AuthMethod, subject: String, removeIdentity: Boolean): UUID? = transaction {
        val identity = identities.find(provider, subject) ?: return@transaction null
        val user = users.lockForUpdate(identity.userId) ?: return@transaction null
        if (removeIdentity) {
            val all = identities.listForUser(user.id)
            if (all.size > 1) remove(user, all, all.first { it.id == identity.id }, clock())
        }
        user.id
    }

    private fun remove(user: UserRecord, all: List<IdentityRecord>, target: IdentityRecord, now: Long) {
        identities.delete(target.id, user.id)
        val remaining = all - target
        if (target.provider == AuthMethod.WHATSAPP && target.phoneE164 != null && target.phoneE164 == user.phoneE164) {
            users.clearPrimaryPhone(user.id, target.phoneE164, now)
        }
        val email = user.email
        if (email != null && target.email == email && remaining.none { it.email == email && it.emailVerified && !it.isPrivateRelay }) {
            users.clearPrimaryEmail(user.id, email, now)
        }
    }

    fun list(userId: UUID): List<IdentityRecord> = identities.listForUser(userId)

    fun find(provider: AuthMethod, subject: String): IdentityRecord? = identities.find(provider, subject)

    private fun identityNotFound() = AuthException("not_found", "No such linked sign-in method.", HttpStatusCode.NotFound)

    private fun AuthMethod.label() = when (this) {
        AuthMethod.GOOGLE -> "Google"
        AuthMethod.APPLE -> "Apple"
        AuthMethod.WHATSAPP -> "WhatsApp"
    }
}
