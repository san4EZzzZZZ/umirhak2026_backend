package com.example.model

import kotlinx.serialization.Serializable

@Serializable
data class StudentRegisterRequest(
    val email: String,
    val fullName: String,
    val password: String
)

@Serializable
data class HrRegisterRequest(
    val email: String,
    val fullName: String,
    val password: String
)

@Serializable
data class LoginRequest(
    val role: String,
    val login: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val role: String,
    val login: String,
    val fullName: String,
    val universityCode: String? = null
)

@Serializable
data class CreateUniversityRequest(
    val code: String,
    val name: String,
    val email: String,
    val contactFullName: String,
    val password: String
)

@Serializable
data class UniversityResponse(
    val code: String,
    val name: String,
    val email: String,
    val contactFullName: String
)

@Serializable
data class DiplomaCreateRequest(
    val fullName: String,
    val specialty: String,
    val diplomaCode: String,
    val graduationYear: Int
)

@Serializable
data class BulkUploadResponse(
    val inserted: Int,
    val updated: Int,
    val errors: List<String>
)

@Serializable
data class VerifyResponse(
    val valid: Boolean,
    val verdict: String,
    val reason: String,
    val universityCode: String? = null,
    val diplomaCodeMasked: String? = null,
    val checkedAt: String
)

@Serializable
data class StudentQrRequest(
    val universityCode: String,
    val diplomaCode: String,
    val ttlMinutes: Long
)

@Serializable
data class StudentQrResponse(
    val token: String,
    val verifyUrl: String,
    val expiresAt: String,
    val qrBase64Png: String
)

@Serializable
data class QrVerificationResponse(
    val valid: Boolean,
    val reason: String,
    val fullName: String? = null,
    val specialty: String? = null,
    val university: String? = null,
    val expiresAt: String? = null,
    val checkedAt: String
)
