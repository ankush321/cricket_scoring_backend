package com.example.cricketscoring.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import com.example.cricketscoring.backend.auth.AccountRepository
import com.example.cricketscoring.backend.auth.AccountService
import com.example.cricketscoring.backend.auth.AppleTokenClient
import com.example.cricketscoring.backend.auth.AppleTokenRepository
import com.example.cricketscoring.backend.auth.AppleVerifier
import com.example.cricketscoring.backend.auth.AuthEventRecorder
import com.example.cricketscoring.backend.auth.OtpGuard
import com.example.cricketscoring.backend.auth.RateLimitRepository
import com.example.cricketscoring.backend.auth.WhatsAppWebhook
import com.example.cricketscoring.backend.auth.TokenCipher
import com.example.cricketscoring.backend.auth.AuthConfig
import com.example.cricketscoring.backend.auth.AuthException
import com.example.cricketscoring.backend.auth.AuthService
import com.example.cricketscoring.backend.auth.GoogleVerifier
import com.example.cricketscoring.backend.auth.IdentityRepository
import com.example.cricketscoring.backend.auth.IdentityService
import com.example.cricketscoring.backend.auth.JwtService
import com.example.cricketscoring.backend.auth.OtpChallengeRepository
import com.example.cricketscoring.backend.auth.ACCESS_AUTH
import com.example.cricketscoring.backend.auth.AccessPrincipal
import com.example.cricketscoring.backend.auth.SessionRepository
import com.example.cricketscoring.backend.auth.SessionService
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import java.util.UUID
import com.example.cricketscoring.backend.auth.UserRepository
import com.example.cricketscoring.backend.auth.WhatsAppOtpSender
import com.example.cricketscoring.backend.auth.authRoutes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

data class AuthDependencies(
    val config: AuthConfig,
    val googleVerifier: GoogleVerifier,
    val whatsAppOtpSender: WhatsAppOtpSender,
    val clock: () -> Long = System::currentTimeMillis,
    val appleVerifier: AppleVerifier = AppleVerifier.fromConfig(config, clock),
    val appleTokenClient: AppleTokenClient = AppleTokenClient.fromConfig(config),
) {
    companion object {
        fun fromEnv(): AuthDependencies {
            val authConfig = AuthConfig.fromEnv()
            return AuthDependencies(
                config = authConfig,
                googleVerifier = GoogleVerifier.fromConfig(authConfig),
                whatsAppOtpSender = WhatsAppOtpSender.fromConfig(authConfig),
                appleVerifier = AppleVerifier.fromConfig(authConfig),
            )
        }
    }
}

fun Application.module(
    config: DatabaseConfig = DatabaseConfig.fromEnv(),
    authDependencies: AuthDependencies = AuthDependencies.fromEnv(),
) {
    val dataSource = DatabaseFactory.connect(config)
    monitor.subscribe(ApplicationStopped) { dataSource.close() }
    val authConfig = authDependencies.config
    val users = UserRepository()
    val identityRepository = IdentityRepository()
    val sessionRepository = SessionRepository()
    val appleTokens = AppleTokenRepository()
    val jwtService = JwtService(authConfig, authDependencies.clock)
    val events = AuthEventRecorder(authConfig.otpPepper, authDependencies.clock)
    val otpRepository = OtpChallengeRepository()
    val sessionService = SessionService(authConfig, sessionRepository, jwtService, authDependencies.clock, events)
    val tokenCipher = authConfig.appleTokenEncryptionKey?.let(TokenCipher::fromBase64)
    val accountService = AccountService(
        users = users,
        identities = identityRepository,
        accounts = AccountRepository(),
        sessions = sessionRepository,
        appleTokens = appleTokens,
        appleTokenClient = authDependencies.appleTokenClient,
        tokenCipher = tokenCipher,
        clock = authDependencies.clock,
    )
    val authService = AuthService(
        config = authConfig,
        users = users,
        identities = IdentityService(users, identityRepository, authDependencies.clock),
        otps = otpRepository,
        sessions = sessionService,
        googleVerifier = authDependencies.googleVerifier,
        appleVerifier = authDependencies.appleVerifier,
        accounts = accountService,
        appleTokens = appleTokens,
        appleTokenClient = authDependencies.appleTokenClient,
        tokenCipher = tokenCipher,
        guard = OtpGuard(authConfig, RateLimitRepository(), events, authDependencies.clock),
        events = events,
        whatsAppOtpSender = authDependencies.whatsAppOtpSender,
        clock = authDependencies.clock,
    )
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CallLogging)
    install(Authentication) {
        jwt(ACCESS_AUTH) {
            verifier(jwtService.verifier())
            // A valid signature isn't enough: the session must still be live, so logout takes effect immediately.
            validate { credential ->
                val userId = credential.payload.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val sessionId = credential.payload.getClaim(JwtService.SESSION_CLAIM).asString()
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (userId == null || sessionId == null) return@validate null
                AccessPrincipal(userId, sessionId).takeIf { sessionService.isActive(it) }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing, invalid or expired access token."))
            }
        }
    }
    install(RequestValidation)
    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError("validation_error", cause.reasons.joinToString(". ")),
            )
        }
        exception<AuthException> { call, cause ->
            cause.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respond(cause.status, ApiError(cause.code, cause.message))
        }
        // Malformed or incomplete JSON bodies (Ktor wraps the decode error in BadRequestException).
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("validation_error", "Request body is missing or malformed."))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Something went wrong."))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiError("not_found", "That path is not on this server."))
        }
    }
    routing {
        get("/health") {
            val databaseUp = runCatching { DatabaseFactory.ping() }.getOrDefault(false)
            val status = if (databaseUp) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(
                status,
                HealthResponse(
                    status = if (databaseUp) "up" else "down",
                    database = if (databaseUp) "up" else "down",
                ),
            )
        }
        authRoutes(authService, sessionService, WhatsAppWebhook(authConfig, otpRepository, events))
    }
}

@Serializable
data class HealthResponse(val status: String, val database: String)

@Serializable
data class ApiError(val code: String, val message: String)
