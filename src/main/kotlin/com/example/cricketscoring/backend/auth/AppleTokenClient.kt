package com.example.cricketscoring.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Apple's token endpoints, used only so Apple tokens can be revoked later (account deletion,
 * unlinking Apple). Both calls are best effort: they never fail a sign-in or a deletion.
 */
interface AppleTokenClient {
    /** Exchanges a one-time `authorizationCode` for Apple's refresh token, or null on any failure. */
    suspend fun exchangeCode(authorizationCode: String, clientId: String): String?

    /** Revokes a refresh token at Apple. Returns false on any failure. */
    suspend fun revoke(refreshToken: String, clientId: String): Boolean

    /** False when the Apple key settings aren't configured; codes are then ignored. */
    val enabled: Boolean

    companion object {
        fun fromConfig(config: AuthConfig): AppleTokenClient {
            val teamId = config.appleTeamId ?: return Disabled
            val keyId = config.appleKeyId ?: return Disabled
            val pem = config.applePrivateKey ?: return Disabled
            return HttpAppleTokenClient(AppleClientSecret(teamId, keyId, AppleClientSecret.parsePrivateKey(pem)))
        }
    }

    object Disabled : AppleTokenClient {
        override val enabled = false
        override suspend fun exchangeCode(authorizationCode: String, clientId: String): String? = null
        override suspend fun revoke(refreshToken: String, clientId: String) = false
    }
}

/**
 * The `client_secret` Apple wants on its token endpoints: an ES256 JWT signed with the `.p8` key,
 * `iss` = team ID, `sub` = client ID, `aud` = https://appleid.apple.com, lifetime at most 6 months.
 */
class AppleClientSecret(
    private val teamId: String,
    private val keyId: String,
    private val privateKey: ECPrivateKey,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun forClient(clientId: String): String {
        val now = clock()
        return JWT.create()
            .withKeyId(keyId)
            .withIssuer(teamId)
            .withSubject(clientId)
            .withAudience(AppleVerifier.ISSUER)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + 5 * 60_000))
            .sign(Algorithm.ECDSA256(null, privateKey))
    }

    companion object {
        /** Reads a `.p8` file's contents. Accepts real newlines or `\n` escapes (as env vars often carry them). */
        fun parsePrivateKey(pem: String): ECPrivateKey {
            val base64 = pem.replace("\\n", "\n")
                .lines()
                .filterNot { it.startsWith("-----") }
                .joinToString("") { it.trim() }
            val der = Base64.getDecoder().decode(base64)
            return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)) as ECPrivateKey
        }
    }
}

private class HttpAppleTokenClient(private val secret: AppleClientSecret) : AppleTokenClient {
    private val log = LoggerFactory.getLogger(HttpAppleTokenClient::class.java)
    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 10_000
        }
        expectSuccess = false
    }
    override val enabled = true

    override suspend fun exchangeCode(authorizationCode: String, clientId: String): String? = try {
        val response = client.submitForm(
            "https://appleid.apple.com/auth/token",
            parameters {
                append("client_id", clientId)
                append("client_secret", secret.forClient(clientId))
                append("code", authorizationCode)
                append("grant_type", "authorization_code")
            },
        )
        if (!response.status.isSuccess()) {
            // Apple's error body is e.g. {"error":"invalid_grant"}; it contains no tokens.
            log.warn("Apple code exchange rejected: status={} body={}", response.status.value, response.bodyAsText().take(200))
            null
        } else {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["refresh_token"]?.jsonPrimitive?.content
        }
    } catch (e: Exception) {
        log.warn("Apple code exchange failed: {}", e.javaClass.simpleName)
        null
    }

    override suspend fun revoke(refreshToken: String, clientId: String): Boolean = try {
        val response = client.submitForm(
            "https://appleid.apple.com/auth/revoke",
            parameters {
                append("client_id", clientId)
                append("client_secret", secret.forClient(clientId))
                append("token", refreshToken)
                append("token_type_hint", "refresh_token")
            },
        )
        if (!response.status.isSuccess()) {
            log.error("Apple token revoke rejected: status={} body={}", response.status.value, response.bodyAsText().take(200))
        }
        response.status.isSuccess()
    } catch (e: Exception) {
        log.error("Apple token revoke failed: {}", e.javaClass.simpleName)
        false
    }
}

/** AES-256-GCM for tokens at rest. Output is IV (12 bytes) || ciphertext+tag; [aad] binds it to its row. */
class TokenCipher(key: ByteArray) {
    private val keySpec = SecretKeySpec(key.also { require(it.size == 32) { "APPLE_TOKEN_ENCRYPTION_KEY must be 32 bytes (base64)" } }, "AES")
    private val random = SecureRandom()

    fun encrypt(plaintext: String, aad: String): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad.toByteArray())
        return iv + cipher.doFinal(plaintext.toByteArray())
    }

    /** Null if the data was tampered with, encrypted under another key, or belongs to another row. */
    fun decrypt(data: ByteArray, aad: String): String? {
        if (data.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(aad.toByteArray())
        return try {
            String(cipher.doFinal(data.copyOfRange(12, data.size)))
        } catch (_: AEADBadTagException) {
            null
        }
    }

    companion object {
        fun fromBase64(value: String) = TokenCipher(Base64.getDecoder().decode(value.trim()))
    }
}
