package com.example.cricketscoring.backend.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

const val ACCESS_AUTH = "access"

/**
 * Auth routes, served under `/api/v1/auth` and, for app builds that predate it, `/auth`.
 * The WhatsApp delivery webhook is at `/webhooks/whatsapp`.
 */
fun Route.authRoutes(authService: AuthService, sessionService: SessionService, webhook: WhatsAppWebhook) {
    route("/api/v1/auth") { authRouteTree(authService, sessionService) }
    route("/auth") { authRouteTree(authService, sessionService) }

    route("/webhooks/whatsapp") {
        get {
            val q = call.request.queryParameters
            call.respondText(webhook.verifySubscription(q["hub.mode"], q["hub.verify_token"], q["hub.challenge"]), ContentType.Text.Plain)
        }
        post {
            webhook.handle(call.receiveText(), call.request.headers["X-Hub-Signature-256"])
            call.respond(HttpStatusCode.OK)
        }
    }
}

private fun Route.authRouteTree(authService: AuthService, sessionService: SessionService) {
    get("/providers") {
        call.respond(HttpStatusCode.OK, authService.providers(call.clientInfo(null)))
    }
    post("/google") {
        val body = call.receive<GoogleSignInRequest>()
        call.respond(HttpStatusCode.OK, authService.signInWithGoogle(body.idToken, call.clientInfo(body.deviceName)))
    }
    route("/apple") {
        post {
            val body = call.receive<AppleSignInRequest>()
            call.respond(HttpStatusCode.OK, authService.signInWithApple(body, call.clientInfo(body.deviceName)))
        }
        // Register this URL as the server-to-server notification endpoint in the Apple Developer portal.
        post("/notifications") {
            val body = call.receive<AppleNotificationRequest>()
            authService.handleAppleNotification(body.payload)
            call.respond(HttpStatusCode.OK)
        }
    }
    route("/whatsapp") {
        val request: suspend RoutingContext.() -> Unit = {
            call.markBeta()
            val body = call.receive<WhatsAppOtpRequest>()
            call.respond(HttpStatusCode.OK, authService.requestWhatsAppOtp(body.phone, call.clientInfo(null)))
        }
        val verify: suspend RoutingContext.() -> Unit = {
            call.markBeta()
            val body = call.receive<WhatsAppOtpVerifyRequest>()
            val response = authService.verifyWhatsAppOtp(body.phone, body.code, body.challengeId, call.clientInfo(body.deviceName))
            call.respond(HttpStatusCode.OK, response)
        }
        post("/otp/request", request)
        post("/send-otp", request)
        post("/otp/verify", verify)
        post("/verify-otp", verify)
    }
    post("/token/refresh") {
        val body = call.receive<RefreshRequest>()
        call.respond(HttpStatusCode.OK, authService.refresh(body.refreshToken))
    }

    authenticate(ACCESS_AUTH) {
        get("/me") {
            call.respond(HttpStatusCode.OK, authService.me(call.accessPrincipal()))
        }
        // Immediate and irreversible; the app should confirm with the user first.
        delete("/me") {
            authService.deleteAccount(call.accessPrincipal())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/logout") {
            sessionService.logout(call.accessPrincipal())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/logout-all") {
            sessionService.logoutAll(call.accessPrincipal())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/sessions") {
            call.respond(HttpStatusCode.OK, sessionService.list(call.accessPrincipal()))
        }
        route("/me/identities") {
            get {
                call.respond(HttpStatusCode.OK, authService.listIdentities(call.accessPrincipal()))
            }
            post("/google") {
                val body = call.receive<LinkGoogleRequest>()
                call.respond(HttpStatusCode.OK, authService.linkGoogle(call.accessPrincipal(), body.idToken))
            }
            post("/apple") {
                val body = call.receive<AppleLinkRequest>()
                call.respond(HttpStatusCode.OK, authService.linkApple(call.accessPrincipal(), body))
            }
            // Get the code from the WhatsApp OTP request endpoint first, as for sign-in.
            post("/whatsapp") {
                call.markBeta()
                val body = call.receive<LinkWhatsAppRequest>()
                val linked = authService.linkWhatsApp(call.accessPrincipal(), body.phone, body.code, body.challengeId, call.clientInfo(null))
                call.respond(HttpStatusCode.OK, linked)
            }
            delete("/{id}") {
                authService.unlinkIdentity(call.accessPrincipal(), call.parameters["id"].orEmpty())
                call.respond(HttpStatusCode.NoContent)
            }
        }
        delete("/sessions/{id}") {
            sessionService.revoke(call.accessPrincipal(), call.parameters["id"].orEmpty())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.markBeta() = response.header("X-Auth-Beta", "whatsapp-otp")

private fun ApplicationCall.accessPrincipal(): AccessPrincipal =
    principal<AccessPrincipal>() ?: error("authenticate(ACCESS_AUTH) must guard this route")

/** Fly's proxy sets Fly-Client-IP; fall back to the socket address locally. */
private fun ApplicationCall.clientInfo(deviceName: String?) = ClientInfo(
    deviceName = deviceName,
    userAgent = request.headers[HttpHeaders.UserAgent],
    ip = request.headers["Fly-Client-IP"] ?: request.origin.remoteHost,
    appVersion = request.headers["X-App-Version"],
)
