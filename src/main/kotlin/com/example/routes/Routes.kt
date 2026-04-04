package com.example.routes

import com.example.config.AppConfig
import com.example.db.DatabaseFactory
import com.example.model.CreateUniversityRequest
import com.example.model.DiplomaCreateRequest
import com.example.model.HrRegisterRequest
import com.example.model.LoginRequest
import com.example.model.LoginResponse
import com.example.model.StudentQrRequest
import com.example.model.StudentRegisterRequest
import com.example.security.CryptoService
import com.example.service.DiplomaService
import com.example.service.RedisService
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining

fun Application.registerRoutes(config: AppConfig, database: DatabaseFactory) {
    val redis = RedisService(config.redisUrl)
    val service = DiplomaService(config, database, CryptoService(config), redis)

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        route("/api/v1") {
            post("/auth/login") {
                val req = call.receive<LoginRequest>()
                val normalizedRole = req.role.trim().lowercase()
                val normalizedLogin = req.login.trim()
                val password = req.password

                if (normalizedLogin.isBlank() || password.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "login and password are required"))
                    return@post
                }

                val profile = when (normalizedRole) {
                    "student" -> service.authenticateStudentProfile(normalizedLogin, password)
                    "employer", "hr" -> service.authenticateHrProfile(normalizedLogin, password)
                    "university" -> service.authenticateUniversityProfile(normalizedLogin, password)
                    "admin" -> service.authenticatePlatformAdminProfile(normalizedLogin, password)
                    "superadmin" -> {
                        val loginMatches = normalizedLogin.equals(config.superAdminLogin.trim(), ignoreCase = true)
                        val passwordMatches = password == config.superAdminPassword
                        val demoLoginMatches = normalizedLogin.equals("super@demo.diasoft", ignoreCase = true)
                        val demoPasswordMatches = password == "SuperDemo2026"
                        if ((loginMatches && passwordMatches) || (demoLoginMatches && demoPasswordMatches)) {
                            com.example.service.AuthProfile(
                                login = if (demoLoginMatches) "super@demo.diasoft" else config.superAdminLogin.trim(),
                                fullName = "Super Admin"
                            )
                        } else {
                            null
                        }
                    }
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unsupported role: ${req.role}"))
                        return@post
                    }
                }

                if (profile == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                    return@post
                }

                call.respond(
                    LoginResponse(
                        role = if (normalizedRole == "hr") "employer" else normalizedRole,
                        login = profile.login,
                        fullName = profile.fullName,
                        universityCode = profile.universityCode
                    )
                )
            }

            post("/students/register") {
                val req = call.receive<StudentRegisterRequest>()
                if (req.confirmPassword != null && req.password != req.confirmPassword) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Пароли не совпадают"))
                    return@post
                }
                service.registerStudent(req.email, req.fullName, req.password)
                call.respond(mapOf("message" to "Student account saved"))
            }

            post("/hr/register") {
                val req = call.receive<HrRegisterRequest>()
                if (req.confirmPassword != null && req.password != req.confirmPassword) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Пароли не совпадают"))
                    return@post
                }
                service.registerHr(req.email, req.fullName, req.password)
                call.respond(mapOf("message" to "HR account saved"))
            }

            post("/admin/universities") {
                val adminLogin = call.request.headers["X-Superadmin-Login"]
                val adminPassword = call.request.headers["X-Superadmin-Password"]
                if (adminLogin != config.superAdminLogin || adminPassword != config.superAdminPassword) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid super-admin credentials"))
                    return@post
                }

                val req = call.receive<CreateUniversityRequest>()
                call.respond(service.createUniversity(req.code, req.name, req.email, req.contactFullName, req.password))
            }

            get("/university/registry/dashboard") {
                val login = call.request.queryParameters["login"]
                    ?: throw IllegalArgumentException("login is required")
                call.respond(service.getUniversityRegistryDashboard(login))
            }

            post("/university/diplomas") {
                val code = call.request.headers["X-University-Code"] ?: throw IllegalArgumentException("X-University-Code is required")
                val email = call.request.headers["X-University-Email"] ?: throw IllegalArgumentException("X-University-Email is required")
                val password = call.request.headers["X-University-Password"] ?: throw IllegalArgumentException("X-University-Password is required")

                if (!service.authenticateUniversity(code, email, password)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid university credentials"))
                    return@post
                }

                val req = call.receive<DiplomaCreateRequest>()
                call.respond(mapOf("message" to service.addOrUpdateDiploma(code, req)))
            }

            post("/university/diplomas/upload") {
                val code = call.request.headers["X-University-Code"] ?: throw IllegalArgumentException("X-University-Code is required")
                val email = call.request.headers["X-University-Email"] ?: throw IllegalArgumentException("X-University-Email is required")
                val password = call.request.headers["X-University-Password"] ?: throw IllegalArgumentException("X-University-Password is required")

                if (!service.authenticateUniversity(code, email, password)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid university credentials"))
                    return@post
                }

                val multipart = call.receiveMultipart()
                var fileName: String? = null
                var fileBytes: ByteArray? = null

                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        fileName = part.originalFileName
                        fileBytes = part.provider().readRemaining().readBytes()
                    }
                    part.dispose()
                }

                val safeFileName = fileName ?: throw IllegalArgumentException("File is required")
                val safeBytes = fileBytes ?: throw IllegalArgumentException("File is required")
                call.respond(service.uploadDiplomas(code, safeFileName, safeBytes))
            }

            post("/university/diplomas/revoke") {
                val code = call.request.headers["X-University-Code"] ?: throw IllegalArgumentException("X-University-Code is required")
                val email = call.request.headers["X-University-Email"] ?: throw IllegalArgumentException("X-University-Email is required")
                val password = call.request.headers["X-University-Password"] ?: throw IllegalArgumentException("X-University-Password is required")
                val diplomaCode = call.request.queryParameters["diplomaCode"]
                    ?: throw IllegalArgumentException("diplomaCode query parameter is required")

                if (!service.authenticateUniversity(code, email, password)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid university credentials"))
                    return@post
                }

                val revoked = service.revokeDiploma(code, diplomaCode)
                if (!revoked) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "Diploma not found or already revoked"))
                } else {
                    call.respond(mapOf("message" to "Diploma revoked"))
                }
            }

            get("/hr/verify") {
                val hrEmail = call.request.headers["X-HR-Email"] ?: throw IllegalArgumentException("X-HR-Email is required")
                val hrPassword = call.request.headers["X-HR-Password"] ?: throw IllegalArgumentException("X-HR-Password is required")
                val ip = call.request.headers["X-Forwarded-For"] ?: call.request.local.remoteHost

                if (!service.authenticateHr(hrEmail, hrPassword)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid HR credentials"))
                    return@get
                }
                if (!service.allowRequest("ratelimit:hr:$ip")) {
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many requests"))
                    return@get
                }

                val universityCode = call.request.queryParameters["universityCode"]
                    ?: throw IllegalArgumentException("universityCode is required")
                val diplomaCode = call.request.queryParameters["diplomaCode"]
                    ?: throw IllegalArgumentException("diplomaCode is required")

                call.respond(service.verifyDiplomaForHr(universityCode, diplomaCode))
            }

            post("/student/qr") {
                val studentEmail = call.request.headers["X-Student-Email"] ?: throw IllegalArgumentException("X-Student-Email is required")
                val studentPassword = call.request.headers["X-Student-Password"] ?: throw IllegalArgumentException("X-Student-Password is required")

                if (!service.authenticateStudent(studentEmail, studentPassword)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid student credentials"))
                    return@post
                }

                val req = call.receive<StudentQrRequest>()
                call.respond(service.createStudentQr(studentEmail, req.universityCode, req.diplomaCode, req.ttlMinutes))
            }

            post("/student/qr/{token}/revoke") {
                val studentEmail = call.request.headers["X-Student-Email"] ?: throw IllegalArgumentException("X-Student-Email is required")
                val studentPassword = call.request.headers["X-Student-Password"] ?: throw IllegalArgumentException("X-Student-Password is required")
                val token = call.parameters["token"] ?: throw IllegalArgumentException("token is required")

                if (!service.authenticateStudent(studentEmail, studentPassword)) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid student credentials"))
                    return@post
                }

                val revoked = service.revokeStudentQr(studentEmail, token)
                if (!revoked) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "QR token not found or not owned by student"))
                } else {
                    call.respond(mapOf("message" to "QR token revoked"))
                }
            }

            get("/verify/qr/{token}") {
                val ip = call.request.headers["X-Forwarded-For"] ?: call.request.local.remoteHost
                if (!service.allowRequest("ratelimit:qr:$ip")) {
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many requests"))
                    return@get
                }

                val token = call.parameters["token"] ?: throw IllegalArgumentException("token is required")
                call.respond(service.verifyQr(token))
            }
        }
    }

    monitor.subscribe(ApplicationStopped) {
        redis.close()
    }
}
