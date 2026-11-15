package com.example.cricketscoring.backend.auth

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** What Apple vouches for in an identity token. */
data class AppleIdentityClaims(
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val isPrivateEmail: Boolean,
    /** Which of our client IDs the token was issued to; Apple token calls for this user must use it. */
    val audience: String,
)

/** A server-to-server notification from Apple (consent revoked, Apple ID deleted, relay email toggled). */
data class AppleNotification(val type: String, val subject: String)

interface AppleVerifier {
    /** Verifies a Sign in with Apple identity token. [rawNonce] is the nonce the app kept; Apple holds its SHA-256. */
    fun verifyIdentityToken(identityToken: String, rawNonce: String): AppleIdentityClaims

    /** Verifies the signed `payload` of a server-to-server notification. */
    fun verifyNotification(payload: String): AppleNotification

    companion object {
        const val ISSUER = "https://appleid.apple.com"
        private const val KEYS_URL = "https://appleid.apple.com/auth/keys"

        fun fromConfig(config: AuthConfig, clock: () -> Long = System::currentTimeMillis): AppleVerifier {
            if (config.appleClientIds.isEmpty()) return NotConfigured
            // Keys are cached for a day; an unknown `kid` (Apple rotated) triggers a refetch, rate-limited.
            val keys = JwkProviderBuilder(URI(KEYS_URL).toURL())
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build()
            return AppleTokenVerifier(config.appleClientIds, keys, clock)
        }
    }

    private object NotConfigured : AppleVerifier {
        private fun fail(): Nothing =
            throw AuthException("apple_not_configured", "Sign in with Apple is not configured.", HttpStatusCode.ServiceUnavailable)

        override fun verifyIdentityToken(identityToken: String, rawNonce: String) = fail()
        override fun verifyNotification(payload: String) = fail()
    }
}

/**
 * Verifies Apple-signed JWTs: RS256 with a key from Apple's JWKS (picked by `kid`), `iss` = Apple,
 * `aud` one of [clientIds] (iOS bundle ID, Services ID for web/Android), `exp` with 60 s leeway.
 */
class AppleTokenVerifier(
    private val clientIds: List<String>,
    private val keys: JwkProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppleVerifier {
    private val javaClock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(clock())
    }

    override fun verifyIdentityToken(identityToken: String, rawNonce: String): AppleIdentityClaims {
        if (rawNonce.isBlank()) throw AuthException("validation_error", "nonce is required.", HttpStatusCode.BadRequest)
        val jwt = verify(identityToken) ?: throw invalidToken()
        // The app sends SHA-256(rawNonce) to Apple and the raw value to us, so a token lifted from
        // another app session can't be replayed here.
        val expected = sha256Hex(rawNonce)
        val actual = jwt.getClaim("nonce").asString()
        if (actual == null || !MessageDigest.isEqual(expected.toByteArray(), actual.lowercase().toByteArray())) {
            throw AuthException("nonce_mismatch", "Apple token nonce does not match.", HttpStatusCode.Unauthorized)
        }
        val subject = jwt.subject?.takeIf { it.isNotBlank() } ?: throw invalidToken()
        return AppleIdentityClaims(
            subject = subject,
            email = jwt.getClaim("email").asString()?.lowercase(),
            emailVerified = jwt.getClaim("email_verified").asLooseBoolean(),
            isPrivateEmail = jwt.getClaim("is_private_email").asLooseBoolean(),
            audience = jwt.audience.first { it in clientIds },
        )
    }

    override fun verifyNotification(payload: String): AppleNotification {
        val jwt = verify(payload) ?: throw invalidNotification()
        // `events` is a JSON object serialised as a string.
        val events = jwt.getClaim("events").asString()
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: throw invalidNotification()
        val type = events.string("type") ?: throw invalidNotification()
        val subject = events.string("sub") ?: throw invalidNotification()
        return AppleNotification(type, subject)
    }

    /** Returns the verified token, or null if the signature, key, issuer, audience or expiry is wrong. */
    private fun verify(token: String): DecodedJWT? {
        val decoded = try {
            JWT.decode(token)
        } catch (_: JWTDecodeException) {
            return null
        }
        if (decoded.algorithm != "RS256") return null
        val key = try {
            keys.get(decoded.keyId ?: return null).publicKey as? RSAPublicKey ?: return null
        } catch (_: JwkException) {
            return null
        }
        val verifier = JWT.require(Algorithm.RSA256(key, null))
            .withIssuer(AppleVerifier.ISSUER)
            .withAnyOfAudience(*clientIds.toTypedArray())
            .acceptLeeway(60)
        return try {
            (verifier as JWTVerifier.BaseVerification).build(javaClock).verify(decoded)
        } catch (_: JWTVerificationException) {
            null
        }
    }

    private fun invalidToken() =
        AuthException("invalid_apple_token", "Apple identity token is invalid or expired.", HttpStatusCode.Unauthorized)

    private fun invalidNotification() =
        AuthException("invalid_apple_notification", "Apple notification could not be verified.", HttpStatusCode.BadRequest)

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Apple sends some booleans as `true` and some as `"true"`. */
    private fun Claim.asLooseBoolean(): Boolean = asBoolean() ?: asString()?.equals("true", ignoreCase = true) ?: false

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.let {
        it.contentOrNull ?: it.booleanOrNull?.toString()
    }
}
