package com.example.cricketscoring.backend.auth

import io.ktor.http.HttpStatusCode

class AuthException(
    val code: String,
    override val message: String,
    val status: HttpStatusCode,
    /** Sent as the `Retry-After` header when set. */
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message)
