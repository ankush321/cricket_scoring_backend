package com.example.cricketscoring.backend.auth

import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Fixed-window counters in `rate_limit_buckets`. */
class RateLimitRepository {
    /** Adds one to the bucket's current window and returns the new count, atomically. */
    fun increment(key: String, windowStartEpochMs: Long): Int = transaction {
        exec(
            """
            INSERT INTO rate_limit_buckets AS b (bucket_key, window_start_epoch_ms, count) VALUES (?, ?, 1)
            ON CONFLICT (bucket_key, window_start_epoch_ms) DO UPDATE SET count = b.count + 1
            RETURNING b.count
            """.trimIndent(),
            listOf(TextColumnType() to key, LongColumnType() to windowStartEpochMs),
            StatementType.SELECT,
        ) { rs -> rs.next(); rs.getInt(1) } ?: error("no count returned")
    }

    fun current(key: String, windowStartEpochMs: Long): Int = transaction {
        exec(
            "SELECT count FROM rate_limit_buckets WHERE bucket_key = ? AND window_start_epoch_ms = ?",
            listOf(TextColumnType() to key, LongColumnType() to windowStartEpochMs),
        ) { rs -> if (rs.next()) rs.getInt(1) else 0 } ?: 0
    }
}
