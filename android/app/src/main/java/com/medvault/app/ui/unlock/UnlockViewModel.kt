package com.medvault.app.ui.unlock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medvault.app.crypto.UnlockManager
import com.medvault.app.crypto.UnlockState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface UnlockUiState {
    data object Idle : UnlockUiState
    data object Unlocking : UnlockUiState
    data class Error(val message: String) : UnlockUiState
}

/**
 * Thin wrapper around [UnlockManager]: the actual `BiometricPrompt` call has to happen in
 * [com.medvault.app.ui.unlock.UnlockScreen] because it needs a `FragmentActivity`, which a
 * ViewModel must never hold a reference to. This class only does the work that follows a
 * successful OS-level auth (see UnlockManager.completeUnlock / SPEC.md §4).
 */
@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val unlockManager: UnlockManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UnlockUiState>(UnlockUiState.Idle)
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    /** Exposed so [com.medvault.app.ui.navigation.MedVaultNavHost] can pop back to the unlock
     * screen whenever `MainActivity.onStop()` re-locks the app, regardless of which screen the
     * user was on. */
    val lockState: StateFlow<UnlockState> = unlockManager.state

    fun onDeviceAuthSucceeded(onUnlocked: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = UnlockUiState.Unlocking
            unlockManager.completeUnlock()
                .onSuccess { onUnlocked() }
                .onFailure { error ->
                    _uiState.value = UnlockUiState.Error(error.message ?: "Unbekannter Fehler")
                }
        }
    }

    fun onAuthError(message: String) {
        _uiState.value = UnlockUiState.Error(message)
    }
}
