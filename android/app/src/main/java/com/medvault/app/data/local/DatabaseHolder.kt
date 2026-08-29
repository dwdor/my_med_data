package com.medvault.app.data.local

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SupportFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the single, lazily-opened [MedVaultDatabase] instance. There is no way to build the
 * Room database at Hilt-graph-construction time because its SQLCipher passphrase only exists
 * once [com.medvault.app.crypto.UnlockManager] has completed a biometric/device-credential
 * check (see SPEC.md §4) — so this is a plain holder rather than a `@Provides` function.
 */
@Singleton
class DatabaseHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var database: MedVaultDatabase? = null

    val isOpen: Boolean
        get() = database != null

    /** Opens (or returns the already-open) database using [passphrase]. Not thread-safe against
     * concurrent [open]/[close] calls; callers serialize through UnlockManager's state machine.
     *
     * TODO verify against the actual `net.zetetic:sqlcipher-android` version Android Studio
     * resolves: this was written from memory of the (older, differently-packaged)
     * `net.zetetic:android-database-sqlcipher` artifact without a way to check the current
     * library's docs/class layout in this environment (see SPEC.md). */
    fun open(passphrase: ByteArray): MedVaultDatabase {
        database?.let { return it }
        val factory = SupportFactory(passphrase)
        val db = Room.databaseBuilder(context, MedVaultDatabase::class.java, MedVaultDatabase.FILE_NAME)
            .openHelperFactory(factory)
            .build()
        database = db
        return db
    }

    fun requireDatabase(): MedVaultDatabase =
        database ?: error("Database accessed before unlock; see UnlockManager")

    fun close() {
        database?.close()
        database = null
    }
}
