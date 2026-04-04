buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.flywaydb:flyway-database-postgresql:11.11.2")
        classpath("org.postgresql:postgresql:42.7.7")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.flyway)
}

group = "com.example"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.jedis)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
    implementation(libs.commons.csv)
    implementation(libs.poi.ooxml)
    implementation(libs.uuid.creator)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

flyway {
    url = System.getenv("JDBC_URL") ?: "jdbc:postgresql://localhost:55432/diasoft"
    user = System.getenv("DB_USER") ?: "diasoft"
    password = System.getenv("DB_PASSWORD") ?: "diasoft"
    locations = arrayOf("classpath:db/migration")
}
