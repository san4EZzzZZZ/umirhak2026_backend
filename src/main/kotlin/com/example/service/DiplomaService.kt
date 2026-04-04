package com.example.service

import com.example.config.AppConfig
import com.example.db.DatabaseFactory
import com.example.model.BulkUploadResponse
import com.example.model.DiplomaCreateRequest
import com.example.model.QrVerificationResponse
import com.example.model.StudentQrResponse
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
    val universityCode: String,
    val universityName: String,
    val diplomaCodeEnc: String,
    val fullNameEnc: String,
    val specialtyEnc: String,
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

    fun registerStudent(email: String, fullName: String, password: String) {
        upsertSimpleUser(
            table = "students",
            email = email,
            fullName = fullName,
            password = password
        )
    }

    fun registerHr(email: String, fullName: String, password: String) {
        upsertSimpleUser(
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
        upsertDiploma(universityCode, request.fullName, request.specialty, request.diplomaCode, request.graduationYear)
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
                set status = 'REVOKED', revoked_at = ?
                where diploma_lookup_hash = ? and university_code = ? and status <> 'REVOKED'
                """.trimIndent()
            ).use { stmt ->
                stmt.setObject(1, OffsetDateTime.now(ZoneOffset.UTC))
                stmt.setString(2, lookupHash)
                stmt.setString(3, universityCode)
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
                universityCode = result.universityCode,
                diplomaCodeMasked = maskDiploma(crypto.decrypt(result.diplomaCodeEnc)),
                checkedAt = OffsetDateTime.now(ZoneOffset.UTC).toString()
            )
            else -> VerifyResponse(
                valid = true,
                verdict = "GREEN",
                reason = "Diploma exists",
                universityCode = result.universityCode,
                diplomaCodeMasked = maskDiploma(crypto.decrypt(result.diplomaCodeEnc)),
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
            fullName = crypto.decrypt(diploma.fullNameEnc),
            specialty = crypto.decrypt(diploma.specialtyEnc),
            university = diploma.universityName,
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

    private fun upsertSimpleUser(table: String, email: String, fullName: String, password: String) {
        database.withConnection { conn ->
            conn.prepareStatement(
                """
                insert into $table(email, full_name, password_hash, created_at)
                values (?, ?, ?, ?)
                on conflict (email) do update set
                    full_name = excluded.full_name,
                    password_hash = excluded.password_hash
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, email.trim().lowercase())
                stmt.setString(2, fullName.trim())
                stmt.setString(3, crypto.hash(password))
                stmt.setObject(4, OffsetDateTime.now(ZoneOffset.UTC))
                stmt.executeUpdate()
            }
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
        graduationYear: Int
    ): Boolean {
        if (graduationYear < 1950 || graduationYear > 2100) {
            throw IllegalArgumentException("Invalid graduation year: $graduationYear")
        }

        val payloadHash = payloadHash(fullName, universityCode, specialty, diplomaCode, graduationYear)
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
                    university_code,
                    full_name_enc,
                    specialty_enc,
                    graduation_year,
                    diploma_code_enc,
                    diploma_payload_hash,
                    diploma_lookup_hash,
                    status,
                    created_at,
                    revoked_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, null)
                on conflict (diploma_lookup_hash) do update set
                    university_code = excluded.university_code,
                    full_name_enc = excluded.full_name_enc,
                    specialty_enc = excluded.specialty_enc,
                    graduation_year = excluded.graduation_year,
                    diploma_code_enc = excluded.diploma_code_enc,
                    diploma_payload_hash = excluded.diploma_payload_hash,
                    status = 'ACTIVE',
                    revoked_at = null
                """.trimIndent()
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, universityCode)
                stmt.setString(3, crypto.encrypt(fullName.trim()))
                stmt.setString(4, crypto.encrypt(specialty.trim()))
                stmt.setInt(5, graduationYear)
                stmt.setString(6, crypto.encrypt(diplomaCode.trim()))
                stmt.setString(7, payloadHash)
                stmt.setString(8, lookupHash)
                stmt.setObject(9, OffsetDateTime.now(ZoneOffset.UTC))
                stmt.executeUpdate()
            }

            exists
        }
    }

    private fun payloadHash(fullName: String, universityCode: String, specialty: String, diplomaCode: String, graduationYear: Int): String {
        val canonical = listOf(
            fullName.trim().lowercase(),
            universityCode.trim().uppercase(),
            specialty.trim().lowercase(),
            diplomaCode.trim().uppercase(),
            graduationYear.toString()
        ).joinToString("|")
        return crypto.hash(canonical)
    }

    private fun lookupHash(universityCode: String, diplomaCode: String): String {
        val canonical = "${diplomaCode.trim().uppercase()}|${universityCode.trim().uppercase()}"
        return crypto.hash(canonical)
    }

    private fun findDiplomaByLookupHash(lookupHash: String): DiplomaLookupRow? {
        return database.withConnection { conn ->
            conn.prepareStatement(
                """
                select d.university_code, u.name as university_name, d.diploma_code_enc, d.full_name_enc, d.specialty_enc, d.status
                from diploma_registry d
                join universities u on u.code = d.university_code
                where d.diploma_lookup_hash = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, lookupHash)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    DiplomaLookupRow(
                        universityCode = rs.getString("university_code"),
                        universityName = rs.getString("university_name"),
                        diplomaCodeEnc = rs.getString("diploma_code_enc"),
                        fullNameEnc = rs.getString("full_name_enc"),
                        specialtyEnc = rs.getString("specialty_enc"),
                        status = rs.getString("status")
                    )
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

    private fun maskDiploma(code: String): String {
        if (code.length <= 4) return "****"
        return "*".repeat(code.length - 4) + code.takeLast(4)
    }

    private fun generateQrBase64(content: String): String {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val output = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(matrix, "PNG", output)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }
}
