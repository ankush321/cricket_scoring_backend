package com.example.cricketscoring.backend

import com.example.cricketscoring.backend.auth.AuthConfig
import com.example.cricketscoring.backend.auth.AuthException
import com.example.cricketscoring.backend.auth.GoogleProfile
import com.example.cricketscoring.backend.auth.GoogleVerifier
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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthIntegrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private val lastOtp = AtomicReference<String?>(null)
    private val failNextSend = AtomicBoolean(false)
    private val now = AtomicLong(0)
    private val phoneCounter = AtomicInteger(0)

    @BeforeAll
    fun startDatabase() {
        val available = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        assumeTrue(available, "Docker is required to run auth integration tests")
        postgres = PostgreSQLContainer("postgres:18-alpine")
        postgres.start()
    }

    @BeforeEach
    fun reset() {
        lastOtp.set(null)
        failNextSend.set(false)
        now.set(1_800_000_000_000L)
    }

    /** Each test uses its own phone so rate-limit history from other tests can't interfere. */
    private fun freshPhone() = "+9198765%05d".format(phoneCounter.incrementAndGet())

    private fun authDependencies(): AuthDependencies {
        val config = testAuthConfig().copy(
            jwtSecret = "test-secret",
            otpPepper = "test-pepper",
            googleClientIds = listOf("test-client-id"),
        )
        val googleVerifier = fakeGoogleVerifier
        val otpSender = WhatsAppOtpSender { _, code ->
            if (failNextSend.getAndSet(false)) {
                throw AuthException("otp_delivery_failed", "down", HttpStatusCode.BadGateway)
            }
            lastOtp.set(code)
            OtpDelivery("test", "wamid.test")
        }
        return AuthDependencies(config, googleVerifier, otpSender, clock = now::get)
    }

    private fun authTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            module(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), authDependencies())
        }
        block()
    }

    private suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpClient.requestOtp(phone: String) = postJson("/auth/whatsapp/otp/request", """{"phone":"$phone"}""")

    private suspend fun HttpClient.verifyOtp(phone: String, code: String, challengeId: String? = null) = postJson(
        "/auth/whatsapp/otp/verify",
        if (challengeId == null) {
            """{"phone":"$phone","code":"$code"}"""
        } else {
            """{"phone":"$phone","code":"$code","challengeId":"$challengeId"}"""
        },
    )

    private suspend fun HttpResponse.field(name: String): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue(name).jsonPrimitive.content

    private fun wrongCode(code: String) = if (code == "000000") "111111" else "000000"

    private fun advanceSeconds(seconds: Long) {
        now.addAndGet(seconds * 1_000)
    }

    private fun dbQuery(sql: String): String? = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
        c.createStatement().executeQuery(sql).use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    @Test
    fun googleSignInCreatesUserAndToken() = authTest {
        val response = client.postJson("/auth/google", """{"idToken":"valid-google-token"}""")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("accessToken"))
        assertTrue(body.contains("player@example.com"))
    }

    @Test
    fun whatsAppOtpSignInWithChallengeId() = authTest {
        val phone = freshPhone()
        val request = client.requestOtp(phone)
        assertEquals(HttpStatusCode.OK, request.status)
        assertEquals("3", request.field("attemptsAllowed"))
        val code = lastOtp.get() ?: error("OTP was not captured")
        assertEquals(6, code.length)

        val verify = client.verifyOtp(phone, code, request.field("challengeId"))
        assertEquals(HttpStatusCode.OK, verify.status)
        assertTrue(verify.bodyAsText().contains(phone))

        // A code works once.
        assertEquals(HttpStatusCode.Unauthorized, client.verifyOtp(phone, code).status)
    }

    @Test
    fun verifyWithoutChallengeIdStillWorksForOlderClients() = authTest {
        val phone = freshPhone()
        client.requestOtp(phone)
        assertEquals(HttpStatusCode.OK, client.verifyOtp(phone, lastOtp.get()!!).status)
    }

    @Test
    fun codeIsStoredAsHmacNotPlainText() = authTest {
        val phone = freshPhone()
        client.requestOtp(phone)
        val code = lastOtp.get()!!
        val stored = dbQuery("SELECT encode(code_hash, 'hex') FROM otp_challenges WHERE phone_e164 = '$phone'")!!
        assertEquals(64, stored.length)
        assertTrue(!stored.contains(code))
    }

    @Test
    fun threeWrongCodesLockTheChallenge() = authTest {
        val phone = freshPhone()
        client.requestOtp(phone)
        val code = lastOtp.get()!!

        val first = client.verifyOtp(phone, wrongCode(code))
        assertEquals(HttpStatusCode.Unauthorized, first.status)
        assertTrue(first.bodyAsText().contains("2 attempt(s) left"))
        assertEquals(HttpStatusCode.Unauthorized, client.verifyOtp(phone, wrongCode(code)).status)

        val third = client.verifyOtp(phone, wrongCode(code))
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
        assertEquals("otp_locked", third.field("code"))

        // Even the right code is useless now.
        assertEquals(HttpStatusCode.Unauthorized, client.verifyOtp(phone, code).status)
    }

    @Test
    fun resendCooldownReturnsRetryAfter() = authTest {
        val phone = freshPhone()
        client.requestOtp(phone)
        advanceSeconds(20)
        val again = client.requestOtp(phone)
        assertEquals(HttpStatusCode.TooManyRequests, again.status)
        assertEquals("otp_cooldown", again.field("code"))
        assertEquals("40", again.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun atMostThreeSendsPerTenMinutes() = authTest {
        val phone = freshPhone()
        repeat(3) {
            assertEquals(HttpStatusCode.OK, client.requestOtp(phone).status)
            advanceSeconds(61)
        }
        val fourth = client.requestOtp(phone)
        assertEquals(HttpStatusCode.TooManyRequests, fourth.status)
        assertEquals("otp_rate_limited", fourth.field("code"))
        // First send was at t=0, now is t=183s: the window frees up at t=600s.
        assertEquals("417", fourth.headers[HttpHeaders.RetryAfter])

        advanceSeconds(417)
        assertEquals(HttpStatusCode.OK, client.requestOtp(phone).status)
    }

    @Test
    fun resendResetsNothingAndInvalidatesTheOldCode() = authTest {
        val phone = freshPhone()
        client.requestOtp(phone)
        val oldCode = lastOtp.get()!!
        advanceSeconds(61)
        client.requestOtp(phone)
        val newCode = lastOtp.get()!!
        if (oldCode != newCode) {
            assertEquals(HttpStatusCode.Unauthorized, client.verifyOtp(phone, oldCode).status)
        }
        assertEquals(HttpStatusCode.OK, client.verifyOtp(phone, newCode).status)
    }

    @Test
    fun expiredCodeIsRejected() = authTest {
        val phone = freshPhone()
        client.requestOtp(phone)
        advanceSeconds(301)
        assertEquals(HttpStatusCode.Unauthorized, client.verifyOtp(phone, lastOtp.get()!!).status)
    }

    @Test
    fun failedSendDoesNotTriggerCooldown() = authTest {
        val phone = freshPhone()
        failNextSend.set(true)
        val failed = client.requestOtp(phone)
        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertEquals("otp_delivery_failed", failed.field("code"))

        val retry = client.requestOtp(phone)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(HttpStatusCode.OK, client.verifyOtp(phone, lastOtp.get()!!, retry.field("challengeId")).status)
    }

    @Test
    fun challengeIdMustMatchTheLiveChallenge() = authTest {
        val phone = freshPhone()
        client.requestOtp(phone)
        val other = "0190d4c0-0000-7000-8000-000000000000"
        assertEquals(HttpStatusCode.Unauthorized, client.verifyOtp(phone, lastOtp.get()!!, other).status)
        assertEquals(HttpStatusCode.BadRequest, client.verifyOtp(phone, lastOtp.get()!!, "not-a-uuid").status)
    }

    @Test
    fun malformedJsonIsA400() = authTest {
        val broken = client.postJson("/auth/whatsapp/otp/request", """{"phone":""")
        assertEquals(HttpStatusCode.BadRequest, broken.status)
        assertEquals("validation_error", broken.field("code"))
        val missingField = client.postJson("/auth/whatsapp/otp/verify", """{"phone":"+919876543210"}""")
        assertEquals(HttpStatusCode.BadRequest, missingField.status)
    }

    @Test
    fun differentPhonesGetDifferentChallenges() = authTest {
        val a = client.requestOtp(freshPhone()).field("challengeId")
        val b = client.requestOtp(freshPhone()).field("challengeId")
        assertNotEquals(a, b)
    }

    // ---------------------------------------------------------------- sessions (phase 2)

    private val day = 86_400L

    /** Signs in over WhatsApp and returns the AuthResponse JSON. Moves the clock past the resend cooldown first. */
    private suspend fun ApplicationTestBuilder.signIn(phone: String, deviceName: String = "Pixel"): JsonObject {
        advanceSeconds(61)
        val request = client.requestOtp(phone)
        assertEquals(HttpStatusCode.OK, request.status, request.bodyAsText())
        val verify = client.postJson(
            "/auth/whatsapp/otp/verify",
            """{"phone":"$phone","code":"${lastOtp.get()}","challengeId":"${request.field("challengeId")}","deviceName":"$deviceName"}""",
        )
        assertEquals(HttpStatusCode.OK, verify.status, verify.bodyAsText())
        return Json.parseToJsonElement(verify.bodyAsText()).jsonObject
    }

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.me(accessToken: String) = client.get("/auth/me") { bearerAuth(accessToken) }

    private suspend fun ApplicationTestBuilder.refresh(refreshToken: String) =
        client.postJson("/auth/token/refresh", """{"refreshToken":"$refreshToken"}""")

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun accessTokenIsShortLivedAndCarriesNoPii() = authTest {
        val auth = signIn(freshPhone())
        assertEquals("900", auth.str("expiresInSeconds"))
        assertEquals("Bearer", auth.str("tokenType"))
        assertEquals((30 * day).toString(), auth.str("refreshTokenExpiresInSeconds"))
        val jwt = JWT.decode(auth.str("accessToken"))
        assertEquals("cricket-scoring-backend", jwt.issuer)
        assertEquals(listOf("cricket-scoring-app"), jwt.audience)
        assertEquals("whatsapp", jwt.getClaim("amr").asString())
        assertTrue(jwt.getClaim("sid").asString().isNotBlank())
        assertTrue(jwt.getClaim("email").isMissing && jwt.getClaim("phone").isMissing, "no PII in the token")
        assertEquals(900_000, jwt.expiresAt.time - jwt.issuedAt.time)
    }

    @Test
    fun meNeedsAValidAccessToken() = authTest {
        val phone = freshPhone()
        val auth = signIn(phone)
        val ok = me(auth.str("accessToken"))
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(phone, ok.json().str("phone"))

        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me").status)
        val garbage = me("not-a-jwt")
        assertEquals(HttpStatusCode.Unauthorized, garbage.status)
        assertEquals("unauthorized", garbage.field("code"))

        val claims = JWT.decode(auth.str("accessToken"))
        val forged = JWT.create().withIssuer(claims.issuer).withAudience("cricket-scoring-app").withSubject(claims.subject)
            .withClaim("sid", claims.getClaim("sid").asString()).withExpiresAt(java.util.Date(now.get() + 60_000))
            .sign(Algorithm.HMAC256("wrong-secret"))
        assertEquals(HttpStatusCode.Unauthorized, me(forged).status)
    }

    @Test
    fun expiredAccessTokenIsRejectedButRefreshWorks() = authTest {
        val auth = signIn(freshPhone())
        advanceSeconds(900 + 6) // past exp plus the 5 s leeway
        assertEquals(HttpStatusCode.Unauthorized, me(auth.str("accessToken")).status)
        val refreshed = refresh(auth.str("refreshToken"))
        assertEquals(HttpStatusCode.OK, refreshed.status)
        assertEquals(HttpStatusCode.OK, me(refreshed.json().str("accessToken")).status)
    }

    @Test
    fun refreshRotatesTheToken() = authTest {
        val auth = signIn(freshPhone())
        val first = refresh(auth.str("refreshToken")).json()
        assertNotEquals(auth.str("refreshToken"), first.str("refreshToken"))
        val second = refresh(first.str("refreshToken"))
        assertEquals(HttpStatusCode.OK, second.status)
    }

    @Test
    fun reusingAnOldRefreshTokenRevokesTheSession() = authTest {
        val auth = signIn(freshPhone())
        val rotated = refresh(auth.str("refreshToken")).json()
        advanceSeconds(11) // outside the 10 s grace window

        val replay = refresh(auth.str("refreshToken"))
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertEquals("invalid_refresh_token", replay.field("code"))
        // The legitimate holder is logged out too, and so is the access token for that session.
        assertEquals(HttpStatusCode.Unauthorized, refresh(rotated.str("refreshToken")).status)
        assertEquals(HttpStatusCode.Unauthorized, me(rotated.str("accessToken")).status)
    }

    @Test
    fun retryWithinGraceWindowIsTolerated() = authTest {
        val auth = signIn(freshPhone())
        val lost = refresh(auth.str("refreshToken")).json() // response "lost" by the client
        advanceSeconds(5)
        val retry = refresh(auth.str("refreshToken"))
        assertEquals(HttpStatusCode.OK, retry.status)
        val next = refresh(retry.json().str("refreshToken"))
        assertEquals(HttpStatusCode.OK, next.status)

        // The token from the lost response was retired; showing it now is reuse, which revokes the session.
        assertEquals(HttpStatusCode.Unauthorized, refresh(lost.str("refreshToken")).status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(next.json().str("refreshToken")).status)
    }

    @Test
    fun idleRefreshTokenExpiresAfter30Days() = authTest {
        val auth = signIn(freshPhone())
        advanceSeconds(30 * day + 1)
        assertEquals(HttpStatusCode.Unauthorized, refresh(auth.str("refreshToken")).status)
    }

    @Test
    fun sessionEndsAfter90DaysEvenWhenActive() = authTest {
        var token = signIn(freshPhone()).str("refreshToken")
        repeat(4) { // refresh on days 20, 40, 60, 80
            advanceSeconds(20 * day)
            val body = refresh(token).json()
            token = body.str("refreshToken")
            if (it == 3) assertEquals((10 * day).toString(), body.str("refreshTokenExpiresInSeconds"), "capped at the session end")
        }
        advanceSeconds(10 * day + 1)
        assertEquals(HttpStatusCode.Unauthorized, refresh(token).status)
    }

    @Test
    fun logoutEndsOnlyTheCurrentSession() = authTest {
        val phone = freshPhone()
        val a = signIn(phone, "Phone A")
        val b = signIn(phone, "Phone B")
        assertEquals(HttpStatusCode.NoContent, client.post("/auth/logout") { bearerAuth(a.str("accessToken")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, me(a.str("accessToken")).status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(a.str("refreshToken")).status)
        assertEquals(HttpStatusCode.OK, me(b.str("accessToken")).status)
    }

    @Test
    fun logoutAllEndsEverySession() = authTest {
        val phone = freshPhone()
        val a = signIn(phone, "Phone A")
        val b = signIn(phone, "Phone B")
        assertEquals(HttpStatusCode.NoContent, client.post("/auth/logout-all") { bearerAuth(a.str("accessToken")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, me(b.str("accessToken")).status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(b.str("refreshToken")).status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(a.str("refreshToken")).status)
    }

    @Test
    fun listAndRevokeSessions() = authTest {
        val phone = freshPhone()
        val a = signIn(phone, "Phone A")
        val b = signIn(phone, "Phone B")
        val list = client.get("/auth/sessions") { bearerAuth(a.str("accessToken")) }
        assertEquals(HttpStatusCode.OK, list.status)
        val sessions = Json.parseToJsonElement(list.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(setOf("Phone A", "Phone B"), sessions.map { it.str("deviceName") }.toSet())
        assertEquals("Phone A", sessions.single { it.str("current") == "true" }.str("deviceName"))
        assertEquals("WHATSAPP", sessions.first().str("authMethod"))

        val bId = sessions.single { it.str("deviceName") == "Phone B" }.str("id")
        // Another user can't revoke it.
        val stranger = signIn(freshPhone())
        assertEquals(HttpStatusCode.NotFound, client.delete("/auth/sessions/$bId") { bearerAuth(stranger.str("accessToken")) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/auth/sessions/not-a-uuid") { bearerAuth(a.str("accessToken")) }.status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/sessions/$bId") { bearerAuth(a.str("accessToken")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(b.str("refreshToken")).status)
        assertEquals(HttpStatusCode.OK, me(a.str("accessToken")).status)
    }

    @Test
    fun googleSignInStartsASession() = authTest {
        val auth = client.postJson("/auth/google", """{"idToken":"valid-google-token","deviceName":"iPad"}""").json()
        assertEquals("google", JWT.decode(auth.str("accessToken")).getClaim("amr").asString())
        assertEquals(HttpStatusCode.OK, refresh(auth.str("refreshToken")).status)
    }

    @Test
    fun garbageRefreshTokenIsRejected() = authTest {
        assertEquals(HttpStatusCode.Unauthorized, refresh("nope").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/auth/token/refresh", "{}").status)
    }

    // ---------------------------------------------------------------- linked identities (phase 3)

    private suspend fun ApplicationTestBuilder.googleSignIn(token: String): HttpResponse =
        client.postJson("/auth/google", """{"idToken":"$token"}""")

    private suspend fun ApplicationTestBuilder.linkGoogle(accessToken: String, token: String) =
        client.post("/auth/me/identities/google") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"$token"}""")
        }

    private suspend fun ApplicationTestBuilder.linkWhatsApp(accessToken: String, phone: String, wrongCode: Boolean = false): HttpResponse {
        advanceSeconds(61)
        val request = client.requestOtp(phone)
        assertEquals(HttpStatusCode.OK, request.status, request.bodyAsText())
        return client.post("/auth/me/identities/whatsapp") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            val code = lastOtp.get()!!.let { if (wrongCode) wrongCode(it) else it }
            setBody("""{"phone":"$phone","code":"$code","challengeId":"${request.field("challengeId")}"}""")
        }
    }

    private suspend fun ApplicationTestBuilder.identities(accessToken: String): List<JsonObject> {
        val response = client.get("/auth/me/identities") { bearerAuth(accessToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.user() = getValue("user").jsonObject

    private fun JsonObject.providers() = getValue("linkedProviders").jsonArray.map { it.jsonPrimitive.content }

    private val emailCounter = AtomicInteger(0)

    private fun freshGoogle(name: String? = null): String {
        val n = emailCounter.incrementAndGet()
        return googleToken("g-sub-$n", "user$n@example.com", name)
    }

    @Test
    fun firstSignInIsFlaggedAsNew() = authTest {
        val phone = freshPhone()
        val first = signIn(phone)
        assertEquals("true", first.str("isNewUser"))
        assertEquals(listOf("WHATSAPP"), first.user().providers())
        val second = signIn(phone)
        assertEquals("false", second.str("isNewUser"))
        assertEquals(first.user().str("id"), second.user().str("id"))
    }

    @Test
    fun whatsAppAccountLinksGoogleThenEitherSignsIn() = authTest {
        val phone = freshPhone()
        val auth = signIn(phone)
        val token = freshGoogle(name = "Rohit")
        val link = linkGoogle(auth.str("accessToken"), token)
        assertEquals(HttpStatusCode.OK, link.status, link.bodyAsText())
        assertEquals("GOOGLE", link.json().str("provider"))

        val viaGoogle = googleSignIn(token).json()
        assertEquals(auth.user().str("id"), viaGoogle.user().str("id"), "same account, no duplicate")
        assertEquals("false", viaGoogle.str("isNewUser"))
        assertEquals(setOf("WHATSAPP", "GOOGLE"), viaGoogle.user().providers().toSet())
        assertEquals(phone, viaGoogle.user().str("phone"))
        assertTrue(viaGoogle.user().str("email").endsWith("@example.com"), "linked Google email becomes the contact email")
        assertEquals("Rohit", viaGoogle.user().str("displayName"), "generated 'User 1234' name replaced by the Google name")
    }

    @Test
    fun googleAccountLinksWhatsAppThenEitherSignsIn() = authTest {
        val google = googleSignIn(freshGoogle()).json()
        val phone = freshPhone()
        val link = linkWhatsApp(google.str("accessToken"), phone)
        assertEquals(HttpStatusCode.OK, link.status, link.bodyAsText())

        val viaPhone = signIn(phone)
        assertEquals(google.user().str("id"), viaPhone.user().str("id"))
        assertEquals("false", viaPhone.str("isNewUser"))
        assertEquals(phone, viaPhone.user().str("phone"))
    }

    @Test
    fun linkWithWrongCodeLinksNothing() = authTest {
        val google = googleSignIn(freshGoogle()).json()
        val response = linkWhatsApp(google.str("accessToken"), freshPhone(), wrongCode = true)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("invalid_otp", response.field("code"))
        assertEquals(listOf("GOOGLE"), identities(google.str("accessToken")).map { it.str("provider") })
    }

    @Test
    fun identityOwnedByAnotherAccountCannotBeLinked() = authTest {
        val phoneA = freshPhone()
        signIn(phoneA)
        val token = freshGoogle()
        val b = googleSignIn(token).json()

        val stolen = linkWhatsApp(b.str("accessToken"), phoneA)
        assertEquals(HttpStatusCode.Conflict, stolen.status)
        assertEquals("identity_already_linked", stolen.field("code"))

        assertEquals(HttpStatusCode.OK, linkGoogle(b.str("accessToken"), token).status, "re-linking your own identity is a no-op")
        val second = linkGoogle(b.str("accessToken"), freshGoogle())
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals("provider_already_linked", second.field("code"))
    }

    @Test
    fun unlinkRemovesTheIdentityButNeverTheLast() = authTest {
        val phone = freshPhone()
        val auth = signIn(phone)
        val access = auth.str("accessToken")
        val onlyOne = identities(access).single().str("id")
        val refused = client.delete("/auth/me/identities/$onlyOne") { bearerAuth(access) }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("last_identity", refused.field("code"))

        assertEquals(HttpStatusCode.OK, linkGoogle(access, freshGoogle()).status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/me/identities/$onlyOne") { bearerAuth(access) }.status)
        assertEquals(listOf("GOOGLE"), identities(access).map { it.str("provider") })
        assertTrue(me(access).json()["phone"] == null, "contact phone cleared with its identity")

        // The phone is free again: signing in with it makes a new account instead of re-attaching.
        val again = signIn(phone)
        assertEquals("true", again.str("isNewUser"))
        assertNotEquals(auth.user().str("id"), again.user().str("id"))
    }

    @Test
    fun cannotUnlinkSomeoneElsesIdentity() = authTest {
        val a = signIn(freshPhone())
        val b = signIn(freshPhone())
        val bIdentity = identities(b.str("accessToken")).single().str("id")
        assertEquals(HttpStatusCode.NotFound, client.delete("/auth/me/identities/$bIdentity") { bearerAuth(a.str("accessToken")) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/auth/me/identities/nope") { bearerAuth(a.str("accessToken")) }.status)
    }

    @Test
    fun secondGoogleAccountWithSameEmailIsAConflict() = authTest {
        assertEquals(HttpStatusCode.OK, googleSignIn(googleToken("dup-sub-1", "dup@example.com")).status)
        val other = googleSignIn(googleToken("dup-sub-2", "dup@example.com"))
        assertEquals(HttpStatusCode.Conflict, other.status)
        assertEquals("account_conflict", other.field("code"))
    }

    @Test
    fun identityRoutesNeedAuth() = authTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me/identities").status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/auth/me/identities/google", """{"idToken":"valid-google-token"}""").status)
    }
}
