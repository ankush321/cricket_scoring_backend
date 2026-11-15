package com.example.cricketscoring.backend

import com.example.cricketscoring.backend.auth.AuthConfig
import com.example.cricketscoring.backend.auth.OtpDelivery
import com.example.cricketscoring.backend.auth.WhatsAppOtpSender
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase 5: per-IP / global / country / failed-verify limits, the WhatsApp rollout flag, auth_events, the delivery webhook and /api/v1. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbuseAndRolloutTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private val lastOtp = AtomicReference<String?>(null)
    private val lastMessageId = AtomicReference<String?>(null)
    private val counter = AtomicInteger(0)
    private val ipCounter = AtomicInteger(0)

    // Each test starts on a fresh 10-minute and 1-hour boundary, far apart, so fixed windows never leak between tests.
    private val now = AtomicLong(0)

    @BeforeAll
    fun startDatabase() {
        val available = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        assumeTrue(available, "Docker is required to run the abuse and rollout tests")
        postgres = PostgreSQLContainer("postgres:18-alpine")
        postgres.start()
    }

    private fun limitsTest(configure: AuthConfig.() -> AuthConfig = { this }, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        now.set(1_800_000_000_000L - 1_800_000_000_000L % 86_400_000 + counter.incrementAndGet() * 86_400_000L * 2)
        val base = testAuthConfig().copy(
            otpIpSendLimit = 10,
            otpIpVerifyLimit = 30,
            otpFailedVerifyLimit = 10,
            otpDailySendBudget = 0,
            whatsAppAppSecret = APP_SECRET,
            whatsAppWebhookVerifyToken = VERIFY_TOKEN,
        )
        val sender = WhatsAppOtpSender { _, code ->
            lastOtp.set(code)
            OtpDelivery("test", "wamid.${counter.incrementAndGet()}").also { lastMessageId.set(it.providerMessageId) }
        }
        val deps = AuthDependencies(base.configure(), fakeGoogleVerifier, sender, clock = now::get)
        application { module(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), deps) }
        block()
    }

    private fun freshPhone(prefix: String = "+91920") = prefix + "%07d".format(counter.incrementAndGet())

    private fun freshIp() = "198.51.100.${ipCounter.incrementAndGet() % 250}"

    private suspend fun ApplicationTestBuilder.request(
        phone: String,
        ip: String = "203.0.113.1",
        appVersion: String? = null,
        path: String = "/auth/whatsapp/otp/request",
    ): HttpResponse = client.post(path) {
        contentType(ContentType.Application.Json)
        header("Fly-Client-IP", ip)
        appVersion?.let { header("X-App-Version", it) }
        setBody("""{"phone":"$phone"}""")
    }

    private suspend fun ApplicationTestBuilder.verify(
        phone: String,
        code: String,
        ip: String = "203.0.113.1",
        appVersion: String? = null,
        path: String = "/auth/whatsapp/otp/verify",
    ): HttpResponse = client.post(path) {
        contentType(ContentType.Application.Json)
        header("Fly-Client-IP", ip)
        appVersion?.let { header("X-App-Version", it) }
        setBody("""{"phone":"$phone","code":"$code"}""")
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code() = json().getValue("code").jsonPrimitive.content

    private fun wrong(code: String) = if (code == "000000") "111111" else "000000"

    private fun advance(seconds: Long) = now.addAndGet(seconds * 1_000)

    private fun rows(query: String): List<String> = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
        c.createStatement().executeQuery(query).use { rs ->
            buildList { while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { rs.getString(it) ?: "null" }) }
        }
    }

    // ---------------------------------------------------------------- limits

    @Test
    fun perIpSendLimit() = limitsTest({ copy(otpIpSendLimit = 3) }) {
        val ip = freshIp()
        repeat(3) { assertEquals(HttpStatusCode.OK, request(freshPhone(), ip).status) }
        val blocked = request(freshPhone(), ip)
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertEquals("rate_limited", blocked.code())
        assertEquals("600", blocked.headers[HttpHeaders.RetryAfter], "window started exactly now")
        assertEquals(HttpStatusCode.OK, request(freshPhone(), freshIp()).status, "other IPs are unaffected")
        advance(600)
        assertEquals(HttpStatusCode.OK, request(freshPhone(), ip).status, "next window")
    }

    @Test
    fun perIpVerifyLimit() = limitsTest({ copy(otpIpVerifyLimit = 2) }) {
        val ip = freshIp()
        val phone = freshPhone()
        request(phone, ip)
        assertEquals(HttpStatusCode.Unauthorized, verify(phone, wrong(lastOtp.get()!!), ip).status)
        assertEquals(HttpStatusCode.Unauthorized, verify(phone, wrong(lastOtp.get()!!), ip).status)
        val third = verify(phone, lastOtp.get()!!, ip)
        assertEquals(HttpStatusCode.TooManyRequests, third.status, "even the right code is refused once the IP is over the limit")
        assertEquals("rate_limited", third.code())
    }

    @Test
    fun repeatedWrongCodesBlockNewCodesForThatPhone() = limitsTest({ copy(otpFailedVerifyLimit = 4) }) {
        val phone = freshPhone()
        request(phone)
        repeat(3) { verify(phone, wrong(lastOtp.get()!!)) } // 3 wrong: challenge locked
        advance(61)
        request(phone)
        verify(phone, wrong(lastOtp.get()!!)) // 4th wrong this hour
        advance(61)
        val blocked = request(phone)
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertEquals("otp_rate_limited", blocked.code())
        assertNotNull(blocked.headers[HttpHeaders.RetryAfter])
        assertEquals(HttpStatusCode.OK, request(freshPhone()).status, "other phones are unaffected")
        advance(3_600)
        assertEquals(HttpStatusCode.OK, request(phone).status, "next hour")
    }

    @Test
    fun globalDailyBudget() = limitsTest({ copy(otpDailySendBudget = 2) }) {
        val a = freshPhone()
        assertEquals(HttpStatusCode.OK, request(a).status)
        assertEquals(HttpStatusCode.TooManyRequests, request(a).status, "cooldown")
        assertEquals(HttpStatusCode.OK, request(freshPhone()).status, "the rejected request above didn't use budget")
        val over = request(freshPhone())
        assertEquals(HttpStatusCode.ServiceUnavailable, over.status)
        assertEquals("otp_unavailable", over.code())
        assertEquals("86400", over.headers[HttpHeaders.RetryAfter], "until the next UTC day")
        assertEquals(listOf("2"), rows("SELECT count(*) FROM otp_challenges WHERE created_at_epoch_ms >= ${now.get()}"), "no challenge row for the refused send")
    }

    @Test
    fun countryAllowlist() = limitsTest({ copy(whatsAppAllowedCountryCodes = listOf("91")) }) {
        assertEquals(HttpStatusCode.OK, request(freshPhone("+91921")).status)
        val uk = request(freshPhone("+44790"))
        assertEquals(HttpStatusCode.BadRequest, uk.status)
        assertEquals("unsupported_country", uk.code())
    }

    // ---------------------------------------------------------------- rollout flag and providers

    @Test
    fun rolloutOffDisablesTheFlow() = limitsTest({ copy(whatsAppRollout = AuthConfig.WhatsAppRollout.OFF) }) {
        val off = request(freshPhone())
        assertEquals(HttpStatusCode.Forbidden, off.status)
        assertEquals("feature_disabled", off.code())
        val providers = client.get("/auth/providers").json()
        assertEquals("false", providers.getValue("whatsapp").jsonObject.getValue("enabled").jsonPrimitive.content)
    }

    @Test
    fun allowlistRolloutGatesSendAndVerify() = limitsTest({
        copy(whatsAppRollout = AuthConfig.WhatsAppRollout.ALLOWLIST, whatsAppAllowlist = listOf("+91999"))
    }) {
        val insider = freshPhone("+91999")
        assertEquals(HttpStatusCode.OK, request(insider).status)
        assertEquals(HttpStatusCode.OK, verify(insider, lastOtp.get()!!).status)
        assertEquals(HttpStatusCode.Forbidden, request(freshPhone("+91922")).status)
        assertEquals(HttpStatusCode.Forbidden, verify(freshPhone("+91922"), "123456").status)
        val providers = client.get("/auth/providers").json()
        assertEquals("true", providers.getValue("whatsapp").jsonObject.getValue("enabled").jsonPrimitive.content, "offered; the phone decides")
    }

    @Test
    fun minimumAppVersion() = limitsTest({ copy(whatsAppMinAppVersion = "1.4.0") }) {
        assertEquals(HttpStatusCode.Forbidden, request(freshPhone()).status, "no X-App-Version header")
        assertEquals(HttpStatusCode.Forbidden, request(freshPhone(), appVersion = "1.3.9").status)
        assertEquals(HttpStatusCode.OK, request(freshPhone(), appVersion = "1.10.0").status, "numeric, not string, comparison")
        assertEquals(HttpStatusCode.OK, request(freshPhone(), appVersion = "1.4").status)
        fun enabled(json: JsonObject) = json.getValue("whatsapp").jsonObject.getValue("enabled").jsonPrimitive.content
        assertEquals("false", enabled(client.get("/auth/providers").json()))
        assertEquals("true", enabled(client.get("/auth/providers") { header("X-App-Version", "2.0.0") }.json()))
    }

    @Test
    fun providersReflectConfiguration() = limitsTest({ copy(googleClientIds = listOf("g"), appleClientIds = emptyList()) }) {
        val providers = client.get("/api/v1/auth/providers").json()
        assertEquals("true", providers.getValue("google").jsonPrimitive.content)
        assertEquals("false", providers.getValue("apple").jsonPrimitive.content)
        assertEquals("true", providers.getValue("whatsapp").jsonObject.getValue("beta").jsonPrimitive.content)
    }

    // ---------------------------------------------------------------- auth_events

    @Test
    fun eventsTrackTheBetaFunnelWithoutRawPhones() = limitsTest {
        val phone = freshPhone()
        val since = now.get()
        // Event ids are random, so order by time; the clock moves between steps to make that order strict.
        request(phone)
        advance(1)
        verify(phone, wrong(lastOtp.get()!!))
        advance(1)
        val auth = verify(phone, lastOtp.get()!!).json()
        assertEquals(
            listOf("OTP_SENT|OK|WHATSAPP|t", "OTP_VERIFY_FAILED|DENIED|WHATSAPP|t", "LOGIN_SUCCEEDED|OK|WHATSAPP|t"),
            rows("SELECT event_type, outcome, provider, is_beta_flow FROM auth_events WHERE created_at_epoch_ms >= $since ORDER BY created_at_epoch_ms"),
        )
        assertEquals(listOf("0"), rows("SELECT count(*) FROM auth_events WHERE CAST(auth_events AS text) LIKE '%${phone.removePrefix("+")}%'"), "no raw phone stored")
        assertEquals(listOf("0"), rows("SELECT count(*) FROM rate_limit_buckets WHERE bucket_key LIKE '%${phone.removePrefix("+")}%'"))
        assertEquals(listOf("t"), rows("SELECT is_beta_flow FROM sessions WHERE user_id = '${auth.getValue("user").jsonObject.getValue("id").jsonPrimitive.content}'"))

        val google = client.post("/auth/google") {
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"${googleToken("ev-${counter.incrementAndGet()}", "ev${counter.get()}@example.com")}"}""")
        }.json()
        val googleUser = google.getValue("user").jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(listOf("f"), rows("SELECT is_beta_flow FROM sessions WHERE user_id = '$googleUser'"))
        assertEquals(listOf("LOGIN_SUCCEEDED|GOOGLE|f"), rows("SELECT event_type, provider, is_beta_flow FROM auth_events WHERE user_id = '$googleUser'"))
    }

    @Test
    fun refreshTokenReuseIsRecorded() = limitsTest {
        val phone = freshPhone()
        request(phone)
        val auth = verify(phone, lastOtp.get()!!).json()
        val token = auth.getValue("refreshToken").jsonPrimitive.content
        val refresh = suspend { client.post("/auth/token/refresh") { contentType(ContentType.Application.Json); setBody("""{"refreshToken":"$token"}""") } }
        refresh()
        advance(11)
        assertEquals(HttpStatusCode.Unauthorized, refresh().status)
        val userId = auth.getValue("user").jsonObject.getValue("id").jsonPrimitive.content
        assertEquals(listOf("REFRESH_REUSE_DETECTED|DENIED"), rows("SELECT event_type, outcome FROM auth_events WHERE user_id = '$userId' AND event_type LIKE 'REFRESH%'"))
    }

    // ---------------------------------------------------------------- delivery webhook

    private fun sign(body: String, secret: String = APP_SECRET): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun statusBody(messageId: String, status: String) =
        """{"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{"statuses":[{"id":"$messageId","status":"$status","timestamp":"1","recipient_id":"91"}]}}]}]}"""

    private suspend fun ApplicationTestBuilder.postStatus(body: String, signature: String? = sign(body)) = client.post("/webhooks/whatsapp") {
        contentType(ContentType.Application.Json)
        signature?.let { header("X-Hub-Signature-256", it) }
        setBody(body)
    }

    @Test
    fun webhookHandshake() = limitsTest {
        val ok = client.get("/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=$VERIFY_TOKEN&hub.challenge=12345")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("12345", ok.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.get("/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=nope&hub.challenge=1").status)
    }

    @Test
    fun webhookIsUnavailableWithoutConfig() = limitsTest({ copy(whatsAppAppSecret = null, whatsAppWebhookVerifyToken = null) }) {
        assertEquals(HttpStatusCode.ServiceUnavailable, postStatus(statusBody("x", "delivered")).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=x&hub.challenge=1").status)
    }

    @Test
    fun webhookStatusesMoveForwardOnly() = limitsTest {
        val phone = freshPhone()
        request(phone)
        val wamid = lastMessageId.get()!!
        fun status() = rows("SELECT delivery_status FROM otp_challenges WHERE provider_message_id = '$wamid'").single()

        val since = now.get()
        assertEquals(HttpStatusCode.OK, postStatus(statusBody(wamid, "delivered")).status)
        assertEquals("DELIVERED", status())
        advance(1)
        postStatus(statusBody(wamid, "sent")) // arrives late
        assertEquals("DELIVERED", status())
        advance(1)
        postStatus(statusBody(wamid, "read"))
        assertEquals("READ", status())
        assertEquals(
            listOf("DELIVERED", "READ"),
            rows("SELECT metadata->>'status' FROM auth_events WHERE event_type = 'OTP_DELIVERY_STATUS' AND created_at_epoch_ms >= $since ORDER BY created_at_epoch_ms"),
        )

        assertEquals(HttpStatusCode.OK, postStatus(statusBody("wamid.unknown", "failed")).status, "not ours: acknowledged, ignored")
    }

    @Test
    fun webhookRejectsBadSignatures() = limitsTest {
        request(freshPhone())
        val wamid = lastMessageId.get()!!
        val body = statusBody(wamid, "failed")
        val forged = postStatus(body, sign(body, secret = "someone-else"))
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        assertEquals("invalid_signature", forged.code())
        assertEquals(HttpStatusCode.Unauthorized, postStatus(body, signature = null).status)
        assertEquals(listOf("null"), rows("SELECT delivery_status FROM otp_challenges WHERE provider_message_id = '$wamid'"))
    }

    // ---------------------------------------------------------------- /api/v1

    @Test
    fun apiV1PathsAndSpecNamesWork() = limitsTest {
        val phone = freshPhone()
        val sent = request(phone, path = "/api/v1/auth/whatsapp/send-otp")
        assertEquals(HttpStatusCode.OK, sent.status)
        assertEquals("whatsapp-otp", sent.headers["X-Auth-Beta"])
        assertEquals("true", sent.json().getValue("beta").jsonPrimitive.content)
        val auth = verify(phone, lastOtp.get()!!, path = "/api/v1/auth/whatsapp/verify-otp")
        assertEquals(HttpStatusCode.OK, auth.status)
        val access = auth.json().getValue("accessToken").jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/auth/me") { bearerAuth(access) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/auth/me") { bearerAuth(access) }.status, "legacy alias still served")
        assertTrue(client.get("/api/v1/auth/sessions") { bearerAuth(access) }.bodyAsText().contains("\"current\":true"))
        assertFalse(client.get("/api/v1/auth/nope").status.isSuccessLike())
    }

    private fun HttpStatusCode.isSuccessLike() = value in 200..299

    private companion object {
        const val APP_SECRET = "meta-app-secret"
        const val VERIFY_TOKEN = "verify-me"
    }
}
