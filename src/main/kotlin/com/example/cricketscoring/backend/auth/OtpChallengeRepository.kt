package com.example.cricketscoring.backend.auth

import com.example.cricketscoring.backend.OtpChallenges
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

enum class OtpConsumedReason { VERIFIED, MAX_ATTEMPTS, SUPERSEDED, SEND_FAILED }

/** A challenge row after one attempt was atomically counted against it. */
data class OtpAttempt(
    val id: Uuid,
    val codeHash: ByteArray,
    val attemptCount: Int,
    val maxAttempts: Int,
)

/**
 * `otp_challenges` queries. Each function opens `transaction { }`, which joins the caller's
 * transaction when there is one, so [AuthService] can group several calls atomically.
 */
class OtpChallengeRepository {
    /** Serialises all OTP sends for one phone until the surrounding transaction ends. */
    fun lockPhone(phoneE164: String) {
        transaction {
            exec("SELECT pg_advisory_xact_lock(hashtext(?))", listOf(TextColumnType() to phoneE164)) { }
        }
    }

    /** Creation times (ascending) of sends since [sinceEpochMs] that count towards limits. Failed sends don't count. */
    fun countedSendTimes(phoneE164: String, sinceEpochMs: Long): List<Long> = transaction {
        OtpChallenges.selectAll()
            .where { (OtpChallenges.phoneE164 eq phoneE164) and (OtpChallenges.createdAtEpochMs greaterEq sinceEpochMs) }
            .orderBy(OtpChallenges.createdAtEpochMs to SortOrder.ASC)
            .filter { it[OtpChallenges.consumedReason] != OtpConsumedReason.SEND_FAILED.name }
            .map { it[OtpChallenges.createdAtEpochMs] }
    }

    /** Ends any live challenge for the phone and inserts a new one. */
    fun replaceLive(
        id: Uuid,
        phoneE164: String,
        codeHash: ByteArray,
        maxAttempts: Int,
        expiresAtEpochMs: Long,
        nowEpochMs: Long,
    ) {
        transaction {
            OtpChallenges.update({ (OtpChallenges.phoneE164 eq phoneE164) and OtpChallenges.consumedAtEpochMs.isNull() }) {
                it[consumedAtEpochMs] = nowEpochMs
                it[consumedReason] = OtpConsumedReason.SUPERSEDED.name
            }
            OtpChallenges.insert {
                it[OtpChallenges.id] = id
                it[OtpChallenges.phoneE164] = phoneE164
                it[purpose] = "LOGIN"
                it[channel] = "WHATSAPP"
                it[OtpChallenges.codeHash] = codeHash
                it[attemptCount] = 0
                it[OtpChallenges.maxAttempts] = maxAttempts
                it[OtpChallenges.expiresAtEpochMs] = expiresAtEpochMs
                it[createdAtEpochMs] = nowEpochMs
            }
        }
    }

    fun recordDelivery(id: Uuid, deliveryChannel: String, providerMessageId: String?) {
        transaction {
            OtpChallenges.update({ OtpChallenges.id eq id }) {
                it[OtpChallenges.deliveryChannel] = deliveryChannel
                it[OtpChallenges.providerMessageId] = providerMessageId
            }
        }
    }

    /**
     * Applies a delivery status from Meta's webhook to the challenge with that message id. Statuses
     * only move forward (SENT < FAILED < DELIVERED < READ), since Meta may deliver them out of order.
     * Returns true if a challenge was updated.
     */
    fun updateDeliveryStatus(providerMessageId: String, status: String): Boolean = transaction {
        val rank = "CASE %s WHEN 'SENT' THEN 1 WHEN 'FAILED' THEN 2 WHEN 'DELIVERED' THEN 3 WHEN 'READ' THEN 4 ELSE 0 END"
        exec(
            """
            UPDATE otp_challenges SET delivery_status = ?
             WHERE provider_message_id = ?
               AND ${rank.format("delivery_status")} < ${rank.format("CAST(? AS text)")}
            RETURNING id
            """.trimIndent(),
            listOf(TextColumnType() to status, TextColumnType() to providerMessageId, TextColumnType() to status),
            StatementType.SELECT,
        ) { rs -> rs.next() } ?: false
    }

    /** Marks a live challenge consumed. Returns false if it was already consumed (e.g. by a concurrent request). */
    fun consume(id: Uuid, reason: OtpConsumedReason, nowEpochMs: Long): Boolean = transaction {
        OtpChallenges.update({ (OtpChallenges.id eq id) and OtpChallenges.consumedAtEpochMs.isNull() }) {
            it[consumedAtEpochMs] = nowEpochMs
            it[consumedReason] = reason.name
        } > 0
    }

    /**
     * Counts one attempt against the phone's live, unexpired, unlocked challenge in a single
     * statement, so concurrent guesses can never exceed `max_attempts`. Returns null if there is
     * no such challenge (unknown, expired, consumed, or out of attempts).
     */
    fun countAttempt(phoneE164: String, challengeId: Uuid?, nowEpochMs: Long): OtpAttempt? = transaction {
        val idFilter = if (challengeId != null) " AND id = CAST(? AS uuid)" else ""
        val args = buildList {
            add(TextColumnType() to phoneE164)
            add(LongColumnType() to nowEpochMs)
            if (challengeId != null) add(TextColumnType() to challengeId.toString())
        }
        exec(
            """
            UPDATE otp_challenges
               SET attempt_count = attempt_count + 1
             WHERE phone_e164 = ?
               AND consumed_at_epoch_ms IS NULL
               AND expires_at_epoch_ms > ?
               AND attempt_count < max_attempts$idFilter
            RETURNING id, code_hash, attempt_count, max_attempts
            """.trimIndent(),
            args,
            StatementType.SELECT,
        ) { rs ->
            if (!rs.next()) {
                null
            } else {
                OtpAttempt(
                    id = Uuid.parse(rs.getString("id")),
                    codeHash = rs.getBytes("code_hash"),
                    attemptCount = rs.getInt("attempt_count"),
                    maxAttempts = rs.getInt("max_attempts"),
                )
            }
        }
    }
}
