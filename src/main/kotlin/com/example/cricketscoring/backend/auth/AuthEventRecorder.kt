package com.example.cricketscoring.backend.auth

import com.example.cricketscoring.backend.AuthEvents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

enum class AuthEventType {
    OTP_SENT, OTP_SEND_FAILED, OTP_RATE_LIMITED, OTP_VERIFY_FAILED, OTP_DELIVERY_STATUS, FEATURE_DISABLED,
    LOGIN_SUCCEEDED, REFRESH_REUSE_DETECTED, ACCOUNT_DELETED,
}

enum class AuthEventOutcome { OK, DENIED, ERROR }

/**
 * Writes `auth_events`, the audit trail and beta funnel. Best effort: a failed write is logged, never
 * surfaced. Call it **outside** any transaction: inside one it would join it and vanish on rollback.
 */
class AuthEventRecorder(pepper: String, private val clock: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger(AuthEventRecorder::class.java)
    private val key = SecretKeySpec(pepper.toByteArray(), "HmacSHA256")

    /** Stable pseudonym for a phone, so events and rate-limit keys never hold the raw number. */
    fun phoneHash(phoneE164: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return mac.doFinal("phone:$phoneE164".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun record(
        type: AuthEventType,
        outcome: AuthEventOutcome = AuthEventOutcome.OK,
        provider: AuthMethod? = null,
        errorCode: String? = null,
        userId: UUID? = null,
        phoneE164: String? = null,
        ip: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        try {
            transaction {
                AuthEvents.insert {
                    it[id] = Uuid.random()
                    it[eventType] = type.name
                    it[AuthEvents.provider] = provider?.name
                    it[AuthEvents.outcome] = outcome.name
                    it[AuthEvents.errorCode] = errorCode
                    it[AuthEvents.userId] = userId?.toKotlinUuid()
                    it[isBetaFlow] = provider == AuthMethod.WHATSAPP
                    it[AuthEvents.ip] = ip?.take(64)
                    it[phoneHash] = phoneE164?.let(::phoneHash)
                    it[AuthEvents.metadata] = metadata.takeIf { m -> m.isNotEmpty() }
                        ?.let { m -> Json.encodeToString(JsonObject.serializer(), JsonObject(m.mapValues { (_, v) -> JsonPrimitive(v) })) }
                    it[createdAtEpochMs] = clock()
                }
            }
        } catch (e: Exception) {
            log.warn("Could not record auth event {}: {}", type, e.javaClass.simpleName)
        }
    }
}
