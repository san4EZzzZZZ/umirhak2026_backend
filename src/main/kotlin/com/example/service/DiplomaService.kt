package com.example.service

import com.example.config.AppConfig
import com.example.db.DatabaseFactory
import com.example.model.BulkUploadResponse
import com.example.model.BulkAddResultResponse
import com.example.model.DiplomaRevokePreviewResponse
import com.example.model.DiplomaCreateRequest
import com.example.model.QrVerificationResponse
import com.example.model.StudentQrResponse
import com.example.model.UniversityDiplomaRecordResponse
import com.example.model.UniversityRegistryDashboardResponse
import com.example.model.UniversityResponse
import com.example.model.VerifyResponse
import com.example.security.CryptoService
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

private data class ParsedDiplomaRow(
    val fullName: String,
    val graduationYear: Int,
    val specialty: String,
    val diplomaCode: String
)

private data class DiplomaLookupRow(
    val status: String
)

data class AuthProfile(
    val login: String,
    val fullName: String,
    val universityCode: String? = null
)

@Serializable
private data class QrPayload(
    val studentEmail: String,
    val fullName: String,
    val specialty: String,
    val university: String,
    val expiresAt: String
)

class DiplomaService(
    private val config: AppConfig,
    private val database: DatabaseFactory,
    private val crypto: CryptoService,
    private val redis: RedisService
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getUniversityRegistryDashboard(login: String): UniversityRegistryDashboardResponse {
        resolveUniversityCodeByLogin(login)
        return database.withConnection { conn ->
            val inRegistry = conn.prepareStatement(
                """
                select count(*) as cnt
                from diploma_registry
                where status = 'ACTIVE'
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt("cnt") else 0
                }
            }

            UniversityRegistryDashboardResponse(
                pendingSignature = 0,
                inRegistry = inRegistry
            )
        }
    }

    fun listUniversityDiplomasByLogin(login: String): List<UniversityDiplomaRecordResponse> {
        resolveUniversityCodeByLogin(login)
        return emptyList()
    }

    fun addUniversityDiplomaByLogin(login: String, request: DiplomaCreateRequest) {
        val universityCode = resolveUniversityCodeByLogin(login)
        verifyDiplomaSignature(request)
        upsertDiploma(
            universityCode = universityCode,
            fullName = request.fullName,
            specialty = request.specialty,
            diplomaCode = request.diplomaCode,
            graduationYear = request.graduationYear,
            privateKeyHash = request.privateKeyHash
        )
    }

    fun addUniversityDiplomasBulkByLogin(login: String, rows: List<DiplomaCreateRequest>): BulkAddResultResponse {
        val universityCode = resolveUniversityCodeByLogin(login)
        rows.forEach { row ->
            upsertDiploma(
                universityCode = universityCode,
                fullName = row.fullName,
                specialty = row.specialty,
                diplomaCode = row.diplomaCode,
                graduationYear = row.graduationYear
            )
        }
        return BulkAddResultResponse(added = rows.size)
    }

    fun revokeUniversityDiplomaByNumberForLogin(login: String, diplomaNumber: String): Boolean {
        val universityCode = resolveUniversityCodeByLogin(login)
        return revokeDiploma(universityCode, diplomaNumber)
    }

    fun previewUniversityDiplomaRevokeByLogin(login: String, diplomaNumber: String): DiplomaRevokePreviewResponse {
        val universityCode = resolveUniversityCodeByLogin(login)
        val lookupHash = lookupHash(universityCode, diplomaNumber)
        return database.withConnection { conn ->
            conn.prepareStatement(
                """
                select 1
                from diploma_registry
                where diploma_lookup_hash = ? and status = 'ACTIVE'
                limit 1
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, lookupHash)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) {
                        DiplomaRevokePreviewResponse(found = false)
                    } else {
                        DiplomaRevokePreviewResponse(found = true)
                    }
                }
            }
        }
    }

    fun registerStudent(email: String, fullName: String, password: String) {
        createSimpleUser(
            table = "students",
            email = email,
            fullName = fullName,
            password = password
        )
    }

    fun registerHr(email: String, fullName: String, password: String) {
        createSimpleUser(
            table = "hr_specialists",
            email = email,
            fullName = fullName,
            password = password
        )
    }

    fun createUniversity(code: String, name: String, email: String, contactFullName: String, password: String): UniversityResponse {
        val normalizedCode = code.trim().uppercase()
        val normalizedEmail = email.trim().lowercase()

        database.withConnection { conn ->
            conn.prepareStatement(
                "select code, email from universities where code = ? or email = ?"
            ).use { stmt ->
                stmt.setString(1, normalizedCode)
                stmt.setString(2, normalizedEmail)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val existingCode = rs.getString("code")
                        val existingEmail = rs.getString("email")
                        if (existingCode != normalizedCode && existingEmail == normalizedEmail) {
                            throw IllegalStateException("University with email '$normalizedEmail' already exists")
                        }
                    }
                }
            }
        }

        database.withConnection { conn ->
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
                stmt.setString(1, normalizedCode)
                stmt.setString(2, name.trim())
                stmt.setString(3, normalizedEmail)
                stmt.setString(4, contactFullName.trim())
                stmt.setString(5, crypto.hash(password))
                stmt.setObject(6, OffsetDateTime.now(ZoneOffset.UTC))
                stmt.executeUpdate()
            }
        }

        return UniversityResponse(
            code = normalizedCode,
            name = name.trim(),
            email = normalizedEmail,
            contactFullName = contactFullName.trim()
        )
    }

    fun authenticateStudent(email: String, password: String): Boolean = authenticateSimpleUser("students", email, password)

    fun authenticateStudentProfile(email: String, password: String): AuthProfile? =
        authenticateSimpleUserProfile("students", email, password)

    fun authenticateHr(email: String, password: String): Boolean = authenticateSimpleUser("hr_specialists", email, password)

    fun authenticateHrProfile(email: String, password: String): AuthProfile? =
        authenticateSimpleUserProfile("hr_specialists", email, password)

    fun authenticateUniversity(code: String, email: String, password: String): Boolean {
        return database.withConnection { conn ->
            conn.prepareStatement(
                "select active from universities where code = ? and email = ? and password_hash = ?"
            ).use { stmt ->
                stmt.setString(1, code.trim())
                stmt.setString(2, email.trim().lowercase())
                stmt.setString(3, crypto.hash(password))
                stmt.executeQuery().use { rs -> rs.next() && rs.getBoolean("active") }
            }
        }
    }

    fun authenticateUniversityProfile(login: String, password: String): AuthProfile? {
        return database.withConnection { conn ->
            conn.prepareStatement(
                """
                select code, email, contact_full_name
                from universities
                where (email = ? or code = ?) and password_hash = ? and active = true
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, login.trim().lowercase())
                stmt.setString(2, login.trim().uppercase())
                stmt.setString(3, crypto.hash(password))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    AuthProfile(
                        login = rs.getString("email"),
                        fullName = rs.getString("contact_full_name"),
                        universityCode = rs.getString("code")
                    )
                }
            }
        }
    }

    fun authenticatePlatformAdminProfile(login: String, password: String): AuthProfile? {
        return database.withConnection { conn ->
            conn.prepareStatement(
                """
                select login, full_name
                from platform_admins
                where login = ? and password_hash = ? and active = true
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, login.trim().lowercase())
                stmt.setString(2, crypto.hash(password))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    AuthProfile(
                        login = rs.getString("login"),
                        fullName = rs.getString("full_name")
                    )
                }
            }
        }
    }

    fun addOrUpdateDiploma(universityCode: String, request: DiplomaCreateRequest): String {
        upsertDiploma(
            universityCode = universityCode,
            fullName = request.fullName,
            specialty = request.specialty,
            diplomaCode = request.diplomaCode,
            graduationYear = request.graduationYear,
            privateKeyHash = request.privateKeyHash
        )
        return "Diploma saved"
    }

    fun uploadDiplomas(universityCode: String, fileName: String, bytes: ByteArray): BulkUploadResponse {
        val rows = parseRows(fileName, bytes)
        if (rows.isEmpty()) throw IllegalArgumentException("File contains no records")

        var inserted = 0
        var updated = 0
        val errors = mutableListOf<String>()

        rows.forEachIndexed { index, row ->
            try {
                val wasUpdate = upsertDiploma(universityCode, row.fullName, row.specialty, row.diplomaCode, row.graduationYear)
                if (wasUpdate) updated++ else inserted++
            } catch (ex: Exception) {
                errors.add("Row ${index + 1}: ${ex.message}")
            }
        }

        return BulkUploadResponse(inserted = inserted, updated = updated, errors = errors)
    }

    fun revokeDiploma(universityCode: String, diplomaCode: String): Boolean {
        val lookupHash = lookupHash(universityCode, diplomaCode)
        val updated = database.withConnection { conn ->
            conn.prepareStatement(
                """
                update diploma_registry
                set status = 'REVOKED'
                where diploma_lookup_hash = ? and status <> 'REVOKED'
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, lookupHash)
                stmt.executeUpdate()
            }
        }

        redis.delete(cacheKey(universityCode, lookupHash))
        return updated > 0
    }

    fun verifyDiplomaForHr(universityCode: String, diplomaCode: String): VerifyResponse {
        val lookupHash = lookupHash(universityCode, diplomaCode)
        val cacheKey = cacheKey(universityCode, lookupHash)

        redis.getJson(cacheKey)?.let { return json.decodeFromString(it) }

        val result = findDiplomaByLookupHash(lookupHash)
        val response = when {
            result == null -> VerifyResponse(
                valid = false,
                verdict = "RED",
                reason = "Diploma not found",
                checkedAt = OffsetDateTime.now(ZoneOffset.UTC).toString()
            )
            result.status != "ACTIVE" -> VerifyResponse(
                valid = false,
                verdict = "RED",
                reason = "Diploma revoked",
                checkedAt = OffsetDateTime.now(ZoneOffset.UTC).toString()
            )
            else -> VerifyResponse(
                valid = true,
                verdict = "GREEN",
                reason = "Diploma exists",
                checkedAt = OffsetDateTime.now(ZoneOffset.UTC).toString()
            )
        }

        redis.setJson(cacheKey, json.encodeToString(response), 300)
        return response
    }

    fun createStudentQr(studentEmail: String, universityCode: String, diplomaCode: String, ttlMinutes: Long): StudentQrResponse {
        if (ttlMinutes < 1 || ttlMinutes > config.maxTtlMinutes) {
            throw IllegalArgumentException("ttlMinutes must be between 1 and ${config.maxTtlMinutes}")
        }

        val lookupHash = lookupHash(universityCode, diplomaCode)
        val diploma = findDiplomaByLookupHash(lookupHash)
            ?: throw IllegalArgumentException("Diploma not found")

        if (diploma.status != "ACTIVE") {
            throw IllegalStateException("Diploma is revoked")
        }

        val token = crypto.generateToken()
        val expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(ttlMinutes)
        val payload = QrPayload(
            studentEmail = studentEmail.lowercase(),
            fullName = "Данные скрыты",
            specialty = "Данные скрыты",
            university = universityCode.trim().uppercase(),
            expiresAt = expiresAt.toString()
        )

        redis.setJson("qr:$token", json.encodeToString(payload), ttlMinutes * 60)

        val verifyUrl = "${config.publicBaseUrl}/api/v1/verify/qr/$token"
        return StudentQrResponse(
            token = token,
            verifyUrl = verifyUrl,
            expiresAt = expiresAt.toString(),
            qrBase64Png = generateQrBase64(verifyUrl)
        )
    }

    fun revokeStudentQr(studentEmail: String, token: String): Boolean {
        val key = "qr:$token"
        val payloadRaw = redis.getJson(key) ?: return false
        val payload = json.decodeFromString<QrPayload>(payloadRaw)
        if (payload.studentEmail != studentEmail.lowercase()) return false

        redis.delete(key)
        return true
    }

    fun verifyQr(token: String): QrVerificationResponse {
        val payloadRaw = redis.getJson("qr:$token")
        if (payloadRaw == null) {
            return QrVerificationResponse(
                valid = false,
                reason = "QR not found, expired, or revoked",
                checkedAt = OffsetDateTime.now(ZoneOffset.UTC).toString()
            )
        }

        val payload = json.decodeFromString<QrPayload>(payloadRaw)
        return QrVerificationResponse(
            valid = true,
            reason = "QR is valid",
            fullName = payload.fullName,
            specialty = payload.specialty,
            university = payload.university,
            expiresAt = payload.expiresAt,
            checkedAt = OffsetDateTime.now(ZoneOffset.UTC).toString()
        )
    }

    fun allowRequest(rateKey: String): Boolean = redis.rateLimit(rateKey, config.rateLimitPerMinute, 60)

    private fun createSimpleUser(table: String, email: String, fullName: String, password: String) {
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank()) {
            throw IllegalArgumentException("Email is required")
        }
        if (fullName.trim().isBlank()) {
            throw IllegalArgumentException("Full name is required")
        }
        if (password.isBlank()) {
            throw IllegalArgumentException("Password is required")
        }
        if (!isPasswordStrong(password)) {
            throw IllegalArgumentException("Пароль должен содержать минимум 8 символов, строчную, прописную букву, спецсимвол и только английские буквы")
        }

        if (emailExistsInSystem(normalizedEmail)) {
            throw IllegalStateException("Аккаунт с таким email уже существует")
        }

        database.withConnection { conn ->
            conn.prepareStatement(
                """
                insert into $table(email, full_name, password_hash, created_at)
                values (?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, normalizedEmail)
                stmt.setString(2, fullName.trim())
                stmt.setString(3, crypto.hash(password))
                stmt.setObject(4, OffsetDateTime.now(ZoneOffset.UTC))
                stmt.executeUpdate()
            }
        }
    }

    private fun emailExistsInSystem(normalizedEmail: String): Boolean {
        return database.withConnection { conn ->
            fun exists(query: String): Boolean {
                conn.prepareStatement(query).use { stmt ->
                    stmt.setString(1, normalizedEmail)
                    stmt.executeQuery().use { rs -> return rs.next() }
                }
            }

            exists("select 1 from students where email = ?") ||
                exists("select 1 from hr_specialists where email = ?") ||
                exists("select 1 from universities where email = ?") ||
                exists("select 1 from platform_admins where login = ?")
        }
    }

    private fun authenticateSimpleUser(table: String, email: String, password: String): Boolean {
        return database.withConnection { conn ->
            conn.prepareStatement("select 1 from $table where email = ? and password_hash = ?").use { stmt ->
                stmt.setString(1, email.trim().lowercase())
                stmt.setString(2, crypto.hash(password))
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    private fun isPasswordStrong(password: String): Boolean {
        if (password.length < 8) return false
        val hasLower = password.any { it in 'a'..'z' }
        val hasUpper = password.any { it in 'A'..'Z' }
        val hasSpecial = password.any { !it.isLetterOrDigit() }
        val hasNonLatinLetters = password.any { it.isLetter() && it !in 'a'..'z' && it !in 'A'..'Z' }
        return hasLower && hasUpper && hasSpecial && !hasNonLatinLetters
    }

    private fun authenticateSimpleUserProfile(table: String, email: String, password: String): AuthProfile? {
        return database.withConnection { conn ->
            conn.prepareStatement("select email, full_name from $table where email = ? and password_hash = ?").use { stmt ->
                stmt.setString(1, email.trim().lowercase())
                stmt.setString(2, crypto.hash(password))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    AuthProfile(
                        login = rs.getString("email"),
                        fullName = rs.getString("full_name")
                    )
                }
            }
        }
    }

    private fun upsertDiploma(
        universityCode: String,
        fullName: String,
        specialty: String,
        diplomaCode: String,
        graduationYear: Int,
        privateKeyHash: String? = null
    ): Boolean {
        if (graduationYear < 1950 || graduationYear > 2100) {
            throw IllegalArgumentException("Invalid graduation year: $graduationYear")
        }
        val normalizedPrivateKeyHash = privateKeyHash?.trim()?.lowercase()
        if (!normalizedPrivateKeyHash.isNullOrBlank() && !normalizedPrivateKeyHash.matches(Regex("^[a-f0-9]{64}$"))) {
            throw IllegalArgumentException("Invalid privateKeyHash format")
        }

        val payloadHash = payloadHash(
            fullName = fullName,
            universityCode = universityCode,
            specialty = specialty,
            diplomaCode = diplomaCode,
            graduationYear = graduationYear,
            privateKeyHash = normalizedPrivateKeyHash
        )
        val lookupHash = lookupHash(universityCode, diplomaCode)

        return database.withConnection { conn ->
            val exists = conn.prepareStatement("select 1 from diploma_registry where diploma_lookup_hash = ?")
                .use { stmt ->
                    stmt.setString(1, lookupHash)
                    stmt.executeQuery().use { rs -> rs.next() }
                }

            conn.prepareStatement(
                """
                insert into diploma_registry(
                    id,
                    diploma_payload_hash,
                    diploma_lookup_hash,
                    status,
                    created_at
                ) values (?, ?, ?, 'ACTIVE', ?)
                on conflict (diploma_lookup_hash) do update set
                    diploma_payload_hash = excluded.diploma_payload_hash,
                    status = 'ACTIVE'
                """.trimIndent()
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, payloadHash)
                stmt.setString(3, lookupHash)
                stmt.setObject(4, OffsetDateTime.now(ZoneOffset.UTC))
                stmt.executeUpdate()
            }

            exists
        }
    }

    private fun resolveUniversityCodeByLogin(login: String): String {
        val normalizedLogin = login.trim()
        if (normalizedLogin.isBlank()) {
            throw IllegalArgumentException("login is required")
        }

        return database.withConnection { conn ->
            conn.prepareStatement(
                """
                select code
                from universities
                where (email = ? or code = ?) and active = true
                limit 1
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, normalizedLogin.lowercase())
                stmt.setString(2, normalizedLogin.uppercase())
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("code") else throw IllegalArgumentException("University not found")
                }
            }
        }
    }

    private fun verifyDiplomaSignature(request: DiplomaCreateRequest) {
        val privateKeyHash = request.privateKeyHash?.trim()?.lowercase().orEmpty()
        val signatureBase64 = request.signatureBase64?.trim().orEmpty()
        val publicKeyPem = request.publicKeyPem?.trim().orEmpty()
        val signatureAlgorithm = request.signatureAlgorithm?.trim().orEmpty()

        if (privateKeyHash.isBlank() || signatureBase64.isBlank() || publicKeyPem.isBlank()) {
            throw IllegalArgumentException("Signature data is required for single diploma creation")
        }
        if (!privateKeyHash.matches(Regex("^[a-f0-9]{64}$"))) {
            throw IllegalArgumentException("Invalid privateKeyHash format")
        }
        if (signatureAlgorithm.isNotBlank() && signatureAlgorithm != "RSASSA-PKCS1-v1_5-SHA-256") {
            throw IllegalArgumentException("Unsupported signatureAlgorithm")
        }

        val canonical = buildDiplomaSigningPayload(
            fullName = request.fullName,
            specialty = request.specialty,
            diplomaCode = request.diplomaCode,
            graduationYear = request.graduationYear
        )
        val publicKey = parsePublicKeyFromPem(publicKeyPem)
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureBase64)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid signatureBase64 format")
        }

        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(canonical)
        if (!verifier.verify(signatureBytes)) {
            throw IllegalArgumentException("Invalid digital signature")
        }
    }

    private fun buildDiplomaSigningPayload(
        fullName: String,
        specialty: String,
        diplomaCode: String,
        graduationYear: Int
    ): ByteArray {
        val canonical = listOf(
            fullName.trim(),
            specialty.trim(),
            diplomaCode.trim().uppercase(),
            graduationYear.toString()
        ).joinToString("|")
        return canonical.toByteArray(StandardCharsets.UTF_8)
    }

    private fun parsePublicKeyFromPem(publicKeyPem: String): java.security.PublicKey {
        val normalized = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        if (normalized.isBlank()) {
            throw IllegalArgumentException("Invalid publicKeyPem")
        }
        val keyBytes = try {
            Base64.getDecoder().decode(normalized)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid publicKeyPem")
        }
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        return try {
            keyFactory.generatePublic(keySpec)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid publicKeyPem")
        }
    }

    private fun payloadHash(
        fullName: String,
        universityCode: String,
        specialty: String,
        diplomaCode: String,
        graduationYear: Int,
        privateKeyHash: String? = null
    ): String {
        val normalizedPrivateKeyHash = privateKeyHash?.trim()?.lowercase()
        val canonical = listOf(
            fullName.trim().lowercase(),
            universityCode.trim().uppercase(),
            specialty.trim().lowercase(),
            diplomaCode.trim().uppercase(),
            graduationYear.toString(),
            normalizedPrivateKeyHash ?: ""
        ).joinToString("|")
        return crypto.hash(canonical)
    }

    private fun lookupHash(universityCode: String, diplomaCode: String): String {
        val canonical = "$diplomaCode|$universityCode"
        return crypto.hash(canonical)
    }

    private fun findDiplomaByLookupHash(lookupHash: String): DiplomaLookupRow? {
        return database.withConnection { conn ->
            conn.prepareStatement(
                """
                select status
                from diploma_registry d
                where d.diploma_lookup_hash = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, lookupHash)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    DiplomaLookupRow(status = rs.getString("status"))
                }
            }
        }
    }

    private fun parseRows(fileName: String, bytes: ByteArray): List<ParsedDiplomaRow> {
        return when {
            fileName.endsWith(".csv", ignoreCase = true) -> parseCsv(bytes)
            fileName.endsWith(".xlsx", ignoreCase = true) -> parseXlsx(bytes)
            else -> throw IllegalArgumentException("Only CSV and XLSX files are supported")
        }
    }

    private fun parseCsv(bytes: ByteArray): List<ParsedDiplomaRow> {
        val records = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .get()
            .parse(bytes.inputStream().reader())

        return records.map { record ->
            val map = record.toMap()
            val fullName = readByAliases(map, listOf("фио", "full_name", "fullname", "name"))
            val year = readByAliases(map, listOf("год", "year", "graduation_year"))
            val specialty = readByAliases(map, listOf("специальность", "specialty", "program"))
            val diplomaCode = readByAliases(map, listOf("номер диплома", "номердиплома", "diploma_code", "diploma_number"))
            ParsedDiplomaRow(fullName, year.toInt(), specialty, diplomaCode)
        }
    }

    private fun parseXlsx(bytes: ByteArray): List<ParsedDiplomaRow> {
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val formatter = DataFormatter()
            val headerRow = sheet.getRow(sheet.firstRowNum) ?: return emptyList()
            val headerMap = mutableMapOf<Int, String>()

            for (cellIndex in headerRow.firstCellNum until headerRow.lastCellNum) {
                val cell = headerRow.getCell(cellIndex.toInt()) ?: continue
                headerMap[cellIndex.toInt()] = normalizeHeader(formatter.formatCellValue(cell))
            }

            val rows = mutableListOf<ParsedDiplomaRow>()
            for (rowIndex in (sheet.firstRowNum + 1)..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val values = mutableMapOf<String, String>()
                headerMap.forEach { (cellIndex, header) ->
                    values[header] = formatter.formatCellValue(row.getCell(cellIndex)).trim()
                }
                if (values.values.all { it.isBlank() }) continue

                val fullName = readByAliases(values, listOf("фио", "full_name", "fullname", "name"))
                val yearRaw = readByAliases(values, listOf("год", "year", "graduation_year"))
                val specialty = readByAliases(values, listOf("специальность", "specialty", "program"))
                val diplomaCode = readByAliases(values, listOf("номер диплома", "номердиплома", "diploma_code", "diploma_number"))
                rows.add(ParsedDiplomaRow(fullName, yearRaw.toInt(), specialty, diplomaCode))
            }

            return rows
        }
    }

    private fun readByAliases(values: Map<String, String>, aliases: List<String>): String {
        aliases.forEach { alias ->
            val normalized = normalizeHeader(alias)
            val value = values.entries.firstOrNull { normalizeHeader(it.key) == normalized }?.value
            if (!value.isNullOrBlank()) return value.trim()
        }
        throw IllegalArgumentException("Missing required column: ${aliases.first()}")
    }

    private fun normalizeHeader(value: String): String = value.lowercase().replace(" ", "").replace("_", "")

    private fun cacheKey(universityCode: String, lookupHash: String): String =
        "verify:${universityCode.uppercase()}:$lookupHash"

    private fun generateQrBase64(content: String): String {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val output = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(matrix, "PNG", output)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }
}
