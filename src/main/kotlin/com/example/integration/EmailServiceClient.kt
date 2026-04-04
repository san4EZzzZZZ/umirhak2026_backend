package com.example.integration

import com.example.config.AppConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
private data class PasswordResetMailRequest(
    val toEmail: String,
    val resetLink: String,
    val accountRole: String,
    val ttlMinutes: Long
)

class EmailServiceClient(private val config: AppConfig) {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun sendPasswordResetMail(toEmail: String, resetLink: String, accountRole: String, ttlMinutes: Long) {
        val body = json.encodeToString(
            PasswordResetMailRequest(
                toEmail = toEmail,
                resetLink = resetLink,
                accountRole = accountRole,
                ttlMinutes = ttlMinutes
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.emailServiceUrl}/internal/mail/password-reset"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-Internal-Token", config.internalServiceToken)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Email service returned HTTP ${response.statusCode()}")
        }
    }
}
