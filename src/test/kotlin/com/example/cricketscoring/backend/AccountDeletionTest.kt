package com.example.cricketscoring.backend

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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountDeletionTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private val now = AtomicLong(1_800_000_000_000L)
    private val lastOtp = AtomicReference<String?>(null)
    private val counter = AtomicInteger(0)

    @BeforeAll
    fun startDatabase() {
        val available = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        assumeTrue(available, "Docker is required to run the account deletion tests")
        postgres = PostgreSQLContainer("postgres:18-alpine")
        postgres.start()
    }

    private fun deletionTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val config = testAuthConfig().copy(googleClientIds = listOf("test"))
        val deps = AuthDependencies(
            config = config,
            googleVerifier = fakeGoogleVerifier,
            whatsAppOtpSender = WhatsAppOtpSender { _, code -> lastOtp.set(code); OtpDelivery("test") },
            clock = now::get,
        )
        application { module(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password), deps) }
        block()
    }

    private suspend fun HttpClient.postJson(path: String, body: String, accessToken: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        accessToken?.let { bearerAuth(it) }
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.signIn(phone: String, device: String = "Phone"): JsonObject {
        now.addAndGet(61_000)
        val request = client.postJson("/auth/whatsapp/otp/request", """{"phone":"$phone"}""").json()
        return client.post("/auth/whatsapp/otp/verify") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, "test-agent")
            setBody("""{"phone":"$phone","code":"${lastOtp.get()}","challengeId":"${request.str("challengeId")}","deviceName":"$device"}""")
        }.json()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.header(name: String, value: String) = headers.append(name, value)

    private fun freshPhone() = "+9193000%05d".format(counter.incrementAndGet())

    private fun sql(statement: String) = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
        it.createStatement().execute(statement)
    }

    private fun rows(query: String): List<String> = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
        c.createStatement().executeQuery(query).use { rs ->
            buildList { while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { rs.getString(it) ?: "null" }) }
        }
    }

    private fun seedCricketData(owner: String, prefix: String) {
        sql(
            """
            INSERT INTO players (id, owner_user_id, name, role, created_at_epoch_ms, updated_at_epoch_ms)
              VALUES ('$prefix-player', '$owner', 'Player', 'BATTER', 1, 1);
            INSERT INTO teams (id, owner_user_id, name, short_name, color, created_at_epoch_ms, updated_at_epoch_ms)
              VALUES ('$prefix-team', '$owner', 'Team', 'T', 0, 1, 1);
            """.trimIndent(),
        )
    }

    private fun membership(userId: String, teamId: String, role: String) = sql(
        "INSERT INTO memberships (id, user_id, resource_type, resource_id, role, created_at_epoch_ms) " +
            "VALUES ('${UUID.randomUUID()}', '$userId', 'TEAM', '$teamId', '$role', 1)",
    )

    @Test
    fun deletionEndsAccessAndScrubsTheAccount() = deletionTest {
        val n = counter.incrementAndGet()
        val phone = freshPhone()
        val a = signIn(phone, "Phone A")
        val b = signIn(phone, "Phone B")
        val userId = a.getValue("user").jsonObject.str("id")
        client.postJson("/auth/me/identities/google", """{"idToken":"${googleToken("del-$n", "del$n@example.com")}"}""", a.str("accessToken"))

        val other = signIn(freshPhone())
        val otherId = other.getValue("user").jsonObject.str("id")
        seedCricketData(userId, "mine-$n")
        seedCricketData(otherId, "theirs-$n")
        membership(otherId, "mine-$n-team", "EDITOR") // someone else's role on my team
        membership(userId, "theirs-$n-team", "VIEWER") // my role on their team
        membership(otherId, "theirs-$n-team", "OWNER") // unrelated to me

        val delete = client.delete("/auth/me") { bearerAuth(a.str("accessToken")) }
        assertEquals(HttpStatusCode.NoContent, delete.status)

        // Every device is out.
        for (session in listOf(a, b)) {
            assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/me") { bearerAuth(session.str("accessToken")) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.postJson("/auth/token/refresh", """{"refreshToken":"${session.str("refreshToken")}"}""").status)
        }
        assertEquals(listOf("DELETED|null|null|Deleted user|${now.get()}"),
            rows("SELECT status, primary_email, primary_phone_e164, display_name, deleted_at_epoch_ms FROM users WHERE id = '$userId'"))
        assertEquals(listOf("0"), rows("SELECT count(*) FROM user_identities WHERE user_id = '$userId'"))
        assertEquals(listOf("ACCOUNT_DELETED|null|null|null", "ACCOUNT_DELETED|null|null|null"),
            rows("SELECT revoked_reason, device_name, user_agent, ip_at_creation FROM sessions WHERE user_id = '$userId'"))
        assertEquals(listOf("0"), rows("SELECT count(*) FROM otp_challenges WHERE phone_e164 = '$phone'"))

        // My cricket data is soft-deleted (version bumped for sync); theirs is untouched.
        assertEquals(listOf("mine-$n-player|${now.get()}|2", "mine-$n-team|${now.get()}|2"),
            rows("SELECT id, deleted_at_epoch_ms, version FROM players WHERE owner_user_id = '$userId' UNION ALL " +
                "SELECT id, deleted_at_epoch_ms, version FROM teams WHERE owner_user_id = '$userId' ORDER BY 1"))
        assertEquals(listOf("theirs-$n-player|null|1", "theirs-$n-team|null|1"),
            rows("SELECT id, deleted_at_epoch_ms, version FROM players WHERE owner_user_id = '$otherId' UNION ALL " +
                "SELECT id, deleted_at_epoch_ms, version FROM teams WHERE owner_user_id = '$otherId' ORDER BY 1"))
        assertEquals(listOf("$otherId|theirs-$n-team|OWNER"),
            rows("SELECT user_id, resource_id, role FROM memberships WHERE resource_id IN ('mine-$n-team', 'theirs-$n-team')"))
        assertEquals(HttpStatusCode.OK, client.get("/auth/me") { bearerAuth(other.str("accessToken")) }.status)
    }

    @Test
    fun signingInAgainStartsAFreshAccount() = deletionTest {
        val n = counter.incrementAndGet()
        val phone = freshPhone()
        val first = signIn(phone)
        val google = googleToken("again-$n", "again$n@example.com")
        client.postJson("/auth/me/identities/google", """{"idToken":"$google"}""", first.str("accessToken"))
        client.delete("/auth/me") { bearerAuth(first.str("accessToken")) }

        val viaPhone = signIn(phone)
        assertEquals("true", viaPhone.str("isNewUser"))
        assertNotEquals(first.getValue("user").jsonObject.str("id"), viaPhone.getValue("user").jsonObject.str("id"))
        val viaGoogle = client.postJson("/auth/google", """{"idToken":"$google"}""").json()
        assertEquals("true", viaGoogle.str("isNewUser"), "the Google email was released too")
    }

    @Test
    fun deletionNeedsAValidToken() = deletionTest {
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/auth/me").status)
        val auth = signIn(freshPhone())
        assertEquals(HttpStatusCode.NoContent, client.delete("/auth/me") { bearerAuth(auth.str("accessToken")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/auth/me") { bearerAuth(auth.str("accessToken")) }.status, "token died with the account")
    }
}
