package com.example.cricketscoring.backend.auth

object PhoneNumber {
    private val e164 = Regex("^\\+[1-9]\\d{7,14}$")

    fun normalize(raw: String): String {
        val trimmed = raw.trim().replace(" ", "").replace("-", "")
        if (!e164.matches(trimmed)) {
            throw AuthException("validation_error", "Phone must be in E.164 format, e.g. +919876543210.", io.ktor.http.HttpStatusCode.BadRequest)
        }
        return trimmed
    }
}