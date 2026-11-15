package com.example.cricketscoring.backend

import com.example.cricketscoring.backend.auth.AppleTokenClient
import java.util.concurrent.CopyOnWriteArrayList

/** Records Apple token calls instead of making them. Exchange returns "apple-rt-<code>". */
class FakeAppleTokenClient : AppleTokenClient {
    override val enabled = true
    val exchanges = CopyOnWriteArrayList<Pair<String, String>>() // code to clientId
    val revocations = CopyOnWriteArrayList<Pair<String, String>>() // refresh token to clientId

    @Volatile var exchangeFails = false

    @Volatile var revokeFails = false

    override suspend fun exchangeCode(authorizationCode: String, clientId: String): String? {
        exchanges += authorizationCode to clientId
        return if (exchangeFails) null else "apple-rt-$authorizationCode"
    }

    override suspend fun revoke(refreshToken: String, clientId: String): Boolean {
        revocations += refreshToken to clientId
        return !revokeFails
    }
}
