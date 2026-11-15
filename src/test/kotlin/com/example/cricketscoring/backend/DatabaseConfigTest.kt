package com.example.cricketscoring.backend

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DatabaseConfigTest {
    private fun config(vararg pairs: Pair<String, String>) = DatabaseConfig.fromEnv(mapOf(*pairs)::get)

    @Test
    fun defaultsPointAtLocalCompose() {
        assertEquals(
            DatabaseConfig("jdbc:postgresql://localhost:5433/cricket_scoring", "cricket", "cricket"),
            config(),
        )
    }

    @Test
    fun dbPartsBuildTheJdbcUrl() {
        assertEquals(
            DatabaseConfig("jdbc:postgresql://db.local:6000/scores", "alice", "pw"),
            config(
                "DB_HOST" to "db.local",
                "DB_PORT" to "6000",
                "DB_NAME" to "scores",
                "DB_USER" to "alice",
                "DB_PASSWORD" to "pw",
            ),
        )
    }

    @Test
    fun databaseUrlWinsOverDbParts() {
        val result = config("DATABASE_URL" to "jdbc:postgresql://other:5432/x", "DB_HOST" to "ignored")
        assertEquals("jdbc:postgresql://other:5432/x", result.jdbcUrl)
    }

    @Test
    fun postgresUrlKeepsCredentialsAndQuery() {
        assertEquals(
            DatabaseConfig("jdbc:postgresql://h:5432/db?sslmode=disable", "u", "p"),
            config("DATABASE_URL" to "postgres://u:p@h/db?sslmode=disable", "DB_USER" to "ignored"),
        )
    }

    @Test
    fun legacyCredentialNamesStillWork() {
        val result = config("DATABASE_USER" to "legacy", "DATABASE_PASSWORD" to "secret")
        assertEquals("legacy", result.user)
        assertEquals("secret", result.password)
    }
}
