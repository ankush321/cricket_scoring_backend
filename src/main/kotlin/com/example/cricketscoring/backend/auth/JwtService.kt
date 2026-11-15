package com.example.cricketscoring.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID

/**
 * Access tokens: HS256, short-lived, and free of PII. Claims: `iss`, `aud`, `sub` (user id),
 * `sid` (session id), `amr` (sign-in method), `iat`, `exp`, `jti`.
 */
class JwtService(private val config: AuthConfig, private val clock: () -> Long = System::currentTimeMillis) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    fun mint(userId: UUID, sessionId: UUID, authMethod: AuthMethod): AuthToken {
        val now = clock()
        val token = JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(userId.toString())
            .withClaim(SESSION_CLAIM, sessionId.toString())
            .withClaim(METHOD_CLAIM, authMethod.name.lowercase())
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + config.accessTokenTtlSeconds * 1_000))
            .sign(algorithm)
        return AuthToken(token, config.accessTokenTtlSeconds)
    }

    /** Verifier for the Ktor `jwt` auth provider. Uses the same clock as minting, so tests can move time. */
    fun verifier(): JWTVerifier {
        val javaClock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant(): Instant = Instant.ofEpochMilli(clock())
        }
        val verification = JWT.require(algorithm)
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withClaimPresence(SESSION_CLAIM)
            .acceptLeeway(5)
        return (verification as JWTVerifier.BaseVerification).build(javaClock)
    }

    companion object {
        const val SESSION_CLAIM = "sid"
        const val METHOD_CLAIM = "amr"
    }
}

data class AuthToken(val accessToken: String, val expiresInSeconds: Long)

enum class AuthMethod { GOOGLE, APPLE, WHATSAPP }
