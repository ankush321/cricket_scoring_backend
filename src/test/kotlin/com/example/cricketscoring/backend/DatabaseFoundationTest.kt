package com.example.cricketscoring.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseFoundationTest {
    private lateinit var postgres: PostgreSQLContainer<*>

    @BeforeAll
    fun startDatabase() {
        val available = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        assumeTrue(available, "Docker is required to run the database test")
        postgres = PostgreSQLContainer("postgres:18-alpine")
        postgres.start()
    }

    @Test
    fun healthUsesTheMigratedDatabase() = testApplication {
        application {
            module(
                DatabaseConfig(
                    jdbcUrl = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                ),
            )
        }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"database\":\"up\""))
    }
}
