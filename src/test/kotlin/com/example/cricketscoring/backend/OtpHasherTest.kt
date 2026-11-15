package com.example.cricketscoring.backend

import com.example.cricketscoring.backend.auth.AuthConfig
import com.example.cricketscoring.backend.auth.OtpHasher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtpHasherTest {
    @Test
    fun codesAreAlwaysSixDigitsAndVary() {
        val codes = List(2_000) { OtpHasher.generate(6) }
        assertTrue(codes.all { it.matches(Regex("^\\d{6}$")) })
        assertTrue(codes.toSet().size > 1_900)
        assertTrue(codes.any { it.startsWith("0") }, "leading zeros must be possible")
    }

    @Test
    fun hashIsBoundToChallengeAndPepper() {
        val base = OtpHasher.hash("challenge-a", "123456", "pepper")
        assertTrue(OtpHasher.matches(base, OtpHasher.hash("challenge-a", "123456", "pepper")))
        assertFalse(OtpHasher.matches(base, OtpHasher.hash("challenge-b", "123456", "pepper")))
        assertFalse(OtpHasher.matches(base, OtpHasher.hash("challenge-a", "123457", "pepper")))
        assertFalse(OtpHasher.matches(base, OtpHasher.hash("challenge-a", "123456", "other")))
    }

    @Test
    fun cloudModeRequiresOtpPepper() {
        val env = mapOf("WHATSAPP_OTP_MODE" to "cloud")
        assertThrows<IllegalStateException> { AuthConfig.fromEnv(env::get) }
        val withPepper = AuthConfig.fromEnv((env + ("OTP_PEPPER" to "x"))::get)
        assertEquals("x", withPepper.otpPepper)
        assertEquals(3, withPepper.otpMaxAttempts)
    }
}
