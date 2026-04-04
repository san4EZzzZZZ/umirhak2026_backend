package com.example.config

import io.ktor.server.config.ApplicationConfig

data class AppConfig(
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val redisUrl: String,
    val encryptionKeyBase64: String,
    val hashSalt: String,
    val maxTtlMinutes: Long,
    val rateLimitPerMinute: Int,
    val superAdminLogin: String,
    val superAdminPassword: String,
    val publicBaseUrl: String
) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig = AppConfig(
            jdbcUrl = config.property("app.db.jdbcUrl").getString(),
            dbUser = config.property("app.db.user").getString(),
            dbPassword = config.property("app.db.password").getString(),
            redisUrl = config.property("app.redis.url").getString(),
            encryptionKeyBase64 = config.property("app.security.encryptionKeyBase64").getString(),
            hashSalt = config.property("app.security.hashSalt").getString(),
            maxTtlMinutes = config.property("app.security.maxTtlMinutes").getString().toLong(),
            rateLimitPerMinute = config.property("app.security.rateLimitPerMinute").getString().toInt(),
            superAdminLogin = config.property("app.superAdmin.login").getString(),
            superAdminPassword = config.property("app.superAdmin.password").getString(),
            publicBaseUrl = config.property("app.publicBaseUrl").getString().trimEnd('/')
        )
    }
}
