package com.medvault.app.backup

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import com.medvault.app.crypto.CryptoManager
import com.medvault.app.data.files.EncryptedFileStore
import com.medvault.app.data.local.DatabaseHolder
import com.medvault.app.data.local.MedVaultDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports/restores the encrypted backup archive described in SPEC.md §6.6.
 *
 * Cross-device restore is the whole point of a backup, but Android Keystore keys are
 * hardware-bound and can never be exported — so this can't just copy the on-disk encrypted files
 * verbatim:
 * - The SQLCipher database file bytes ARE copied as-is (still SQLCipher-encrypted), but the raw
 *   passphrase that opens it is bundled *inside* the archive too, protected only by the backup's
 *   own password (see [BackupCrypto]). On restore, [CryptoManager.installPassphrase] re-wraps
 *   that exact passphrase with the *new* device's Keystore key, so the copied `.db` file opens
 *   normally afterwards.
 * - Document files are decrypted to plaintext at export time (`EncryptedFileStore.readAllBytes`)
 *   and re-encrypted with the new device's file `MasterKey` at restore time
 *   (`EncryptedFileStore.restoreFile`), keeping their original file names so the (byte-for-byte
 *   copied) database rows still point at the right files.
 *
 * KNOWN LIMITATION (see SPEC.md): the database file is read via a WAL checkpoint + plain file
 * copy while the app may still hold it open, not SQLite's dedicated Online Backup API — acceptable
 * for a manual, user-triggered export of a single-process personal app, but not a guaranteed
 * atomic snapshot under concurrent writes. Hardening candidate before relying on this in practice.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseHolder: DatabaseHolder,
    private val cryptoManager: CryptoManager,
    private val fileStore: EncryptedFileStore,
) {
    suspend fun exportBackup(destinationUri: Uri, password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            checkpointWal()

            val passphrase = cryptoManager.unlockPassphrase()
                ?: error("Keine Datenbank zum Sichern vorhanden.")
            val dbFile = context.getDatabasePath(MedVaultDatabase.FILE_NAME)
            check(dbFile.exists()) { "Datenbankdatei nicht gefunden." }

            val manifest = JSONObject()
                .put("formatVersion", BACKUP_FORMAT_VERSION)
                .put("dbPassphraseBase64", android.util.Base64.encodeToString(passphrase, android.util.Base64.NO_WRAP))
                .toString()

            val zipBytes = ByteArrayOutputStream().use { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                    zip.write(manifest.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry(ENTRY_DB))
                    dbFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    fileStore.listStoredFileNames().forEach { fileName ->
                        zip.putNextEntry(ZipEntry("$ENTRY_DOCUMENTS_DIR$fileName"))
                        zip.write(fileStore.readAllBytes(fileName))
                        zip.closeEntry()
                    }
                }
                buffer.toByteArray()
            }

            val encrypted = BackupCrypto.encrypt(zipBytes, password)
            context.contentResolver.openOutputStream(destinationUri)?.use { it.write(encrypted) }
                ?: error("Zieldatei konnte nicht geöffnet werden.")
        }
    }

    suspend fun restoreBackup(sourceUri: Uri, password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val encrypted = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: error("Backup-Datei konnte nicht gelesen werden.")
            val zipBytes = BackupCrypto.decrypt(encrypted, password)

            var dbPassphrase: ByteArray? = null
            val dbFile = context.getDatabasePath(MedVaultDatabase.FILE_NAME)

            databaseHolder.close()
            fileStore.clearAllDocuments()
            deleteDbFiles(dbFile)

            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when {
                        entry.name == ENTRY_MANIFEST -> {
                            val manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                            dbPassphrase = android.util.Base64.decode(
                                manifest.getString("dbPassphraseBase64"),
                                android.util.Base64.NO_WRAP,
                            )
                        }
                        entry.name == ENTRY_DB -> {
                            dbFile.parentFile?.mkdirs()
                            dbFile.outputStream().use { out -> zip.copyTo(out) }
                        }
                        entry.name.startsWith(ENTRY_DOCUMENTS_DIR) -> {
                            val storedFileName = entry.name.removePrefix(ENTRY_DOCUMENTS_DIR)
                            fileStore.restoreFile(storedFileName, zip.readBytes())
                        }
                    }
                    entry = zip.nextEntry
                }
            }

            val passphrase = dbPassphrase ?: error("Backup enthält keinen gültigen Datenbankschlüssel.")
            cryptoManager.installPassphrase(passphrase)
        }
    }

    private fun checkpointWal() {
        if (!databaseHolder.isOpen) return
        runCatching {
            databaseHolder.requireDatabase().openHelper.writableDatabase
                .query(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)"))
                .close()
        }
    }

    private fun deleteDbFiles(dbFile: File) {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
    }

    companion object {
        private const val BACKUP_FORMAT_VERSION = 1
        private const val ENTRY_MANIFEST = "manifest.json"
        private const val ENTRY_DB = "medvault.db"
        private const val ENTRY_DOCUMENTS_DIR = "documents/"
    }
}
