package com.example.cricketscoring.backend

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.SigningKeyNotFoundException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.cricketscoring.backend.auth.AppleTokenVerifier
import com.example.cricketscoring.backend.auth.AppleVerifier
import com.example.cricketscoring.backend.auth.AuthConfig
import com.example.cricketscoring.backend.auth.OtpDelivery
import com.example.cricketscoring.backend.auth.WhatsAppOtpSender
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Sign in with Apple through HTTP. Tokens are signed here with a generated RSA key, and the real
 * [AppleTokenVerifier] checks them via a stand-in [JwkProvider], so signature, kid, iss, aud, exp
 * and nonce handling are all exercised without calling Apple.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppleIntegrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private val now = AtomicLong(1_800_000_000_000L)
    private val lastOtp = AtomicReference<String?>(null)
    private val counter = AtomicInteger(0)

    private val appleKey: KeyPair = rsaKeyPair()
    private val otherKey: KeyPair = rsaKeyPair()
    private val clientId = "com.example.cricket"

    private val keys = JwkProvider { kid ->
        if (kid != KID) throw SigningKeyNotFoundException("unknown kid $kid", null)
        val pub = appleKey.public as RSAPublicKey
        Jwk.fromValues(
            mapOf("kty" to "RSA", "kid" to KID, "use" to "sig", "alg" to "RS256", "n" to b64(pub.modulus), "e" to b64(pub.publicExponent)),
        )
    }

    @BeforeAll
    fun startDatabase() {
        val available = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        assumeTrue(available, "Docker is required to run the Apple sign-in tests")
        postgres = PostgreSQLContainer("postgres:18-alpine")
        postgres.start()
    }

    private val appleTokens = FakeAppleTokenClient()

    private fun appleTest(appleVerifier: AppleVerifier? = null, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        appleTokens.exchanges.clear()
        appleTokens.revocations.clear()
        appleTokens.exchangeFails = false
        appleTokens.revokeFails = false
        val config = testAuthConfig().copy(
            jwtSecret = "test-secret",
            otpPepper = "test-pepper",
            appleClientIds = listOf(clientId),
            appleTokenEncryptionKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        )
        val deps = AuthDependencies(
            config = config,
            googleVerifier = fakeGoogleVerifier,
            whatsAppOtpSender = WhatsAppOtpSender { _, code -> lastOtp.set(code); OtpDelivery("test") },
            clock = now::get,
            appleVerifier = appleVerifier ?: AppleTokenVerifier(listOf(clientId), keys, now::get),
            appleTokenClient = appleTokens,
        )
        application { module(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), deps) }
        block()
    }

    // ---------------------------------------------------------------- token minting

    private fun appleToken(
        sub: String,
        email: String? = null,
        emailVerified: Any = "true",
        isPrivateEmail: Any? = null,
        rawNonce: String = NONCE,
        audience: String = clientId,
        issuer: String = AppleVerifier.ISSUER,
        expiresInSeconds: Long = 600,
        kid: String = KID,
        signWith: KeyPair = appleKey,
    ): String {
        val builder = JWT.create().withKeyId(kid).withIssuer(issuer).withAudience(audience).withSubject(sub)
            .withIssuedAt(Date(now.get())).withExpiresAt(Date(now.get() + expiresInSeconds * 1_000))
            .withClaim("nonce", sha256Hex(rawNonce))
        if (email != null) builder.withClaim("email", email)
        when (emailVerified) {
            is Boolean -> builder.withClaim("email_verified", emailVerified)
            else -> builder.withClaim("email_verified", emailVerified.toString())
        }
        when (isPrivateEmail) {
            is Boolean -> builder.withClaim("is_private_email", isPrivateEmail)
            is String -> builder.withClaim("is_private_email", isPrivateEmail)
        }
        return builder.sign(Algorithm.RSA256(signWith.public as RSAPublicKey, signWith.private as RSAPrivateKey))
    }

    private fun notification(type: String, sub: String, signWith: KeyPair = appleKey): String =
        JWT.create().withKeyId(KID).withIssuer(AppleVerifier.ISSUER).withAudience(clientId)
            .withIssuedAt(Date(now.get())).withExpiresAt(Date(now.get() + 600_000))
            .withClaim("events", """{"type":"$type","sub":"$sub","event_time":${now.get()}}""")
            .sign(Algorithm.RSA256(signWith.public as RSAPublicKey, signWith.private as RSAPrivateKey))

    // ---------------------------------------------------------------- HTTP helpers

    private suspend fun HttpClient.postJson(path: String, body: String, accessToken: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        accessToken?.let { bearerAuth(it) }
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.appleSignIn(
        token: String,
        nonce: String = NONCE,
        fullName: String? = null,
        code: String? = null,
    ): HttpResponse = client.postJson(
        "/auth/apple",
        """{"identityToken":"$token","nonce":"$nonce"${fullName?.let { ",\"fullName\":$it" } ?: ""}${code?.let { ",\"authorizationCode\":\"$it\"" } ?: ""}}""",
    )

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.user() = getValue("user").jsonObject

    private suspend fun ApplicationTestBuilder.identities(accessToken: String) =
        Json.parseToJsonElement(client.get("/auth/me/identities") { bearerAuth(accessToken) }.bodyAsText()).jsonArray.map { it.jsonObject }

    private suspend fun ApplicationTestBuilder.whatsAppSignIn(phone: String): JsonObject {
        now.addAndGet(61_000)
        val request = client.postJson("/auth/whatsapp/otp/request", """{"phone":"$phone"}""").json()
        return client.postJson(
            "/auth/whatsapp/otp/verify",
            """{"phone":"$phone","code":"${lastOtp.get()}","challengeId":"${request.str("challengeId")}"}""",
        ).json()
    }

    private fun fresh() = counter.incrementAndGet()

    // ---------------------------------------------------------------- tests

    @Test
    fun firstAppleSignInCreatesAccountWithNameFromApp() = appleTest {
        val n = fresh()
        val response = appleSignIn(appleToken("apple-$n", email = "ravi$n@example.com"), fullName = """{"givenName":"Ravi","familyName":"Kumar"}""")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals("true", body.str("isNewUser"))
        assertEquals("Ravi Kumar", body.user().str("displayName"))
        assertEquals("ravi$n@example.com", body.user().str("email"))
        assertEquals(listOf("APPLE"), body.user().getValue("linkedProviders").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("apple", JWT.decode(body.str("accessToken")).getClaim("amr").asString())

        // Later sign-ins carry no name: the account keeps the one it has.
        val again = appleSignIn(appleToken("apple-$n", email = "ravi$n@example.com")).json()
        assertEquals("false", again.str("isNewUser"))
        assertEquals(body.user().str("id"), again.user().str("id"))
        assertEquals("Ravi Kumar", again.user().str("displayName"))
    }

    @Test
    fun privateRelayEmailIsNeverTheContactEmail() = appleTest {
        val n = fresh()
        val body = appleSignIn(appleToken("apple-$n", email = "x$n@privaterelay.appleid.com", isPrivateEmail = "true")).json()
        assertTrue(body.user()["email"] == null || body.user()["email"] == JsonNull)
        assertEquals("Player", body.user().str("displayName"))
        val identity = identities(body.str("accessToken")).single()
        assertEquals("true", identity.str("isPrivateRelay"))
        assertEquals("x$n@privaterelay.appleid.com", identity.str("email"))
    }

    @Test
    fun verifiedAppleEmailAttachesToTheGoogleAccountWithThatEmail() = appleTest {
        val n = fresh()
        val google = client.postJson("/auth/google", """{"idToken":"${googleToken("g-apple-$n", "same$n@example.com")}"}""").json()
        val apple = appleSignIn(appleToken("apple-$n", email = "same$n@example.com", emailVerified = true)).json()
        assertEquals(google.user().str("id"), apple.user().str("id"))
        assertEquals("false", apple.str("isNewUser"))
        assertEquals(setOf("GOOGLE", "APPLE"), apple.user().getValue("linkedProviders").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun unverifiedAppleEmailDoesNotAttach() = appleTest {
        val n = fresh()
        val google = client.postJson("/auth/google", """{"idToken":"${googleToken("g-unv-$n", "unv$n@example.com")}"}""").json()
        val apple = appleSignIn(appleToken("apple-$n", email = "unv$n@example.com", emailVerified = "false")).json()
        assertNotEquals(google.user().str("id"), apple.user().str("id"))
        assertEquals("true", apple.str("isNewUser"))
    }

    @Test
    fun badTokensAreRejected() = appleTest {
        val sub = "apple-bad-${fresh()}"
        val cases = listOf(
            "wrong audience" to appleSignIn(appleToken(sub, audience = "com.someone.else")),
            "wrong issuer" to appleSignIn(appleToken(sub, issuer = "https://evil.example.com")),
            "expired" to appleSignIn(appleToken(sub, expiresInSeconds = -61)),
            "signed by another key" to appleSignIn(appleToken(sub, signWith = otherKey)),
            "unknown kid" to appleSignIn(appleToken(sub, kid = "rotated-away")),
            "not a JWT" to appleSignIn("garbage"),
        )
        for ((label, response) in cases) {
            assertEquals(HttpStatusCode.Unauthorized, response.status, label)
            assertEquals("invalid_apple_token", response.json().str("code"), label)
        }
        val wrongNonce = appleSignIn(appleToken(sub), nonce = "a-different-nonce")
        assertEquals(HttpStatusCode.Unauthorized, wrongNonce.status)
        assertEquals("nonce_mismatch", wrongNonce.json().str("code"))
        assertEquals(HttpStatusCode.BadRequest, appleSignIn(appleToken(sub), nonce = "").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/auth/apple", """{"identityToken":"x"}""").status)
        // Within the 60 s leeway an expired token is still accepted.
        assertEquals(HttpStatusCode.OK, appleSignIn(appleToken(sub, expiresInSeconds = -30)).status)
    }

    @Test
    fun notConfiguredIsA503() = appleTest(appleVerifier = AppleVerifier.fromConfig(AuthConfig.fromEnv { null })) {
        val response = appleSignIn(appleToken("whatever"))
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("apple_not_configured", response.json().str("code"))
    }

    @Test
    fun whatsAppAccountLinksAppleThenAppleSignsIntoIt() = appleTest {
        val n = fresh()
        val phone = "+9197000%05d".format(n)
        val wa = whatsAppSignIn(phone)
        val link = client.postJson(
            "/auth/me/identities/apple",
            """{"identityToken":"${appleToken("apple-$n", email = "link$n@example.com")}","nonce":"$NONCE","fullName":{"givenName":"Asha"}}""",
            accessToken = wa.str("accessToken"),
        )
        assertEquals(HttpStatusCode.OK, link.status, link.bodyAsText())
        assertEquals("APPLE", link.json().str("provider"))

        val viaApple = appleSignIn(appleToken("apple-$n", email = "link$n@example.com")).json()
        assertEquals(wa.user().str("id"), viaApple.user().str("id"))
        assertEquals("Asha", viaApple.user().str("displayName"), "generated name replaced on link")
        assertEquals("link$n@example.com", viaApple.user().str("email"))
    }

    @Test
    fun consentRevokedEndsSessionsButKeepsTheIdentity() = appleTest {
        val sub = "apple-revoke-${fresh()}"
        val first = appleSignIn(appleToken(sub)).json()
        val ack = client.postJson("/auth/apple/notifications", """{"payload":"${notification("consent-revoked", sub)}"}""")
        assertEquals(HttpStatusCode.OK, ack.status)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { bearerAuth(first.str("accessToken")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/auth/token/refresh", """{"refreshToken":"${first.str("refreshToken")}"}""").status)
        // Re-granting consent gives the same sub, which still opens the same account.
        val again = appleSignIn(appleToken(sub)).json()
        assertEquals(first.user().str("id"), again.user().str("id"))
    }

    @Test
    fun accountDeleteRemovesTheAppleIdentityWhenAnotherRemains() = appleTest {
        val n = fresh()
        val wa = whatsAppSignIn("+9196000%05d".format(n))
        client.postJson(
            "/auth/me/identities/apple",
            """{"identityToken":"${appleToken("apple-del-$n")}","nonce":"$NONCE"}""",
            accessToken = wa.str("accessToken"),
        )
        client.postJson("/auth/apple/notifications", """{"payload":"${notification("account-delete", "apple-del-$n")}"}""")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { bearerAuth(wa.str("accessToken")) }.status)

        val back = whatsAppSignIn("+9196000%05d".format(n))
        assertEquals(wa.user().str("id"), back.user().str("id"))
        assertEquals(listOf("WHATSAPP"), identities(back.str("accessToken")).map { it.str("provider") })
    }

    @Test
    fun accountDeleteKeepsTheLastIdentity() = appleTest {
        val sub = "apple-last-${fresh()}"
        val only = appleSignIn(appleToken(sub)).json()
        client.postJson("/auth/apple/notifications", """{"payload":"${notification("account-delete", sub)}"}""")
        assertEquals(
            "1",
            java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
                c.createStatement().executeQuery("SELECT count(*) FROM user_identities WHERE user_id = '${only.user().str("id")}'").use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            },
        )
    }

    @Test
    fun notificationsMustBeSignedByApple() = appleTest {
        val sub = "apple-forged-${fresh()}"
        val user = appleSignIn(appleToken(sub)).json()
        val forged = client.postJson("/auth/apple/notifications", """{"payload":"${notification("consent-revoked", sub, signWith = otherKey)}"}""")
        assertEquals(HttpStatusCode.BadRequest, forged.status)
        assertEquals("invalid_apple_notification", forged.json().str("code"))
        assertEquals(HttpStatusCode.OK, client.get("/auth/me") { bearerAuth(user.str("accessToken")) }.status, "forged revoke ignored")

        // Unknown subject and ignored event types are acknowledged without effect.
        assertEquals(HttpStatusCode.OK, client.postJson("/auth/apple/notifications", """{"payload":"${notification("consent-revoked", "nobody")}"}""").status)
        assertEquals(HttpStatusCode.OK, client.postJson("/auth/apple/notifications", """{"payload":"${notification("email-disabled", sub)}"}""").status)
        assertEquals(HttpStatusCode.OK, client.get("/auth/me") { bearerAuth(user.str("accessToken")) }.status)
    }

    @Test
    fun appleIdentityCanBeUnlinkedWhenAnotherRemains() = appleTest {
        val n = fresh()
        val wa = whatsAppSignIn("+9195000%05d".format(n))
        val linked = client.postJson(
            "/auth/me/identities/apple",
            """{"identityToken":"${appleToken("apple-unlink-$n")}","nonce":"$NONCE"}""",
            accessToken = wa.str("accessToken"),
        ).json()
        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/me/identities/${linked.str("id")}") { bearerAuth(wa.str("accessToken")) }.status)
    }

    // ---------------------------------------------------------------- Apple token revocation

    private fun appleTokenRows(sub: String): List<String> =
        java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().executeQuery(
                "SELECT t.client_id, encode(t.refresh_token_cipher, 'escape') FROM apple_tokens t " +
                    "JOIN user_identities i ON i.id = t.identity_id WHERE i.provider_subject = '$sub'",
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) + "|" + rs.getString(2)) } }
        }

    @Test
    fun authorizationCodeIsExchangedAndTheTokenStoredEncrypted() = appleTest {
        val sub = "apple-code-${fresh()}"
        assertEquals(HttpStatusCode.OK, appleSignIn(appleToken(sub), code = "code-1").status)
        assertEquals(listOf("code-1" to clientId), appleTokens.exchanges, "exchanged for the token's audience")
        val stored = appleTokenRows(sub).single()
        assertTrue(stored.startsWith("$clientId|"))
        assertTrue(!stored.contains("apple-rt-code-1"), "refresh token not stored in plain text")

        appleSignIn(appleToken(sub)) // no code: nothing exchanged, stored token kept
        assertEquals(1, appleTokens.exchanges.size)
        assertEquals(1, appleTokenRows(sub).size)
    }

    @Test
    fun accountDeletionRevokesTheLatestAppleToken() = appleTest {
        val sub = "apple-delrevoke-${fresh()}"
        appleSignIn(appleToken(sub), code = "first")
        val auth = appleSignIn(appleToken(sub), code = "second").json() // a newer code replaces the stored token
        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/me") { bearerAuth(auth.str("accessToken")) }.status)
        assertEquals(listOf("apple-rt-second" to clientId), appleTokens.revocations)
        assertEquals(emptyList(), appleTokenRows(sub))
    }

    @Test
    fun deletionGoesAheadWhenAppleRevokeFails() = appleTest {
        val sub = "apple-revokefail-${fresh()}"
        val auth = appleSignIn(appleToken(sub), code = "c").json()
        appleTokens.revokeFails = true
        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/me") { bearerAuth(auth.str("accessToken")) }.status)
        assertEquals(1, appleTokens.revocations.size)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { bearerAuth(auth.str("accessToken")) }.status)
    }

    @Test
    fun failedCodeExchangeDoesNotBlockSignIn() = appleTest {
        val sub = "apple-exchfail-${fresh()}"
        appleTokens.exchangeFails = true
        assertEquals(HttpStatusCode.OK, appleSignIn(appleToken(sub), code = "bad").status)
        assertEquals(emptyList(), appleTokenRows(sub))
    }

    @Test
    fun unlinkingAppleRevokesItsTokenButTheLastIdentityIsKept() = appleTest {
        val n = fresh()
        val wa = whatsAppSignIn("+9194000%05d".format(n))
        val linked = client.postJson(
            "/auth/me/identities/apple",
            """{"identityToken":"${appleToken("apple-ul-$n")}","nonce":"$NONCE","authorizationCode":"link-code"}""",
            accessToken = wa.str("accessToken"),
        ).json()
        assertEquals(1, appleTokenRows("apple-ul-$n").size)
        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/me/identities/${linked.str("id")}") { bearerAuth(wa.str("accessToken")) }.status)
        assertEquals(listOf("apple-rt-link-code" to clientId), appleTokens.revocations)

        // Apple-only account: unlinking is refused, so nothing is revoked.
        val only = appleSignIn(appleToken("apple-only-$n"), code = "only").json()
        val identityId = identities(only.str("accessToken")).single().str("id")
        assertEquals(HttpStatusCode.Conflict, client.delete("/auth/me/identities/$identityId") { bearerAuth(only.str("accessToken")) }.status)
        assertEquals(1, appleTokens.revocations.size)
    }

    private companion object {
        const val KID = "test-kid"
        const val NONCE = "raw-nonce-from-the-app"

        fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        /** Unsigned big-endian base64url, as JWKs encode RSA numbers. */
        fun b64(n: BigInteger): String {
            val bytes = n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun sha256Hex(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
