package com.example.security

import com.example.config.AppConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoService(private val config: AppConfig) {
    private val random = SecureRandom()
    private val aesKeyBytes: ByteArray = Base64.getDecoder().decode(config.encryptionKeyBase64)

    init {
        require(aesKeyBytes.size == 32) { "app.security.encryptionKeyBase64 must be 32-byte key in base64" }
    }

    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = digest.digest("${config.hashSalt}:$value".toByteArray())
        return hashed.joinToString("") { "%02x".format(it) }
    }

    fun encrypt(value: String): String {
        val iv = ByteArray(12)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(aesKeyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))

        val encrypted = cipher.doFinal(value.toByteArray())
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decrypt(value: String): String {
        val payload = Base64.getDecoder().decode(value)
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = SecretKeySpec(aesKeyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

        return String(cipher.doFinal(ciphertext))
    }

    fun sign(recordHash: String, signingSecret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingSecret.toByteArray(), "HmacSHA256"))
        val bytes = mac.doFinal(recordHash.toByteArray())
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun generateToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

