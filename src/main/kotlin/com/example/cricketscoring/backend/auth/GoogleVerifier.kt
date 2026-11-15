package com.example.cricketscoring.backend.auth

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory

data class GoogleProfile(
    val subject: String,
    val email: String,
    val emailVerified: Boolean,
    val name: String?,
    val picture: String?,
)

fun interface GoogleVerifier {
    fun verify(idToken: String): GoogleProfile

    companion object {
        fun fromConfig(config: AuthConfig): GoogleVerifier {
            val clientIds = config.googleClientIds
            if (clientIds.isEmpty()) {
                return GoogleVerifier { throw AuthException("google_not_configured", "Google Sign-In is not configured.", io.ktor.http.HttpStatusCode.ServiceUnavailable) }
            }
            val inner = GoogleIdTokenVerifier.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(clientIds)
                .build()
            return GoogleVerifier { token ->
                val parsed: GoogleIdToken = inner.verify(token)
                    ?: throw AuthException("invalid_google_token", "Google ID token is invalid or expired.", io.ktor.http.HttpStatusCode.Unauthorized)
                val payload = parsed.payload
                val email = payload.email
                    ?: throw AuthException("invalid_google_token", "Google account has no email.", io.ktor.http.HttpStatusCode.BadRequest)
                if (payload.emailVerified != true) {
                    throw AuthException("email_not_verified", "Google email is not verified.", io.ktor.http.HttpStatusCode.BadRequest)
                }
                GoogleProfile(
                    subject = payload.subject,
                    email = email.lowercase(),
                    emailVerified = true,
                    name = payload["name"] as? String,
                    picture = payload["picture"] as? String,
                )
            }
        }
    }
}