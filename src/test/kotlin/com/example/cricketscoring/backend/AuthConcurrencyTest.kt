package com.example.cricketscoring.backend

import com.example.cricketscoring.backend.auth.AccountRepository
import com.example.cricketscoring.backend.auth.AccountService
import com.example.cricketscoring.backend.auth.AppleTokenClient
import com.example.cricketscoring.backend.auth.AppleTokenRepository
import com.example.cricketscoring.backend.auth.AppleVerifier
import com.example.cricketscoring.backend.auth.AuthEventRecorder
import com.example.cricketscoring.backend.auth.OtpGuard
import com.example.cricketscoring.backend.auth.RateLimitRepository
import com.example.cricketscoring.backend.auth.AuthConfig
import com.example.cricketscoring.backend.auth.AuthException
import com.example.cricketscoring.backend.auth.AuthService
import com.example.cricketscoring.backend.auth.GoogleVerifier
import com.example.cricketscoring.backend.auth.IdentityRepository
import com.example.cricketscoring.backend.auth.IdentityService
import com.example.cricketscoring.backend.auth.JwtService
import com.example.cricketscoring.backend.auth.OtpChallengeRepository
import com.example.cricketscoring.backend.auth.OtpDelivery
import com.example.cricketscoring.backend.auth.SessionRepository
import com.example.cricketscoring.backend.auth.SessionService
import com.example.cricketscoring.backend.auth.ClientInfo
import com.example.cricketscoring.backend.auth.UserRepository
import com.example.cricketscoring.backend.auth.WhatsAppOtpSender
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import com.example.cricketscoring.backend.auth.AccessPrincipal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Calls [AuthService] from real threads released together, against a real Postgres.
 * (Ktor's in-memory test engine runs blocking handlers one at a time, so HTTP-level tests can't catch races.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthConcurrencyTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var service: AuthService
    private lateinit var sessions: SessionService
    private val lastOtp = AtomicReference<String?>(null)

    @BeforeAll
    fun setUp() {
        val available = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        assumeTrue(available, "Docker is required to run the concurrency tests")
        postgres = PostgreSQLContainer("postgres:18-alpine")
        postgres.start()
        DatabaseFactory.connect(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password))
        val config = testAuthConfig()
        val sessionRepository = SessionRepository()
        sessions = SessionService(config, sessionRepository, JwtService(config))
        val users = UserRepository()
        val identityRepository = IdentityRepository()
        val appleTokens = AppleTokenRepository()
        val accounts = AccountService(
            users, identityRepository, AccountRepository(), sessionRepository, appleTokens, AppleTokenClient.Disabled, tokenCipher = null,
        )
        service = AuthService(
            config = config,
            users = users,
            identities = IdentityService(users, identityRepository),
            otps = OtpChallengeRepository(),
            sessions = sessions,
            googleVerifier = fakeGoogleVerifier,
            appleVerifier = AppleVerifier.fromConfig(config),
            accounts = accounts,
            appleTokens = appleTokens,
            appleTokenClient = AppleTokenClient.Disabled,
            tokenCipher = null,
            guard = OtpGuard(config, RateLimitRepository(), AuthEventRecorder(config.otpPepper)),
            events = AuthEventRecorder(config.otpPepper),
            whatsAppOtpSender = WhatsAppOtpSender { _, code ->
                lastOtp.set(code)
                OtpDelivery("test")
            },
        )
    }

    /** Runs [task] on [threads] threads that all start at the same instant; returns each outcome's error code or "ok". */
    private fun race(threads: Int, task: () -> Unit): List<String> {
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        try {
            val futures = (1..threads).map {
                pool.submit<String> {
                    ready.countDown()
                    go.await()
                    try {
                        task()
                        "ok"
                    } catch (e: AuthException) {
                        e.code
                    }
                }
            }
            ready.await()
            go.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun dbQuery(sql: String): String? = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
        c.createStatement().executeQuery(sql).use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    @Test
    fun parallelSendsForOnePhoneCreateOneChallenge() {
        val phone = "+919800000001"
        val outcomes = race(8) { runBlocking { service.requestWhatsAppOtp(phone) } }
        assertEquals(1, outcomes.count { it == "ok" }, "$outcomes")
        assertEquals(7, outcomes.count { it == "otp_cooldown" }, "losers get a clean cooldown, not a DB error: $outcomes")
        assertEquals("1", dbQuery("SELECT count(*) FROM otp_challenges WHERE phone_e164 = '$phone'"))
    }

    @Test
    fun parallelWrongGuessesCannotExceedTheAttemptCap() {
        val phone = "+919800000002"
        runBlocking { service.requestWhatsAppOtp(phone) }
        val wrong = if (lastOtp.get() == "000000") "111111" else "000000"
        val outcomes = race(12) { service.verifyWhatsAppOtp(phone, wrong) }
        assertEquals(1, outcomes.count { it == "otp_locked" }, "$outcomes")
        assertEquals(11, outcomes.count { it == "invalid_otp" }, "$outcomes")
        assertEquals("3", dbQuery("SELECT attempt_count FROM otp_challenges WHERE phone_e164 = '$phone'"))
        assertEquals("MAX_ATTEMPTS", dbQuery("SELECT consumed_reason FROM otp_challenges WHERE phone_e164 = '$phone'"))
    }

    @Test
    fun parallelCorrectGuessesSignInOnceAndCreateOneUser() {
        val phone = "+919800000003"
        runBlocking { service.requestWhatsAppOtp(phone) }
        val code = lastOtp.get()!!
        val outcomes = race(6) { service.verifyWhatsAppOtp(phone, code) }
        assertEquals(1, outcomes.count { it == "ok" }, "$outcomes")
        assertEquals("1", dbQuery("SELECT count(*) FROM users WHERE primary_phone_e164 = '$phone'"))
    }

    @Test
    fun parallelRefreshesOfOneTokenLeaveExactlyOneLiveToken() {
        val user = runBlocking { service.requestWhatsAppOtp("+919800000004") }.let {
            service.verifyWhatsAppOtp("+919800000004", lastOtp.get()!!, it.challengeId, ClientInfo(deviceName = "race"))
        }
        val outcomes = race(6) { sessions.refresh(user.refreshToken) }
        // All six arrive within the grace window, so all succeed; each one retires the previous child.
        assertEquals(List(6) { "ok" }, outcomes)
        val sessionFilter = "session_id = (SELECT id FROM sessions WHERE device_name = 'race')"
        assertEquals("1", dbQuery("SELECT count(*) FROM refresh_tokens WHERE $sessionFilter AND used_at_epoch_ms IS NULL"))
        assertEquals("7", dbQuery("SELECT count(*) FROM refresh_tokens WHERE $sessionFilter"))
        assertEquals<String?>(null, dbQuery("SELECT revoked_reason FROM sessions WHERE device_name = 'race'"))
    }

    @Test
    fun parallelFirstGoogleSignInsCreateOneAccount() {
        val token = googleToken("race-google-sub", "race@example.com")
        val outcomes = race(6) { service.signInWithGoogle(token) }
        assertEquals(List(6) { "ok" }, outcomes)
        assertEquals("1", dbQuery("SELECT count(*) FROM users WHERE primary_email = 'race@example.com'"))
        assertEquals("1", dbQuery("SELECT count(*) FROM user_identities WHERE provider_subject = 'race-google-sub'"))
    }

    @Test
    fun parallelUnlinksNeverRemoveTheLastIdentity() {
        val phone = "+919800000005"
        val auth = runBlocking { service.requestWhatsAppOtp(phone) }.let { service.verifyWhatsAppOtp(phone, lastOtp.get()!!, it.challengeId) }
        val principal = AccessPrincipal(UUID.fromString(auth.user.id), UUID.randomUUID())
        service.linkGoogle(principal, googleToken("unlink-race-sub", "unlink-race@example.com"))
        val ids = service.listIdentities(principal).map { it.id }
        assertEquals(2, ids.size)

        val pool = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        val outcomes = try {
            ids.map { id ->
                pool.submit<String> {
                    go.await()
                    try {
                        runBlocking { service.unlinkIdentity(principal, id) }
                        "ok"
                    } catch (e: AuthException) {
                        e.code
                    }
                }
            }.also { go.countDown() }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(listOf("last_identity", "ok"), outcomes.sorted())
        assertEquals("1", dbQuery("SELECT count(*) FROM user_identities WHERE user_id = '${auth.user.id}'"))
    }

    @Test
    fun linkRacingAccountDeletionNeverLeavesAnIdentityOnADeletedAccount() {
        repeat(5) { round ->
            val phone = "+91980001%04d".format(round)
            val auth = runBlocking { service.requestWhatsAppOtp(phone) }.let { service.verifyWhatsAppOtp(phone, lastOtp.get()!!, it.challengeId) }
            val principal = AccessPrincipal(UUID.fromString(auth.user.id), UUID.randomUUID())
            val token = googleToken("del-race-$round", "del-race-$round@example.com")
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val outcomes = try {
                listOf<() -> Unit>(
                    { runBlocking { service.deleteAccount(principal) } },
                    { service.linkGoogle(principal, token) },
                ).map { task ->
                    pool.submit<String> {
                        go.await()
                        try {
                            task()
                            "ok"
                        } catch (e: AuthException) {
                            e.code
                        }
                    }
                }.also { go.countDown() }.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            assertEquals("ok", outcomes[0], "deletion always succeeds")
            assertTrue(outcomes[1] in setOf("ok", "unauthorized"), "link either ran first or was refused: $outcomes")
            assertEquals("0", dbQuery("SELECT count(*) FROM user_identities WHERE user_id = '${auth.user.id}'"), "round $round")
            assertEquals("DELETED", dbQuery("SELECT status FROM users WHERE id = '${auth.user.id}'"))
        }
    }
}
