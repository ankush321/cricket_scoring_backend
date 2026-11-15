package com.example.cricketscoring.backend

import com.example.cricketscoring.backend.auth.AuthException
import com.example.cricketscoring.backend.auth.GoogleProfile
import com.example.cricketscoring.backend.auth.GoogleVerifier
import io.ktor.http.HttpStatusCode

/**
 * Offline stand-in for Google. Accepts `valid-google-token`, or `fake:sub=<sub>;email=<email>[;name=<name>][;verified=false]`
 * so tests can mint any Google identity they need.
 */
val fakeGoogleVerifier = GoogleVerifier { token ->
    when {
        token == "valid-google-token" -> GoogleProfile("google-sub-1", "player@example.com", true, "Player One", null)
        token.startsWith("fake:") -> {
            val fields = token.removePrefix("fake:").split(";").associate { it.substringBefore("=") to it.substringAfter("=") }
            GoogleProfile(
                subject = fields.getValue("sub"),
                email = fields.getValue("email").lowercase(),
                emailVerified = fields["verified"] != "false",
                name = fields["name"],
                picture = null,
            )
        }
        else -> throw AuthException("invalid_google_token", "bad token", HttpStatusCode.Unauthorized)
    }
}

fun googleToken(sub: String, email: String, name: String? = null) =
    "fake:sub=$sub;email=$email" + (name?.let { ";name=$it" } ?: "")
