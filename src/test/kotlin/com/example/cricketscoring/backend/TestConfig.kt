package com.example.cricketscoring.backend

import com.example.cricketscoring.backend.auth.AuthConfig

/**
 * Base config for tests: fixed fake secrets, and per-IP / failed-verify limits lifted, because every
 * request in Ktor's test engine comes from the same address. AbuseAndRolloutTest sets real limits.
 */
fun testAuthConfig(): AuthConfig = AuthConfig.fromEnv { null }.copy(
    jwtSecret = "test-secret",
    otpPepper = "test-pepper",
    otpIpSendLimit = 1_000_000,
    otpIpVerifyLimit = 1_000_000,
    otpFailedVerifyLimit = 1_000_000,
    otpDailySendBudget = 0,
)
