package com.example.cricketscoring.backend.auth

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.uuid.toKotlinUuid

/**
 * Immediate account deletion. The `users` row becomes a scrubbed tombstone (status DELETED, no
 * contact data, generic name) so foreign keys on the soft-deleted cricket data stay valid.
 *
 * Order: revoke Apple tokens first (network, best effort, outside the transaction), then one
 * transaction under the user-row lock does all database changes, so a concurrent link, refresh or
 * sign-in either happens entirely before or sees a deleted account.
 */
class AccountService(
    private val users: UserRepository,
    private val identities: IdentityRepository,
    private val accounts: AccountRepository,
    private val sessions: SessionRepository,
    private val appleTokens: AppleTokenRepository,
    private val appleTokenClient: AppleTokenClient,
    private val tokenCipher: TokenCipher?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(AccountService::class.java)

    suspend fun delete(userId: UUID) {
        revokeAppleTokens(userId)
        transaction {
            val now = clock()
            val user = users.lockForUpdate(userId) ?: return@transaction
            if (user.deleted) return@transaction // already deleted: idempotent
            val phones = (identities.listForUser(userId).mapNotNull { it.phoneE164 } + listOfNotNull(user.phoneE164)).toSet()

            sessions.revokeAllForUser(userId.toKotlinUuid(), SessionRevokedReason.ACCOUNT_DELETED, now)
            sessions.scrubDeviceInfo(userId.toKotlinUuid())
            accounts.deleteMemberships(userId) // includes other users' roles on resources this user owns
            accounts.softDeleteOwnedData(userId, now)
            accounts.deleteIdentities(userId) // cascades to apple_tokens
            accounts.deleteOtpHistory(phones)
            users.markDeleted(userId, now)
            log.info("Account {} deleted", userId)
        }
    }

    /** Revokes one Apple identity's token at Apple, if we hold one. Used on deletion and on unlinking Apple. */
    suspend fun revokeAppleToken(identityId: UUID) {
        appleTokens.find(identityId)?.let { revoke(it) }
    }

    private suspend fun revokeAppleTokens(userId: UUID) {
        appleTokens.findForUser(userId).forEach { revoke(it) }
    }

    private suspend fun revoke(stored: StoredAppleToken) {
        val cipher = tokenCipher ?: return
        val token = cipher.decrypt(stored.cipher, stored.identityId.toString())
        if (token == null) {
            log.error("Apple token for identity {} could not be decrypted; not revoked", stored.identityId)
            return
        }
        if (!appleTokenClient.revoke(token, stored.clientId)) {
            // Deletion still goes ahead: the user's right to delete doesn't depend on Apple being up.
            log.error("Apple token for identity {} was not revoked at Apple", stored.identityId)
        }
    }
}
