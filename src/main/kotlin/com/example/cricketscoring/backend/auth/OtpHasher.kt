package com.example.cricketscoring.backend.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object OtpHasher {
    private val random = SecureRandom()

    /** Uniformly random code of [length] digits, leading zeros allowed. */
    fun generate(length: Int): String {
        require(length in 4..9) { "OTP length must be 4..9" }
        var bound = 1
        repeat(length) { bound *= 10 }
        return random.nextInt(bound).toString().padStart(length, '0')
    }

    /** HMAC-SHA256(pepper, challengeId:code). Binding the challenge id stops a hash being reused for another challenge. */
    fun hash(challengeId: String, code: String, pepper: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$challengeId:$code".toByteArray())
    }

    fun matches(expected: ByteArray, actual: ByteArray): Boolean = MessageDigest.isEqual(expected, actual)
}
