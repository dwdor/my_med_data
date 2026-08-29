package com.medvault.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Android Keystore key that wraps MedVault's random SQLCipher database passphrase.
 *
 * Design (see SPEC.md §4): the wrap key is created with `setUserAuthenticationRequired(true)` +
 * a validity duration, so Android Keystore itself refuses to use it unless the user passed a
 * biometric/device-credential check within [AUTH_VALIDITY_SECONDS] — this is enforced below the
 * app, not just an in-app screen gate. The window is refreshed by [UnlockManager] every time the
 * app returns to the foreground.
 *
 * NOTE: this class was written without access to a real device/emulator (see SPEC.md) — the
 * Keystore user-authentication-binding behavior is notoriously easy to get subtly wrong across
 * OEMs/API levels. Treat this as a first draft that needs to be exercised on real hardware
 * (including "no biometrics enrolled, PIN only" and "biometrics removed after setup" cases)
 * before this app is trusted with real medical documents.
 */
@Singleton
class CryptoManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isPassphraseConfigured(): Boolean = prefs.contains(PREF_WRAPPED_PASSPHRASE)

    /**
     * First-run setup: generates a random 256-bit DB passphrase, wraps it with the Keystore key
     * and persists the wrapped blob. Must run while the auth-validity window is open, i.e.
     * immediately after [UnlockManager] reports a successful unlock.
     */
    fun setUpNewPassphrase(): ByteArray {
        val passphrase = ByteArray(DB_PASSPHRASE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        wrapAndStore(passphrase)
        return passphrase
    }

    /**
     * Wraps an already-existing passphrase instead of generating a new one — used by
     * [com.medvault.app.backup.BackupManager] after a restore, where the database file being
     * written back was encrypted on the *old* device with its own (non-exportable) Keystore key.
     * The backup carries the raw passphrase inside its own password-protected archive; this
     * re-wraps that same passphrase with the current device's Keystore key so the restored
     * database file can be opened normally afterwards.
     */
    fun installPassphrase(passphrase: ByteArray) {
        wrapAndStore(passphrase)
    }

    private fun wrapAndStore(passphrase: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateDbWrapKey())
        }
        val ciphertext = cipher.doFinal(passphrase)
        prefs.edit()
            .putString(PREF_WRAPPED_PASSPHRASE, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Unwraps the persisted DB passphrase. Returns `null` on first run (nothing set up yet).
     * Throws [android.security.keystore.UserNotAuthenticatedException] if the Keystore
     * auth-validity window has expired — callers should show the unlock prompt again and retry.
     */
    fun unlockPassphrase(): ByteArray? {
        val wrapped = prefs.getString(PREF_WRAPPED_PASSPHRASE, null) ?: return null
        val ivString = prefs.getString(PREF_IV, null) ?: return null
        val iv = Base64.decode(ivString, Base64.NO_WRAP)
        val ciphertext = Base64.decode(wrapped, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateDbWrapKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    /**
     * Deletes the wrap key and stored passphrase blob. Used right before a backup restore
     * replaces the whole encrypted store. The database file itself is deleted by
     * [com.medvault.app.backup.BackupManager], which owns that part of the sequence.
     */
    fun resetDbPassphrase() {
        if (keyStore.containsAlias(DB_WRAP_KEY_ALIAS)) {
            keyStore.deleteEntry(DB_WRAP_KEY_ALIAS)
        }
        prefs.edit().remove(PREF_WRAPPED_PASSPHRASE).remove(PREF_IV).apply()
    }

    private fun getOrCreateDbWrapKey(): SecretKey {
        (keyStore.getKey(DB_WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            DB_WRAP_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DB_WRAP_KEY_ALIAS = "medvault_db_wrap_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val DB_PASSPHRASE_LENGTH_BYTES = 32
        private const val PREFS_NAME = "medvault_secure_prefs"
        private const val PREF_WRAPPED_PASSPHRASE = "wrapped_db_passphrase"
        private const val PREF_IV = "wrapped_db_passphrase_iv"

        /**
         * Seconds a successful biometric/device-credential check keeps the Keystore keys usable.
         * Shared with the file [androidx.security.crypto.MasterKey] in
         * files/EncryptedFileStore.kt so both layers unlock together. Refreshed on every
         * foreground unlock (see UnlockManager) rather than relied on for long-lived sessions.
         */
        const val AUTH_VALIDITY_SECONDS = 3600
    }
}
