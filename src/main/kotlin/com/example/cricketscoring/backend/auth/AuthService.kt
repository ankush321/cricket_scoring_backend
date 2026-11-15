package com.example.cricketscoring.backend.auth

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

class AuthService(
    private val config: AuthConfig,
    private val users: UserRepository,
    private val identities: IdentityService,
    private val otps: OtpChallengeRepository,
    private val sessions: SessionService,
    private val googleVerifier: GoogleVerifier,
    private val appleVerifier: AppleVerifier,
    private val accounts: AccountService,
    private val appleTokens: AppleTokenRepository,
    private val appleTokenClient: AppleTokenClient,
    private val tokenCipher: TokenCipher?,
    private val guard: OtpGuard,
    private val events: AuthEventRecorder,
    private val whatsAppOtpSender: WhatsAppOtpSender,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun signInWithGoogle(idToken: String, client: ClientInfo = ClientInfo()): AuthResponse {
        val resolved = identities.signIn(verifyGoogle(idToken))
        return signedIn(resolved, AuthMethod.GOOGLE, client)
    }

    private fun verifyGoogle(idToken: String): VerifiedIdentity {
        if (idToken.isBlank()) {
            throw AuthException("validation_error", "idToken is required.", HttpStatusCode.BadRequest)
        }
        val profile = googleVerifier.verify(idToken)
        return VerifiedIdentity(
            provider = AuthMethod.GOOGLE,
            subject = profile.subject,
            email = profile.email,
            emailVerified = profile.emailVerified,
            displayName = profile.name?.takeIf { it.isNotBlank() },
            avatarUrl = profile.picture,
        )
    }

    suspend fun signInWithApple(request: AppleSignInRequest, client: ClientInfo = ClientInfo()): AuthResponse {
        val (identity, audience) = verifyApple(request.identityToken, request.nonce, request.fullName)
        val resolved = identities.signIn(identity)
        storeAppleToken(identity.subject, audience, request.authorizationCode)
        return signedIn(resolved, AuthMethod.APPLE, client)
    }

    /**
     * Exchanges the one-time `authorizationCode` for Apple's refresh token and keeps it encrypted,
     * only so it can be revoked at Apple later. Best effort: a failure never fails the sign-in.
     */
    private suspend fun storeAppleToken(subject: String, clientId: String, authorizationCode: String?) {
        val cipher = tokenCipher
        if (authorizationCode.isNullOrBlank() || cipher == null || !appleTokenClient.enabled) return
        val identity = identities.find(AuthMethod.APPLE, subject) ?: return
        val refreshToken = appleTokenClient.exchangeCode(authorizationCode, clientId) ?: return
        appleTokens.upsert(identity.id, clientId, cipher.encrypt(refreshToken, identity.id.toString()), clock())
    }

    /** [fullName] only arrives from the app on the first authorisation; Apple never puts it in the token. */
    private fun verifyApple(identityToken: String, nonce: String, fullName: AppleFullName?): Pair<VerifiedIdentity, String> {
        if (identityToken.isBlank()) {
            throw AuthException("validation_error", "identityToken is required.", HttpStatusCode.BadRequest)
        }
        val claims = appleVerifier.verifyIdentityToken(identityToken, nonce)
        val name = listOfNotNull(fullName?.givenName?.trim(), fullName?.familyName?.trim())
            .filter { it.isNotEmpty() }.joinToString(" ").take(100).takeIf { it.isNotEmpty() }
        val identity = VerifiedIdentity(
            provider = AuthMethod.APPLE,
            subject = claims.subject,
            email = claims.email,
            emailVerified = claims.emailVerified,
            isPrivateRelay = claims.isPrivateEmail || claims.email?.endsWith("@privaterelay.appleid.com") == true,
            displayName = name,
        )
        return identity to claims.audience
    }

    /**
     * Apple server-to-server notification. `consent-revoked` and `account-delete` end the user's
     * sessions; `account-delete` also removes the Apple identity if the account has another way in
     * (an Apple ID that's gone never comes back, while a revoked consent can be re-granted with the same `sub`).
     */
    fun handleAppleNotification(payload: String) {
        val event = appleVerifier.verifyNotification(payload)
        val revoke = event.type == "consent-revoked" || event.type == "account-delete"
        if (!revoke) return // email-enabled / email-disabled: nothing stored depends on relay forwarding yet
        val userId = identities.onProviderRevoked(AuthMethod.APPLE, event.subject, removeIdentity = event.type == "account-delete")
            ?: return
        sessions.revokeAllForUser(userId, SessionRevokedReason.PROVIDER_REVOKED)
    }

    suspend fun requestWhatsAppOtp(rawPhone: String, client: ClientInfo = ClientInfo()): OtpRequestResponse {
        val phone = PhoneNumber.normalize(rawPhone)
        try {
            val response = sendOtp(phone, client)
            events.record(AuthEventType.OTP_SENT, provider = AuthMethod.WHATSAPP, phoneE164 = phone, ip = client.ip, metadata = mapOf("delivery" to response.delivery))
            return response
        } catch (e: AuthException) {
            recordOtpFailure(e, phone, client)
            throw e
        }
    }

    private suspend fun sendOtp(phone: String, client: ClientInfo): OtpRequestResponse {
        guard.checkEnabled(phone, client)
        guard.beforeSend(phone, client)
        val now = clock()
        val challengeId = Uuid.random()
        val code = OtpHasher.generate(config.otpLength)

        // Limit checks and the insert share one transaction under a per-phone advisory lock,
        // so parallel requests for the same phone can't both slip under a limit.
        val decision = transaction {
            otps.lockPhone(phone)
            val sends = otps.countedSendTimes(phone, sinceEpochMs = now - DAY_MS)
            val blocked = checkSendLimits(sends, now) ?: guard.reserveGlobalSend()
            if (blocked == null) {
                otps.replaceLive(
                    id = challengeId,
                    phoneE164 = phone,
                    codeHash = OtpHasher.hash(challengeId.toString(), code, config.otpPepper),
                    maxAttempts = config.otpMaxAttempts,
                    expiresAtEpochMs = now + config.otpTtlSeconds * 1_000,
                    nowEpochMs = now,
                )
            }
            blocked
        }
        if (decision != null) throw decision

        // Send outside the transaction. A failed send is marked SEND_FAILED, which doesn't count
        // towards the cooldown or windows, so the user can retry straight away.
        val delivery = try {
            whatsAppOtpSender.sendOtp(phone, code)
        } catch (e: Exception) {
            otps.consume(challengeId, OtpConsumedReason.SEND_FAILED, clock())
            throw e as? AuthException ?: WhatsAppOtpSender.deliveryFailed()
        }
        otps.recordDelivery(challengeId, delivery.channel, delivery.providerMessageId)
        return OtpRequestResponse(
            phone = phone,
            challengeId = challengeId.toString(),
            expiresInSeconds = config.otpTtlSeconds,
            resendAfterSeconds = config.otpResendCooldownSeconds,
            attemptsAllowed = config.otpMaxAttempts,
            delivery = delivery.channel,
        )
    }

    private fun recordOtpFailure(e: AuthException, phone: String, client: ClientInfo) {
        val (type, outcome) = when (e.code) {
            "otp_delivery_failed" -> AuthEventType.OTP_SEND_FAILED to AuthEventOutcome.ERROR
            "feature_disabled" -> AuthEventType.FEATURE_DISABLED to AuthEventOutcome.DENIED
            "invalid_otp", "otp_locked" -> AuthEventType.OTP_VERIFY_FAILED to AuthEventOutcome.DENIED
            "otp_cooldown", "otp_rate_limited", "rate_limited", "otp_unavailable", "unsupported_country" ->
                AuthEventType.OTP_RATE_LIMITED to AuthEventOutcome.DENIED
            else -> return // validation errors, link conflicts...: not OTP abuse signals
        }
        events.record(type, outcome, AuthMethod.WHATSAPP, e.code, phoneE164 = phone, ip = client.ip)
    }

    /** Returns the exception to throw when a send is over a limit, or null when it may proceed. */
    private fun checkSendLimits(sendTimes: List<Long>, now: Long): AuthException? {
        val last = sendTimes.lastOrNull()
        val cooldownMs = config.otpResendCooldownSeconds * 1_000
        if (last != null && now - last < cooldownMs) {
            val wait = ceilSeconds(last + cooldownMs - now)
            return AuthException("otp_cooldown", "Please wait ${wait}s before requesting another code.", HttpStatusCode.TooManyRequests, wait)
        }
        val windowMs = config.otpWindowSeconds * 1_000
        val inWindow = sendTimes.filter { it > now - windowMs }
        if (inWindow.size >= config.otpMaxSendsPerWindow) {
            val wait = ceilSeconds(inWindow.first() + windowMs - now)
            return AuthException("otp_rate_limited", "Too many codes requested. Try again in ${wait}s.", HttpStatusCode.TooManyRequests, wait)
        }
        if (sendTimes.size >= config.otpMaxSendsPerDay) {
            val wait = ceilSeconds(sendTimes.first() + DAY_MS - now)
            return AuthException("otp_rate_limited", "Daily code limit reached. Try again later.", HttpStatusCode.TooManyRequests, wait)
        }
        return null
    }

    fun verifyWhatsAppOtp(
        rawPhone: String,
        rawCode: String,
        rawChallengeId: String? = null,
        client: ClientInfo = ClientInfo(),
    ): AuthResponse {
        val phone = PhoneNumber.normalize(rawPhone)
        val resolved = consumeOtp(phone, rawCode, rawChallengeId, client) { identities.signIn(whatsAppIdentity(phone)) }
        return signedIn(resolved, AuthMethod.WHATSAPP, client)
    }

    /**
     * Checks an OTP for [phone]. On success, marks the challenge VERIFIED and runs [onVerified] in the
     * same transaction, so the code can't be used twice and a failure in [onVerified] leaves it unused.
     */
    private fun <T> consumeOtp(phone: String, rawCode: String, rawChallengeId: String?, client: ClientInfo, onVerified: () -> T): T =
        try {
            guard.checkEnabled(phone, client)
            guard.beforeVerify(client)
            checkOtp(phone, rawCode, rawChallengeId, onVerified)
        } catch (e: AuthException) {
            if (e.code == "invalid_otp" || e.code == "otp_locked") guard.onVerifyFailed(phone)
            recordOtpFailure(e, phone, client)
            throw e
        }

    private fun <T> checkOtp(phone: String, rawCode: String, rawChallengeId: String?, onVerified: () -> T): T {
        val code = rawCode.trim()
        if (!code.matches(Regex("^\\d{${config.otpLength}}$"))) {
            throw AuthException("validation_error", "Invalid OTP format.", HttpStatusCode.BadRequest)
        }
        val challengeId = rawChallengeId?.takeIf { it.isNotBlank() }?.let {
            runCatching { Uuid.parse(it.trim()) }.getOrNull()
                ?: throw AuthException("validation_error", "Invalid challengeId.", HttpStatusCode.BadRequest)
        }
        val now = clock()

        // Counting the attempt is its own committed statement: a wrong guess must stay counted
        // even though we throw afterwards.
        val attempt = otps.countAttempt(phone, challengeId, now) ?: throw invalidOtp()
        val expected = OtpHasher.hash(attempt.id.toString(), code, config.otpPepper)
        if (!OtpHasher.matches(expected, attempt.codeHash)) {
            val remaining = attempt.maxAttempts - attempt.attemptCount
            if (remaining <= 0) {
                otps.consume(attempt.id, OtpConsumedReason.MAX_ATTEMPTS, now)
                throw AuthException("otp_locked", "Too many attempts. Request a new code.", HttpStatusCode.TooManyRequests)
            }
            throw AuthException("invalid_otp", "Invalid OTP. $remaining attempt(s) left.", HttpStatusCode.Unauthorized)
        }
        return transaction {
            if (!otps.consume(attempt.id, OtpConsumedReason.VERIFIED, now)) throw invalidOtp()
            onVerified()
        }
    }

    private fun whatsAppIdentity(phone: String) = VerifiedIdentity(
        provider = AuthMethod.WHATSAPP,
        subject = phone,
        phoneE164 = phone,
    )

    // ---------------------------------------------------------------- linked identities

    fun listIdentities(principal: AccessPrincipal): List<IdentityDto> = identities.list(principal.userId).map { it.toDto() }

    fun linkGoogle(principal: AccessPrincipal, idToken: String): IdentityDto =
        identities.link(principal.userId, verifyGoogle(idToken)).toDto()

    suspend fun linkApple(principal: AccessPrincipal, request: AppleLinkRequest): IdentityDto {
        val (identity, audience) = verifyApple(request.identityToken, request.nonce, request.fullName)
        val linked = identities.link(principal.userId, identity)
        storeAppleToken(identity.subject, audience, request.authorizationCode)
        return linked.toDto()
    }

    fun linkWhatsApp(
        principal: AccessPrincipal,
        rawPhone: String,
        rawCode: String,
        rawChallengeId: String?,
        client: ClientInfo = ClientInfo(),
    ): IdentityDto {
        val phone = PhoneNumber.normalize(rawPhone)
        return consumeOtp(phone, rawCode, rawChallengeId, client) { identities.link(principal.userId, whatsAppIdentity(phone)) }.toDto()
    }

    /** Unlinking Apple also revokes our Apple token (best effort), as Apple asks apps to do on disconnect. */
    suspend fun unlinkIdentity(principal: AccessPrincipal, identityId: String) {
        val target = identities.list(principal.userId).firstOrNull { it.id.toString() == identityId.trim() }
        // Revoke only when the unlink will succeed; never for the last identity.
        if (target?.provider == AuthMethod.APPLE && identities.list(principal.userId).size > 1) {
            accounts.revokeAppleToken(target.id)
        }
        identities.unlink(principal.userId, identityId)
    }

    /** Immediate, irreversible account deletion. See [AccountService]. */
    suspend fun deleteAccount(principal: AccessPrincipal) {
        accounts.delete(principal.userId)
        events.record(AuthEventType.ACCOUNT_DELETED, userId = principal.userId)
    }

    /** What the app should offer on its sign-in screen. */
    fun providers(client: ClientInfo) = ProvidersResponse(
        google = config.googleClientIds.isNotEmpty(),
        apple = config.appleClientIds.isNotEmpty(),
        whatsapp = WhatsAppProvider(enabled = guard.offered(client), beta = true),
    )

    private fun signedIn(resolved: ResolvedUser, method: AuthMethod, client: ClientInfo): AuthResponse {
        val response = toAuthResponse(resolved, sessions.start(resolved.user.id, method, client))
        events.record(
            AuthEventType.LOGIN_SUCCEEDED,
            provider = method,
            userId = resolved.user.id,
            ip = client.ip,
            metadata = mapOf("newUser" to resolved.isNewUser.toString()),
        )
        return response
    }

    private fun IdentityRecord.toDto() = IdentityDto(
        id = id.toString(),
        provider = provider.name,
        email = email,
        isPrivateRelay = isPrivateRelay,
        phone = phoneE164,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedAtEpochMs = lastUsedAtEpochMs,
    )

    fun refresh(refreshToken: String): AuthResponse {
        val tokens = sessions.refresh(refreshToken)
        val user = users.findById(tokens.userId)
            ?: throw AuthException("invalid_refresh_token", "Account no longer exists.", HttpStatusCode.Unauthorized)
        return toAuthResponse(ResolvedUser(user, isNewUser = false), tokens)
    }

    fun me(principal: AccessPrincipal): AuthUserDto =
        users.findById(principal.userId)?.toDto()
            ?: throw AuthException("unauthorized", "Account no longer exists.", HttpStatusCode.Unauthorized)

    private fun invalidOtp() =
        AuthException("invalid_otp", "OTP expired or not found. Request a new code.", HttpStatusCode.Unauthorized)

    private fun toAuthResponse(resolved: ResolvedUser, tokens: IssuedTokens) = AuthResponse(
        user = resolved.user.toDto(),
        isNewUser = resolved.isNewUser,
        accessToken = tokens.access.accessToken,
        expiresInSeconds = tokens.access.expiresInSeconds,
        refreshToken = tokens.refreshToken,
        refreshTokenExpiresInSeconds = tokens.refreshTokenExpiresInSeconds,
    )

    private fun UserRecord.toDto() = AuthUserDto(
        id = id.toString(),
        email = email,
        phone = phoneE164,
        displayName = displayName,
        linkedProviders = identities.list(id).map { it.provider.name },
    )

    private companion object {
        const val DAY_MS = 86_400_000L

        fun ceilSeconds(ms: Long): Long = (ms + 999) / 1_000
    }
}
