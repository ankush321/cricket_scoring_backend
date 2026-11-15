package com.example.cricketscoring.backend.auth

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class GoogleSignInRequest(val idToken: String, val deviceName: String? = null)

@Serializable
data class WhatsAppOtpRequest(val phone: String)

@Serializable
data class WhatsAppOtpVerifyRequest(
    val phone: String,
    val code: String,
    /** From the request response. Optional for older app builds; the phone's live challenge is used when absent. */
    val challengeId: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class SessionDto(
    val id: String,
    val authMethod: String,
    val deviceName: String? = null,
    val userAgent: String? = null,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val current: Boolean,
)

@Serializable
data class AuthUserDto(
    val id: String,
    val email: String? = null,
    val phone: String? = null,
    val displayName: String,
    /** e.g. ["GOOGLE", "WHATSAPP"]. */
    val linkedProviders: List<String>,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthResponse(
    val user: AuthUserDto,
    /** True when this sign-in created the account (show onboarding). */
    val isNewUser: Boolean,
    val accessToken: String,
    @EncodeDefault val tokenType: String = "Bearer",
    /** Access token lifetime. */
    val expiresInSeconds: Long,
    /** Opaque; send to `/auth/token/refresh`. Single use: every refresh returns a new one. */
    val refreshToken: String,
    val refreshTokenExpiresInSeconds: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class OtpRequestResponse(
    val phone: String,
    /** WhatsApp sign-in is a beta flow. */
    @EncodeDefault val beta: Boolean = true,
    val challengeId: String,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
    val attemptsAllowed: Int,
    val delivery: String,
)
@Serializable
data class IdentityDto(
    val id: String,
    val provider: String,
    val email: String? = null,
    val isPrivateRelay: Boolean,
    val phone: String? = null,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
)

@Serializable
data class LinkGoogleRequest(val idToken: String)

@Serializable
data class LinkWhatsAppRequest(
    val phone: String,
    val code: String,
    val challengeId: String? = null,
)

@Serializable
data class AppleFullName(val givenName: String? = null, val familyName: String? = null)

@Serializable
data class AppleSignInRequest(
    val identityToken: String,
    /** The raw nonce; the app passed SHA-256 of it to Apple. */
    val nonce: String,
    val fullName: AppleFullName? = null,
    val deviceName: String? = null,
    /** Optional one-time code from the same Apple authorisation; lets the server revoke Apple access on account deletion. */
    val authorizationCode: String? = null,
)

@Serializable
data class AppleLinkRequest(
    val identityToken: String,
    val nonce: String,
    val fullName: AppleFullName? = null,
    val authorizationCode: String? = null,
)

/** Apple posts `{"payload": "<signed JWT>"}`. */
@Serializable
data class AppleNotificationRequest(val payload: String)

@Serializable
data class WhatsAppProvider(val enabled: Boolean, val beta: Boolean)

@Serializable
data class ProvidersResponse(val google: Boolean, val apple: Boolean, val whatsapp: WhatsAppProvider)
