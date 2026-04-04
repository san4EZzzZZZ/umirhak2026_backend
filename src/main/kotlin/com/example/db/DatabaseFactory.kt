package com.example.db

import com.example.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

class DatabaseFactory(private val appConfig: AppConfig) {
    private val dataSource: HikariDataSource

    init {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = appConfig.jdbcUrl
            username = appConfig.dbUser
            password = appConfig.dbPassword
            maximumPoolSize = 10
            minimumIdle = 2
            driverClassName = "org.postgresql.Driver"
        }
        dataSource = HikariDataSource(hikariConfig)
    }

    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    fun seedDefaults() {
        // Super-admin credentials are config based (env/app config), no DB seed needed.
    }

    fun <T> withConnection(block: (Connection) -> T): T {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            return block(connection)
        }
    }

    fun dataSource(): DataSource = dataSource

    fun close() {
        dataSource.close()
    }
}
