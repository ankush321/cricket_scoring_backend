package com.example.cricketscoring.backend

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import kotlin.test.assertEquals

/** V5 moves sign-in data from `users` columns into `user_identities`. Check it on V4-shaped data. */
class MigrationBackfillTest {
    @Test
    fun v5BackfillsIdentitiesFromUsersColumns() {
        val available = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        assumeTrue(available, "Docker is required to run the migration test")
        PostgreSQLContainer("postgres:18-alpine").use { pg ->
            pg.start()
            fun flyway(target: String) = Flyway.configure()
                .dataSource(pg.jdbcUrl, pg.username, pg.password)
                .locations("classpath:db/migration")
                .target(target)
                .load()

            flyway("4").migrate()
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                c.createStatement().execute(
                    """
                    INSERT INTO users (id, email, password_hash, display_name, phone_e164, google_sub, created_at_epoch_ms, updated_at_epoch_ms) VALUES
                      ('00000000-0000-0000-0000-000000000001', 'g@example.com', NULL, 'Google Only', NULL, 'gsub-1', 1, 2),
                      ('00000000-0000-0000-0000-000000000002', NULL, NULL, 'User 3210', '+919876543210', NULL, 3, 4),
                      ('00000000-0000-0000-0000-000000000003', 'both@example.com', NULL, 'Both', '+919876500000', 'gsub-3', 5, 6)
                    """.trimIndent(),
                )
            }
            flyway("5").migrate() // exactly V5: later migrations add their own columns

            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                fun rows(sql: String): List<String> = c.createStatement().executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { rs.getString(it) ?: "null" }) }
                }
                assertEquals(
                    listOf(
                        "00000000-0000-0000-0000-000000000001|GOOGLE|gsub-1|g@example.com|t|null|1|2",
                        "00000000-0000-0000-0000-000000000002|WHATSAPP|+919876543210|null|f|+919876543210|3|4",
                        "00000000-0000-0000-0000-000000000003|GOOGLE|gsub-3|both@example.com|t|null|5|6",
                        "00000000-0000-0000-0000-000000000003|WHATSAPP|+919876500000|null|f|+919876500000|5|6",
                    ),
                    rows(
                        "SELECT user_id, provider, provider_subject, email, email_verified, phone_e164, created_at_epoch_ms, last_used_at_epoch_ms " +
                            "FROM user_identities ORDER BY user_id, provider",
                    ),
                )
                assertEquals(
                    listOf(
                        "00000000-0000-0000-0000-000000000001|g@example.com|null",
                        "00000000-0000-0000-0000-000000000002|null|+919876543210",
                        "00000000-0000-0000-0000-000000000003|both@example.com|+919876500000",
                    ),
                    rows("SELECT id, primary_email, primary_phone_e164 FROM users ORDER BY id"),
                )
                assertEquals(
                    listOf("created_at_epoch_ms", "display_name", "id", "primary_email", "primary_phone_e164", "updated_at_epoch_ms"),
                    rows("SELECT column_name FROM information_schema.columns WHERE table_name = 'users' ORDER BY column_name"),
                )
            }
        }
    }
}
