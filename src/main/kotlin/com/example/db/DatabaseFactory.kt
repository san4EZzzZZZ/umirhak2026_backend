package com.example.db

import com.example.config.AppConfig
import com.example.security.CryptoService
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
        val crypto = CryptoService(appConfig)
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        withConnection { conn ->
            conn.prepareStatement(
                """
                insert into students(email, full_name, password_hash, created_at)
                values (?, ?, ?, ?)
                on conflict (email) do update set
                    full_name = excluded.full_name,
                    password_hash = excluded.password_hash
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, "student@demo.diasoft")
                stmt.setString(2, "Петрова Анна Сергеевна")
                stmt.setString(3, crypto.hash("Student2026"))
                stmt.setObject(4, now)
                stmt.executeUpdate()
            }

            conn.prepareStatement(
                """
                insert into hr_specialists(email, full_name, password_hash, created_at)
                values (?, ?, ?, ?)
                on conflict (email) do update set
                    full_name = excluded.full_name,
                    password_hash = excluded.password_hash
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, "hr@demo.diasoft")
                stmt.setString(2, "Козлов Олег Игоревич")
                stmt.setString(3, crypto.hash("HrDemo2026"))
                stmt.setObject(4, now)
                stmt.executeUpdate()
            }

            conn.prepareStatement(
                """
                insert into universities(code, name, email, contact_full_name, password_hash, created_at, active)
                values (?, ?, ?, ?, ?, ?, true)
                on conflict (code) do update set
                    name = excluded.name,
                    email = excluded.email,
                    contact_full_name = excluded.contact_full_name,
                    password_hash = excluded.password_hash,
                    active = true
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, "DEMO")
                stmt.setString(2, "Демо-университет")
                stmt.setString(3, "vuz@demo.diasoft")
                stmt.setString(4, "Семёнов Иван Викторович")
                stmt.setString(5, crypto.hash("VuzDemo2026"))
                stmt.setObject(6, now)
                stmt.executeUpdate()
            }

            conn.prepareStatement(
                """
                insert into platform_admins(login, full_name, password_hash, active, created_at)
                values (?, ?, ?, true, ?)
                on conflict (login) do update set
                    full_name = excluded.full_name,
                    password_hash = excluded.password_hash,
                    active = true
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, "admin@demo.diasoft")
                stmt.setString(2, "Демо Администратор")
                stmt.setString(3, crypto.hash("AdminDemo2026"))
                stmt.setObject(4, now)
                stmt.executeUpdate()
            }

            conn.prepareStatement(
                """
                insert into platform_admins(login, full_name, password_hash, active, created_at)
                values (?, ?, ?, true, ?)
                on conflict (login) do update set
                    full_name = excluded.full_name,
                    password_hash = excluded.password_hash,
                    active = true
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, "zuev.aleksandr.dstu@gmail.com")
                stmt.setString(2, "Зуев Александр")
                stmt.setString(3, crypto.hash("testtest123!"))
                stmt.setObject(4, now)
                stmt.executeUpdate()
            }
        }
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
