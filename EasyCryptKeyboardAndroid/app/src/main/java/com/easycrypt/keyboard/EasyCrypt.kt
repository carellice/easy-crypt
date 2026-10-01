package com.easycrypt.keyboard

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stesso algoritmo della web app (easy-crypt-fe/src/utils/crypto.js):
 * PBKDF2-HMAC-SHA256 (100000 iterazioni) -> AES-256-GCM,
 * output = base64(salt[16] + iv[12] + ciphertext + tag[16]).
 */
object EasyCrypt {
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 16
    private const val ITERATIONS = 100000

    private val random = SecureRandom()
    private val whitespace = Regex("\\s+")
    private val base64Shape = Regex("^[A-Za-z0-9+/]+={0,2}$")

    /** Chiave derivata con il suo salt: la derivazione è lenta, quindi si riusa per tutto un messaggio. */
    class SessionKey(val salt: ByteArray, val key: SecretKey)

    fun newSessionKey(password: String): SessionKey {
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        return SessionKey(salt, deriveKey(password, salt))
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /** Cifra con una chiave già derivata; l'IV è nuovo a ogni chiamata. */
    fun encrypt(plaintext: String, session: SessionKey): String {
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, session.key, GCMParameterSpec(TAG_LENGTH * 8, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(session.salt + iv + encrypted)
    }

    fun encrypt(plaintext: String, password: String): String =
        encrypt(plaintext, newSessionKey(password))

    /** Lancia un'eccezione se la password è errata o il testo non è valido. */
    fun decrypt(ciphertext: String, password: String): String {
        val data = Base64.getDecoder().decode(ciphertext.replace(whitespace, ""))
        require(data.size >= SALT_LENGTH + IV_LENGTH + TAG_LENGTH)
        val salt = data.copyOfRange(0, SALT_LENGTH)
        val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_LENGTH * 8, iv))
        val decrypted = cipher.doFinal(data, SALT_LENGTH + IV_LENGTH, data.size - SALT_LENGTH - IV_LENGTH)
        return String(decrypted, Charsets.UTF_8)
    }

    /** Controllo veloce (senza password) per capire se un testo ha la forma di un output EasyCrypt. */
    fun looksEncrypted(text: CharSequence?): Boolean {
        if (text == null) return false
        val s = text.toString().replace(whitespace, "")
        if (s.length < 60 || s.length % 4 != 0 || !base64Shape.matches(s)) return false
        return true
    }
}
