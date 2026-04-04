package com.example

import com.example.config.AppConfig
import com.example.db.DatabaseFactory
import com.example.routes.registerRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import org.slf4j.event.Level
import java.sql.SQLException

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val config = AppConfig.from(environment.config)
    val database = DatabaseFactory(config)
    database.migrate()
    database.seedDefaults()

    install(ForwardedHeaders)
    install(CallLogging) {
        level = Level.INFO
    }
    install(ContentNegotiation) {
        json()
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Bad request")))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(HttpStatusCode.Conflict, mapOf("error" to (cause.message ?: "Conflict")))
        }
        exception<SQLException> { call, cause ->
            this@module.log.error("Database error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Database error: ${cause.message}"))
        }
        exception<Throwable> { call, cause ->
            this@module.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    registerRoutes(config, database)

    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        database.close()
    }
}

