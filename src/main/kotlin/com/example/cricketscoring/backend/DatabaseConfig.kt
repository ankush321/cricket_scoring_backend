package com.example.cricketscoring.backend

import java.net.URI

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
) {
    companion object {
        /**
         * Resolution order:
         * 1. `DATABASE_URL` (`jdbc:postgresql://...` or `postgres://user:pass@host:port/db?params`), as set by Fly.io.
         * 2. `DB_HOST` / `DB_PORT` / `DB_NAME` (the `.env` style used for local development).
         * 3. Local docker-compose defaults: `localhost:5433/cricket_scoring`.
         *
         * Credentials come from `DB_USER` / `DB_PASSWORD`, falling back to `DATABASE_USER` / `DATABASE_PASSWORD`,
         * then `cricket` / `cricket`. Credentials embedded in a `postgres://` URL win over all of them.
         */
        fun fromEnv(env: (String) -> String? = System::getenv): DatabaseConfig {
            fun read(name: String): String? = env(name)?.takeIf { it.isNotBlank() }

            val user = read("DB_USER") ?: read("DATABASE_USER") ?: "cricket"
            val password = read("DB_PASSWORD") ?: read("DATABASE_PASSWORD") ?: "cricket"
            val raw = read("DATABASE_URL")

            if (raw == null) {
                val host = read("DB_HOST") ?: "localhost"
                val port = read("DB_PORT") ?: "5433"
                val name = read("DB_NAME") ?: "cricket_scoring"
                return DatabaseConfig("jdbc:postgresql://$host:$port/$name", user, password)
            }
            if (raw.startsWith("jdbc:")) {
                return DatabaseConfig(raw, user, password)
            }
            val uri = URI(raw.replaceFirst(Regex("^postgres(ql)?://"), "postgresql://"))
            val info = uri.userInfo?.split(":", limit = 2).orEmpty()
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return DatabaseConfig(
                jdbcUrl = "jdbc:postgresql://${uri.host}:$port${uri.path}$query",
                user = info.getOrNull(0) ?: user,
                password = info.getOrNull(1) ?: password,
            )
        }
    }
}
