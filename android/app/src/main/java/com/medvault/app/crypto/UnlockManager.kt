package com.medvault.app.crypto

import com.medvault.app.data.local.DatabaseHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class UnlockState { LOCKED, UNLOCKED }

/**
 * App-wide lock state. The actual [androidx.biometric.BiometricPrompt] UI lives in the Unlock
 * screen (it needs a `FragmentActivity`); once that prompt succeeds, the screen calls
 * [completeUnlock] here to do the crypto + database-open work described in SPEC.md §4.
 *
 * [lock] is called from `MainActivity.onStop()` so the app re-locks immediately on backgrounding
 * (no timeout, per the MVP decision in SPEC.md §4).
 */
@Singleton
class UnlockManager @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val databaseHolder: DatabaseHolder,
) {
    private val _state = MutableStateFlow(UnlockState.LOCKED)
    val state: StateFlow<UnlockState> = _state.asStateFlow()

    /**
     * Runs after the OS-level biometric/device-credential check has already succeeded. Generates
     * the DB passphrase on first run, otherwise unwraps the existing one, then opens the
     * database. The raw passphrase is handed to SQLCipher via [DatabaseHolder.open] and is not
     * explicitly wiped afterwards (see DatabaseHolder — SQLCipher may need the byte array for the
     * lazily-opened underlying connection; explicit zeroing here would risk corrupting that open
     * on some code paths and needs on-device verification before being added).
     */
    suspend fun completeUnlock(): Result<Unit> = runCatching {
        val passphrase = cryptoManager.unlockPassphrase() ?: cryptoManager.setUpNewPassphrase()
        databaseHolder.open(passphrase)
        _state.value = UnlockState.UNLOCKED
    }

    fun lock() {
        databaseHolder.close()
        _state.value = UnlockState.LOCKED
    }

    fun isFirstRun(): Boolean = !cryptoManager.isPassphraseConfigured()
}
