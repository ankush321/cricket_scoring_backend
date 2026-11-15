package com.example.cricketscoring.backend.auth

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Meta WhatsApp Cloud API webhook: message status updates (sent / delivered / read / failed) for the
 * OTP messages we sent, matched by message id (`wamid`). Gives the beta real delivery numbers.
 */
class WhatsAppWebhook(
    private val config: AuthConfig,
    private val otps: OtpChallengeRepository,
    private val events: AuthEventRecorder,
) {
    /** Meta's GET handshake when the webhook is registered: echo `hub.challenge` if the token matches. */
    fun verifySubscription(mode: String?, token: String?, challenge: String?): String {
        val expected = config.whatsAppWebhookVerifyToken ?: throw notConfigured()
        if (mode != "subscribe" || token == null || challenge == null ||
            !MessageDigest.isEqual(expected.toByteArray(), token.toByteArray())
        ) {
            throw AuthException("forbidden", "Webhook verification failed.", HttpStatusCode.Forbidden)
        }
        return challenge
    }

    /** Verifies `X-Hub-Signature-256` over the raw body, then applies any status updates. Returns how many applied. */
    fun handle(rawBody: String, signatureHeader: String?): Int {
        val secret = config.whatsAppAppSecret ?: throw notConfigured()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val expected = "sha256=" + mac.doFinal(rawBody.toByteArray()).joinToString("") { "%02x".format(it) }
        if (signatureHeader == null || !MessageDigest.isEqual(expected.toByteArray(), signatureHeader.trim().lowercase().toByteArray())) {
            throw AuthException("invalid_signature", "Webhook signature is invalid.", HttpStatusCode.Unauthorized)
        }
        val root = runCatching { Json.parseToJsonElement(rawBody) as? JsonObject }.getOrNull() ?: return 0
        var applied = 0
        for (status in statuses(root)) {
            val id = status.string("id") ?: continue
            val value = status.string("status")?.uppercase()?.takeIf { it in STATUSES } ?: continue
            if (otps.updateDeliveryStatus(id, value)) {
                applied++
                events.record(
                    AuthEventType.OTP_DELIVERY_STATUS,
                    if (value == "FAILED") AuthEventOutcome.ERROR else AuthEventOutcome.OK,
                    AuthMethod.WHATSAPP,
                    errorCode = if (value == "FAILED") "delivery_failed" else null,
                    metadata = mapOf("status" to value),
                )
            }
        }
        return applied
    }

    /** entry[].changes[].value.statuses[] */
    private fun statuses(root: JsonObject): List<JsonObject> =
        root.array("entry").flatMap { it.array("changes") }
            .mapNotNull { it["value"] as? JsonObject }
            .flatMap { it.array("statuses") }

    private fun JsonObject.array(name: String): List<JsonObject> = (this[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.string(name: String): String? = runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun notConfigured() =
        AuthException("webhook_not_configured", "WhatsApp webhook is not configured.", HttpStatusCode.ServiceUnavailable)

    private companion object {
        val STATUSES = setOf("SENT", "DELIVERED", "READ", "FAILED")
    }
}
