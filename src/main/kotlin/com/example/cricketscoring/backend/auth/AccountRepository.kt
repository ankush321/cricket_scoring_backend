package com.example.cricketscoring.backend.auth

import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/** Account-wide cleanup queries for deletion. Each joins the caller's transaction. */
class AccountRepository {
    /** Soft-deletes every cricket row the user owns (bumping `version` so syncing clients see the change). */
    fun softDeleteOwnedData(userId: UUID, nowEpochMs: Long) {
        transaction {
            OWNED_TABLES.forEach { table ->
                exec(
                """
                UPDATE $table
                   SET deleted_at_epoch_ms = ?, updated_at_epoch_ms = ?, version = version + 1
                 WHERE owner_user_id = CAST(? AS uuid) AND deleted_at_epoch_ms IS NULL
                """.trimIndent(),
                listOf(LongColumnType() to nowEpochMs, LongColumnType() to nowEpochMs, TextColumnType() to userId.toString()),
                )
            }
        }
    }

    /** Removes the user's memberships, and everyone's memberships on resources the user owns. */
    fun deleteMemberships(userId: UUID) {
        transaction {
            exec(
                """
                DELETE FROM memberships
                 WHERE user_id = CAST(? AS uuid)
                    OR (resource_type = 'TEAM' AND resource_id IN (SELECT id FROM teams WHERE owner_user_id = CAST(? AS uuid)))
                    OR (resource_type = 'MATCH' AND resource_id IN (SELECT id FROM matches WHERE owner_user_id = CAST(? AS uuid)))
                    OR (resource_type = 'TOURNAMENT' AND resource_id IN (SELECT id FROM tournaments WHERE owner_user_id = CAST(? AS uuid)))
                """.trimIndent(),
                List(4) { TextColumnType() to userId.toString() },
            )
        }
    }

    /** Deletes OTP history for the given phones (it holds the phone numbers). */
    fun deleteOtpHistory(phones: Collection<String>) {
        if (phones.isEmpty()) return
        transaction {
            exec(
                "DELETE FROM otp_challenges WHERE phone_e164 = ANY (string_to_array(?, ','))",
                listOf(TextColumnType() to phones.joinToString(",")),
            )
        }
    }

    fun deleteIdentities(userId: UUID) {
        transaction {
            exec("DELETE FROM user_identities WHERE user_id = CAST(? AS uuid)", listOf(TextColumnType() to userId.toString()))
        }
    }

    private companion object {
        /** Tables with owner_user_id + deleted_at_epoch_ms. Constants, never user input. */
        val OWNED_TABLES = listOf("players", "teams", "rule_profiles", "tournaments", "matches")
    }
}
