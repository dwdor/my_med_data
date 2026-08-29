package com.medvault.app.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for the backup archive (SPEC.md §6.6). Independent of the device
 * Keystore on purpose — a backup has to be restorable on a *different* device, where none of the
 * exporting device's Keystore keys exist. Output layout: `salt(16) || iv(12) || ciphertext+tag`.
 */
object BackupCrypto {
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt))
        }
        val ciphertext = cipher.doFinal(plaintext)
        return salt + cipher.iv + ciphertext
    }

    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        require(blob.size > SALT_LENGTH_BYTES + IV_LENGTH_BYTES) { "Backup-Datei ist beschädigt oder kein MedVault-Backup." }
        val salt = blob.copyOfRange(0, SALT_LENGTH_BYTES)
        val iv = blob.copyOfRange(SALT_LENGTH_BYTES, SALT_LENGTH_BYTES + IV_LENGTH_BYTES)
        val ciphertext = blob.copyOfRange(SALT_LENGTH_BYTES + IV_LENGTH_BYTES, blob.size)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
