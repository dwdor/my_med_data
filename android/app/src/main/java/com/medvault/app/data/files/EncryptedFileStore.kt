package com.medvault.app.data.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.medvault.app.crypto.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores original uploaded/scanned documents individually encrypted (AES-256-GCM) under the
 * app's private storage, never in shared/public storage (see SPEC.md §4).
 *
 * The [MasterKey] shares [CryptoManager.AUTH_VALIDITY_SECONDS] with the DB passphrase wrap key
 * so both are gated by the same biometric/device-credential check. As with CryptoManager, this
 * was written without a real device to test against — verify the `setUserAuthenticationRequired`
 * overload signature against the actual androidx.security.crypto version Android Studio resolves
 * before relying on it.
 */
@Singleton
class EncryptedFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val documentsDir: File by lazy {
        File(context.filesDir, "documents").apply { mkdirs() }
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(true, CryptoManager.AUTH_VALIDITY_SECONDS)
            .build()
    }

    /** Copies [sourceUri] into the encrypted store and returns the generated stored file name. */
    fun store(sourceUri: Uri, contentResolver: ContentResolver, suggestedExtension: String?): String {
        val storedFileName = newFileName(suggestedExtension)
        val target = File(documentsDir, storedFileName)
        val input = contentResolver.openInputStream(sourceUri)
            ?: error("Could not open input stream for $sourceUri")
        input.use { source ->
            buildEncryptedFile(target).openFileOutput().use { output -> source.copyTo(output) }
        }
        return storedFileName
    }

    /** Writes raw bytes (e.g. a freshly-scanned PDF) into the encrypted store. */
    fun storeBytes(bytes: ByteArray, suggestedExtension: String?): String {
        val storedFileName = newFileName(suggestedExtension)
        val target = File(documentsDir, storedFileName)
        buildEncryptedFile(target).openFileOutput().use { it.write(bytes) }
        return storedFileName
    }

    fun openInputStream(storedFileName: String): InputStream =
        buildEncryptedFile(File(documentsDir, storedFileName)).openFileInput()

    fun delete(storedFileName: String) {
        File(documentsDir, storedFileName).delete()
    }

    /** Decrypts [storedFileName] into a temp file under the FileProvider-exposed cache dir
     * (`res/xml/file_paths.xml`), for one-off sharing via `ACTION_SEND`. Caller is responsible
     * for cleaning up old entries in that directory (e.g. on app start). */
    fun createShareableCopy(storedFileName: String, displayFileName: String): File {
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val target = File(shareDir, displayFileName)
        openInputStream(storedFileName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    fun clearShareCache() {
        File(context.cacheDir, "share").deleteRecursively()
    }

    /**
     * Writes [plaintextBytes] back under the *exact* [storedFileName] it originally had, encrypted
     * with this (possibly new) device's file [MasterKey]. Used only by
     * [com.medvault.app.backup.BackupManager]: the restored database's `DocumentEntity` rows
     * reference this file name verbatim, so — unlike [store]/[storeBytes] — restore must not
     * invent a new random name.
     */
    fun restoreFile(storedFileName: String, plaintextBytes: ByteArray) {
        buildEncryptedFile(File(documentsDir, storedFileName)).openFileOutput().use { it.write(plaintextBytes) }
    }

    /** Reads back the plaintext bytes of a stored file, for [com.medvault.app.backup.BackupManager]
     * export (the backup archive itself is what carries the actual encryption, see BackupCrypto). */
    fun readAllBytes(storedFileName: String): ByteArray = openInputStream(storedFileName).use { it.readBytes() }

    fun listStoredFileNames(): List<String> = documentsDir.list()?.toList().orEmpty()

    /** Deletes every stored document file. Used by [com.medvault.app.backup.BackupManager] right
     * before writing a restored set back in, so no orphaned pre-restore files remain. */
    fun clearAllDocuments() {
        documentsDir.listFiles()?.forEach { it.delete() }
    }

    private fun buildEncryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build()

    private fun newFileName(suggestedExtension: String?): String {
        val base = UUID.randomUUID().toString()
        return if (suggestedExtension.isNullOrBlank()) base else "$base.$suggestedExtension"
    }

    companion object {
        private const val MASTER_KEY_ALIAS = "medvault_file_master_key"
    }
}
