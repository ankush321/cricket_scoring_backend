package com.example.cricketscoring.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object DatabaseFactory {
    /** Migrates and connects Exposed. The caller owns the returned pool and must close it on shutdown. */
    fun connect(config: DatabaseConfig): HikariDataSource {
        val dataSource = hikari(config)
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        Database.connect(dataSource)
        return dataSource
    }

    fun ping(): Boolean = transaction {
        exec("SELECT 1") { rs -> rs.next() } ?: false
    }

    private fun hikari(config: DatabaseConfig): HikariDataSource {
        val pool = HikariConfig()
        pool.jdbcUrl = config.jdbcUrl
        pool.username = config.user
        pool.password = config.password
        pool.driverClassName = "org.postgresql.Driver"
        pool.maximumPoolSize = 5
        pool.poolName = "cricket-scoring"
        return HikariDataSource(pool)
    }
}
