package com.example.cricketscoring.backend.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Where the code went: `channel` is reported to the client, `providerMessageId` (e.g. Meta's wamid) is stored. */
data class OtpDelivery(val channel: String, val providerMessageId: String? = null)

fun interface WhatsAppOtpSender {
    /** Throws [AuthException] (`otp_delivery_failed`) when the code could not be sent. */
    suspend fun sendOtp(phoneE164: String, code: String): OtpDelivery

    companion object {
        private val log = LoggerFactory.getLogger(WhatsAppOtpSender::class.java)

        fun fromConfig(config: AuthConfig): WhatsAppOtpSender = when (config.whatsAppMode) {
            AuthConfig.WhatsAppMode.CONSOLE -> WhatsAppOtpSender { phone, code ->
                log.warn("WHATSAPP_OTP_MODE=console: OTP for {} is {} (not sent via WhatsApp)", phone, code)
                OtpDelivery("console")
            }
            AuthConfig.WhatsAppMode.CLOUD -> {
                val token = config.whatsAppAccessToken
                val phoneNumberId = config.whatsAppPhoneNumberId
                if (token.isNullOrBlank() || phoneNumberId.isNullOrBlank()) {
                    throw AuthException(
                        "whatsapp_not_configured",
                        "Set WHATSAPP_ACCESS_TOKEN and WHATSAPP_PHONE_NUMBER_ID for cloud mode.",
                        HttpStatusCode.ServiceUnavailable,
                    )
                }
                CloudWhatsAppOtpSender(token, phoneNumberId, config)
            }
        }

        internal fun deliveryFailed() = AuthException(
            "otp_delivery_failed",
            "Could not deliver the code via WhatsApp. Please try again.",
            HttpStatusCode.BadGateway,
        )
    }
}

/**
 * Sends an AUTHENTICATION-category template through the Meta WhatsApp Cloud API.
 * Templates with a copy-code / one-tap button need the code in a `button` component as well as the `body`.
 */
private class CloudWhatsAppOtpSender(
    private val accessToken: String,
    private val phoneNumberId: String,
    private val config: AuthConfig,
) : WhatsAppOtpSender {
    private val log = LoggerFactory.getLogger(CloudWhatsAppOtpSender::class.java)
    // explicitNulls = false: omit sub_type/index on the body component instead of sending nulls.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    override suspend fun sendOtp(phoneE164: String, code: String): OtpDelivery {
        val response = try {
            client.post("https://graph.facebook.com/${config.whatsAppGraphVersion}/$phoneNumberId/messages") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(templateMessage(phoneE164.removePrefix("+"), code))
            }
        } catch (e: Exception) {
            log.error("WhatsApp send failed: {}", e.javaClass.simpleName)
            throw WhatsAppOtpSender.deliveryFailed()
        }
        if (!response.status.isSuccess()) {
            // Meta's error body describes the problem (bad template, unregistered number...) and never echoes the code.
            val error = runCatching { json.decodeFromString<MetaErrorResponse>(response.bodyAsText()).error }.getOrNull()
            log.error("WhatsApp send rejected: status={} code={} message={}", response.status.value, error?.code, error?.message)
            throw WhatsAppOtpSender.deliveryFailed()
        }
        val messageId = runCatching { response.body<MetaSendResponse>().messages.firstOrNull()?.id }.getOrNull()
        return OtpDelivery("whatsapp_cloud", messageId)
    }

    private fun templateMessage(to: String, code: String): WhatsAppTemplateMessage {
        val codeParam = listOf(TemplateParameter(type = "text", text = code))
        val components = buildList {
            add(TemplateComponent(type = "body", parameters = codeParam))
            if (config.whatsAppTemplateHasButton) {
                add(TemplateComponent(type = "button", subType = "url", index = "0", parameters = codeParam))
            }
        }
        return WhatsAppTemplateMessage(
            messagingProduct = "whatsapp",
            to = to,
            type = "template",
            template = TemplateBody(
                name = config.whatsAppOtpTemplateName,
                language = TemplateLanguage(code = config.whatsAppTemplateLanguage),
                components = components,
            ),
        )
    }
}

@Serializable
internal data class WhatsAppTemplateMessage(
    @SerialName("messaging_product") val messagingProduct: String,
    val to: String,
    val type: String,
    val template: TemplateBody,
)

@Serializable
internal data class TemplateBody(
    val name: String,
    val language: TemplateLanguage,
    val components: List<TemplateComponent>,
)

@Serializable
internal data class TemplateLanguage(val code: String)

@Serializable
internal data class TemplateComponent(
    val type: String,
    @SerialName("sub_type") val subType: String? = null,
    val index: String? = null,
    val parameters: List<TemplateParameter>,
)

@Serializable
internal data class TemplateParameter(val type: String, val text: String)

@Serializable
private data class MetaSendResponse(val messages: List<MetaMessageId> = emptyList())

@Serializable
private data class MetaMessageId(val id: String)

@Serializable
private data class MetaErrorResponse(val error: MetaError? = null)

@Serializable
private data class MetaError(val message: String? = null, val code: Int? = null)
