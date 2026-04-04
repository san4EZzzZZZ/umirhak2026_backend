package com.example.model

import kotlinx.serialization.Serializable

@Serializable
data class StudentRegisterRequest(
    val email: String,
    val fullName: String,
    val password: String,
    val confirmPassword: String? = null
)

@Serializable
data class HrRegisterRequest(
    val email: String,
    val fullName: String,
    val password: String,
    val confirmPassword: String? = null
)

@Serializable
data class LoginRequest(
    val role: String,
    val login: String,
    val password: String
)

@Serializable
data class AdminLoginCodeRequest(
    val login: String,
    val password: String
)

@Serializable
data class AdminLoginCodeConfirmRequest(
    val login: String,
    val password: String,
    val code: String
)

@Serializable
data class LoginResponse(
    val role: String,
    val login: String,
    val fullName: String,
    val universityCode: String? = null
)

@Serializable
data class PasswordResetRequest(
    val role: String,
    val email: String
)

@Serializable
data class PasswordResetConfirmRequest(
    val token: String,
    val newPassword: String
)

@Serializable
data class StudentDiplomaCheckRequest(
    val universityCode: String,
    val diplomaNumber: String,
    val graduationYear: Int,
    val specialty: String
)

@Serializable
data class StudentDiplomaCheckResponse(
    val found: Boolean,
    val holderFullName: String,
    val lookupHash: String,
    val reason: String? = null
)

@Serializable
data class StudentVerificationLinkCreateRequest(
    val universityCode: String,
    val diplomaNumber: String,
    val specialty: String,
    val ttlHours: Int = 72
)

@Serializable
data class StudentVerificationLinkResponse(
    val token: String,
    val verificationUrl: String,
    val status: String,
    val issuedAt: String,
    val expiresAt: String
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
data class UniversityAdminRowResponse(
    val code: String,
    val name: String,
    val email: String,
    val contactFullName: String,
    val active: Boolean,
    val createdAt: String
)

@Serializable
data class DiplomaCreateRequest(
    val fullName: String,
    val specialty: String,
    val diplomaCode: String,
    val graduationYear: Int,
    val privateKeyHash: String? = null,
    val signatureBase64: String? = null,
    val publicKeyPem: String? = null,
    val signatureAlgorithm: String? = null
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
    val universityName: String? = null,
    val fullName: String? = null,
    val specialty: String? = null,
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

@Serializable
data class UniversityRegistryDashboardResponse(
    val pendingSignature: Int,
    val inRegistry: Int
)

@Serializable
data class UniversityDiplomaRecordResponse(
    val id: String,
    val fullName: String,
    val specialty: String,
    val graduationYear: Int,
    val diplomaNumber: String,
    val status: String,
    val createdAt: String
)

@Serializable
data class BulkAddResultResponse(
    val added: Int
)

@Serializable
data class DiplomaRevokePreviewResponse(
    val found: Boolean,
    val fullName: String? = null,
    val diplomaNumber: String? = null
)
