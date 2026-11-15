package com.example.cricketscoring.backend.auth

data class AuthConfig(
    val jwtSecret: String,
    /** Access JWT lifetime. Short, so a leaked token is only useful briefly. */
    val accessTokenTtlSeconds: Long = 900,
    val jwtIssuer: String = "cricket-scoring-backend",
    val jwtAudience: String = "cricket-scoring-app",
    /** Refresh token lifetime; each refresh issues a new one (sliding). */
    val refreshTokenTtlSeconds: Long = 30L * 86_400,
    /** Absolute session lifetime; refreshing never extends past it. */
    val sessionMaxAgeSeconds: Long = 90L * 86_400,
    /** Reusing the previous refresh token within this window counts as a client retry, not theft. */
    val refreshReuseGraceSeconds: Long = 10,
    /** OAuth client IDs accepted as the Google token audience (Android, iOS, web each have their own). */
    val googleClientIds: List<String>,
    /** Accepted `aud` values for Apple tokens: the iOS bundle ID, and the Services ID used on web/Android. */
    val appleClientIds: List<String> = emptyList(),
    /** Apple revocation settings (all four or none): team ID, key ID, the .p8 private key, and a base64 32-byte AES key for tokens at rest. */
    val appleTeamId: String? = null,
    val appleKeyId: String? = null,
    val applePrivateKey: String? = null,
    val appleTokenEncryptionKey: String? = null,
    val whatsAppMode: WhatsAppMode,
    val whatsAppAccessToken: String?,
    val whatsAppPhoneNumberId: String?,
    val whatsAppOtpTemplateName: String,
    val whatsAppTemplateLanguage: String = "en",
    val whatsAppTemplateHasButton: Boolean = true,
    val whatsAppGraphVersion: String = "v21.0",
    /** HMAC key for OTP hashes. Kept separate from [jwtSecret] so either can rotate alone. */
    val otpPepper: String = DEV_OTP_PEPPER,
    val otpLength: Int,
    val otpTtlSeconds: Long,
    val otpResendCooldownSeconds: Long,
    val otpMaxAttempts: Int,
    val otpMaxSendsPerWindow: Int = 3,
    val otpWindowSeconds: Long = 600,
    val otpMaxSendsPerDay: Int = 10,
    /** Per client IP, per [otpIpWindowSeconds]: code requests and verify attempts. */
    val otpIpSendLimit: Int = 10,
    val otpIpVerifyLimit: Int = 30,
    val otpIpWindowSeconds: Long = 600,
    /** Wrong codes per phone per hour before code requests for that phone are blocked for the rest of the hour. */
    val otpFailedVerifyLimit: Int = 10,
    /** Codes sent across all phones per UTC day; 0 = unlimited. */
    val otpDailySendBudget: Int = 2000,
    /** Country calling codes (without +) that may receive codes; empty = all. */
    val whatsAppAllowedCountryCodes: List<String> = emptyList(),
    val whatsAppRollout: WhatsAppRollout = WhatsAppRollout.ON,
    /** E.164 numbers or prefixes allowed when [whatsAppRollout] is ALLOWLIST. */
    val whatsAppAllowlist: List<String> = emptyList(),
    /** Minimum `X-App-Version` for the WhatsApp flow; null = any (header not required). */
    val whatsAppMinAppVersion: String? = null,
    /** Meta app secret, for the `X-Hub-Signature-256` on delivery webhooks. */
    val whatsAppAppSecret: String? = null,
    /** The token you type into Meta's webhook setup; echoed back in the GET handshake. */
    val whatsAppWebhookVerifyToken: String? = null,
) {
    enum class WhatsAppMode {
        CONSOLE,
        CLOUD,
    }

    enum class WhatsAppRollout { OFF, ALLOWLIST, ON }

    companion object {
        const val DEV_OTP_PEPPER = "dev-only-otp-pepper"

        fun fromEnv(env: (String) -> String? = System::getenv): AuthConfig {
            fun read(name: String): String? = env(name)?.takeIf { it.isNotBlank() }
            fun list(value: String?): List<String> = value.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val mode = read("WHATSAPP_OTP_MODE")?.lowercase() ?: "console"
            val config = AuthConfig(
                jwtSecret = read("JWT_SECRET") ?: "dev-only-change-me",
                accessTokenTtlSeconds = (read("ACCESS_TOKEN_TTL_SECONDS") ?: read("JWT_EXPIRY_SECONDS"))?.toLongOrNull() ?: 900L,
                jwtIssuer = read("JWT_ISSUER") ?: "cricket-scoring-backend",
                jwtAudience = read("JWT_AUDIENCE") ?: "cricket-scoring-app",
                refreshTokenTtlSeconds = read("REFRESH_TOKEN_TTL_SECONDS")?.toLongOrNull() ?: (30L * 86_400),
                sessionMaxAgeSeconds = read("SESSION_MAX_AGE_SECONDS")?.toLongOrNull() ?: (90L * 86_400),
                refreshReuseGraceSeconds = read("REFRESH_REUSE_GRACE_SECONDS")?.toLongOrNull() ?: 10L,
                googleClientIds = (read("GOOGLE_CLIENT_IDS") ?: read("GOOGLE_CLIENT_ID")).orEmpty()
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                appleClientIds = read("APPLE_CLIENT_IDS").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() },
                appleTeamId = read("APPLE_TEAM_ID"),
                appleKeyId = read("APPLE_KEY_ID"),
                applePrivateKey = read("APPLE_PRIVATE_KEY"),
                appleTokenEncryptionKey = read("APPLE_TOKEN_ENCRYPTION_KEY"),
                whatsAppMode = if (mode == "cloud") WhatsAppMode.CLOUD else WhatsAppMode.CONSOLE,
                whatsAppAccessToken = read("WHATSAPP_ACCESS_TOKEN"),
                whatsAppPhoneNumberId = read("WHATSAPP_PHONE_NUMBER_ID"),
                whatsAppOtpTemplateName = read("WHATSAPP_OTP_TEMPLATE_NAME") ?: "login_otp",
                whatsAppTemplateLanguage = read("WHATSAPP_TEMPLATE_LANGUAGE") ?: "en",
                whatsAppTemplateHasButton = read("WHATSAPP_TEMPLATE_HAS_BUTTON")?.toBooleanStrictOrNull() ?: true,
                whatsAppGraphVersion = read("WHATSAPP_GRAPH_VERSION") ?: "v21.0",
                otpPepper = read("OTP_PEPPER") ?: DEV_OTP_PEPPER,
                otpLength = 6,
                otpTtlSeconds = read("OTP_TTL_SECONDS")?.toLongOrNull() ?: 300L,
                otpResendCooldownSeconds = read("OTP_RESEND_COOLDOWN_SECONDS")?.toLongOrNull() ?: 60L,
                otpMaxAttempts = read("OTP_MAX_ATTEMPTS")?.toIntOrNull() ?: 3,
                otpMaxSendsPerWindow = read("OTP_MAX_SENDS_PER_WINDOW")?.toIntOrNull() ?: 3,
                otpWindowSeconds = read("OTP_WINDOW_SECONDS")?.toLongOrNull() ?: 600L,
                otpMaxSendsPerDay = read("OTP_MAX_SENDS_PER_DAY")?.toIntOrNull() ?: 10,
                otpIpSendLimit = read("OTP_IP_SEND_LIMIT")?.toIntOrNull() ?: 10,
                otpIpVerifyLimit = read("OTP_IP_VERIFY_LIMIT")?.toIntOrNull() ?: 30,
                otpIpWindowSeconds = read("OTP_IP_WINDOW_SECONDS")?.toLongOrNull() ?: 600L,
                otpFailedVerifyLimit = read("OTP_FAILED_VERIFY_LIMIT")?.toIntOrNull() ?: 10,
                otpDailySendBudget = read("OTP_DAILY_SEND_BUDGET")?.toIntOrNull() ?: 2000,
                whatsAppAllowedCountryCodes = list(read("WHATSAPP_ALLOWED_COUNTRY_CODES")).map { it.removePrefix("+") },
                whatsAppRollout = read("WHATSAPP_OTP_ROLLOUT")?.uppercase()?.let { value ->
                    runCatching { WhatsAppRollout.valueOf(value) }.getOrElse { error("WHATSAPP_OTP_ROLLOUT must be off, allowlist or on") }
                } ?: WhatsAppRollout.ON,
                whatsAppAllowlist = list(read("WHATSAPP_OTP_ALLOWLIST")),
                whatsAppMinAppVersion = read("WHATSAPP_OTP_MIN_APP_VERSION"),
                whatsAppAppSecret = read("WHATSAPP_APP_SECRET"),
                whatsAppWebhookVerifyToken = read("WHATSAPP_WEBHOOK_VERIFY_TOKEN"),
            )
            val appleRevocation = listOf(config.appleTeamId, config.appleKeyId, config.applePrivateKey, config.appleTokenEncryptionKey)
            check(appleRevocation.all { it == null } || appleRevocation.none { it == null }) {
                "Set all of APPLE_TEAM_ID, APPLE_KEY_ID, APPLE_PRIVATE_KEY and APPLE_TOKEN_ENCRYPTION_KEY, or none of them."
            }
            check(config.whatsAppMode == WhatsAppMode.CONSOLE || config.otpPepper != DEV_OTP_PEPPER) {
                "OTP_PEPPER must be set when WHATSAPP_OTP_MODE=cloud."
            }
            return config
        }
    }
}
