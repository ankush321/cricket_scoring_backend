package com.example.cricketscoring.backend.auth

import io.ktor.http.HttpStatusCode

/**
 * Abuse and rollout rules for the WhatsApp OTP flow, on top of the per-phone limits in [AuthService]:
 * the beta rollout flag, a country allowlist, per-IP limits, a per-phone block after repeated wrong
 * codes, and a global daily send budget. Counters live in `rate_limit_buckets`, so they hold across
 * server machines. Per-IP counters commit their own increment, so rejected requests still count.
 * Nothing here records events; [AuthService] does, outside transactions.
 */
class OtpGuard(
    private val config: AuthConfig,
    private val buckets: RateLimitRepository,
    private val events: AuthEventRecorder, // only for phoneHash
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Whether the app should offer WhatsApp sign-in at all (no phone known yet). */
    fun offered(client: ClientInfo): Boolean =
        config.whatsAppRollout != AuthConfig.WhatsAppRollout.OFF && appVersionAllowed(client.appVersion)

    /** Rollout flag for this phone and app build. Applies to sending, verifying and linking. */
    fun checkEnabled(phone: String, client: ClientInfo) {
        val allowed = when (config.whatsAppRollout) {
            AuthConfig.WhatsAppRollout.OFF -> false
            AuthConfig.WhatsAppRollout.ON -> true
            AuthConfig.WhatsAppRollout.ALLOWLIST -> config.whatsAppAllowlist.any { phone.startsWith(it) }
        } && appVersionAllowed(client.appVersion)
        if (!allowed) {
            throw AuthException("feature_disabled", "WhatsApp sign-in isn't available for you yet.", HttpStatusCode.Forbidden)
        }
    }

    /** Checks run before a code is generated: country, per-IP send limit, recent wrong codes for this phone. */
    fun beforeSend(phone: String, client: ClientInfo) {
        val allowedCountries = config.whatsAppAllowedCountryCodes
        if (allowedCountries.isNotEmpty() && allowedCountries.none { phone.startsWith("+$it") }) {
            throw AuthException("unsupported_country", "WhatsApp codes can't be sent to this country.", HttpStatusCode.BadRequest)
        }
        client.ip?.let { ip ->
            countOrReject("otp_send:ip:$ip", config.otpIpWindowSeconds, config.otpIpSendLimit)
        }
        val failKey = "otp_verify_fail:phone:${events.phoneHash(phone)}"
        val failWindow = window(HOUR_SECONDS)
        if (buckets.current(failKey, failWindow.start) >= config.otpFailedVerifyLimit) {
            throw AuthException(
                "otp_rate_limited",
                "Too many wrong codes for this number. Try again in ${failWindow.retryAfter}s.",
                HttpStatusCode.TooManyRequests,
                failWindow.retryAfter,
            )
        }
    }

    /**
     * Global daily budget (cost protection). Call inside the send transaction, after the per-phone
     * checks pass, so rejected requests don't use budget; returns the error instead of throwing.
     */
    fun reserveGlobalSend(): AuthException? {
        if (config.otpDailySendBudget <= 0) return null
        val day = window(DAY_SECONDS)
        if (buckets.increment("otp_send:global", day.start) <= config.otpDailySendBudget) return null
        return AuthException(
            "otp_unavailable",
            "WhatsApp codes are temporarily unavailable. Try another sign-in method.",
            HttpStatusCode.ServiceUnavailable,
            day.retryAfter,
        )
    }

    fun beforeVerify(client: ClientInfo) {
        client.ip?.let { ip ->
            countOrReject("otp_verify:ip:$ip", config.otpIpWindowSeconds, config.otpIpVerifyLimit)
        }
    }

    /** Called for every wrong or locked-out code. */
    fun onVerifyFailed(phone: String) {
        buckets.increment("otp_verify_fail:phone:${events.phoneHash(phone)}", window(HOUR_SECONDS).start)
    }

    private fun countOrReject(key: String, windowSeconds: Long, limit: Int) {
        val w = window(windowSeconds)
        if (buckets.increment(key, w.start) > limit) {
            throw AuthException("rate_limited", "Too many requests. Try again in ${w.retryAfter}s.", HttpStatusCode.TooManyRequests, w.retryAfter)
        }
    }

    private data class Window(val start: Long, val retryAfter: Long)

    /** Fixed windows aligned to the epoch (so the daily one is a UTC day). */
    private fun window(seconds: Long): Window {
        val now = clock()
        val size = seconds * 1_000
        val start = now - now % size
        return Window(start, (start + size - now + 999) / 1_000)
    }

    /** `1.4.10` >= `1.4.2`: numeric, part by part. A missing or unparsable header fails when a minimum is set. */
    private fun appVersionAllowed(version: String?): Boolean {
        val min = config.whatsAppMinAppVersion ?: return true
        val actual = version?.let(::parts) ?: return false
        val required = parts(min) ?: return true
        for (i in 0 until maxOf(actual.size, required.size)) {
            val a = actual.getOrElse(i) { 0 }
            val r = required.getOrElse(i) { 0 }
            if (a != r) return a > r
        }
        return true
    }

    private fun parts(version: String): List<Int>? =
        version.trim().split(".").map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

    private companion object {
        const val HOUR_SECONDS = 3_600L
        const val DAY_SECONDS = 86_400L
    }
}
