package com.example.cricketscoring.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.cricketscoring.backend.auth.AppleClientSecret
import com.example.cricketscoring.backend.auth.AuthConfig
import com.example.cricketscoring.backend.auth.TokenCipher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppleTokenCryptoTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun cipherRoundTripsAndIsBoundToItsRow() {
        val cipher = TokenCipher(key)
        val sealed = cipher.encrypt("apple-refresh-token", "identity-1")
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("apple-refresh-token"))
        assertEquals("apple-refresh-token", cipher.decrypt(sealed, "identity-1"))
        assertNull(cipher.decrypt(sealed, "identity-2"), "copied to another row")
        assertNull(cipher.decrypt(sealed.copyOf().also { it[20] = (it[20] + 1).toByte() }, "identity-1"), "tampered")
        assertNull(TokenCipher(ByteArray(32)).decrypt(sealed, "identity-1"), "other key")
        assertThrows<IllegalArgumentException> { TokenCipher(ByteArray(16)) }
        assertEquals("x", TokenCipher.fromBase64(Base64.getEncoder().encodeToString(key)).let { it.decrypt(it.encrypt("x", "a"), "a") })
    }

    @Test
    fun clientSecretIsAnEs256JwtApplesTokenEndpointAccepts() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded)
        val pem = "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
        val escaped = pem.replace("\n", "\\n") // as it often arrives through an env var

        for (input in listOf(pem, escaped)) {
            val secret = AppleClientSecret("TEAM123", "KEY456", AppleClientSecret.parsePrivateKey(input), clock = { 1_800_000_000_000L })
                .forClient("com.example.cricket")
            val jwt = JWT.decode(secret)
            assertEquals("ES256", jwt.algorithm)
            assertEquals("KEY456", jwt.keyId)
            assertEquals("TEAM123", jwt.issuer)
            assertEquals("com.example.cricket", jwt.subject)
            assertEquals(listOf("https://appleid.apple.com"), jwt.audience)
            Algorithm.ECDSA256(pair.public as ECPublicKey, null).verify(jwt) // throws if the signature is wrong
        }
    }

    @Test
    fun appleRevocationSettingsAreAllOrNothing() {
        assertThrows<IllegalStateException> { AuthConfig.fromEnv(mapOf("APPLE_TEAM_ID" to "T")::get) }
        val all = mapOf("APPLE_TEAM_ID" to "T", "APPLE_KEY_ID" to "K", "APPLE_PRIVATE_KEY" to "P", "APPLE_TOKEN_ENCRYPTION_KEY" to "E")
        assertEquals("T", AuthConfig.fromEnv(all::get).appleTeamId)
        assertNull(AuthConfig.fromEnv { null }.appleTeamId)
    }
}
