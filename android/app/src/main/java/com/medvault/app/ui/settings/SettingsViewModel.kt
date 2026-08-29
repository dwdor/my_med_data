package com.medvault.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medvault.app.backup.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object Working : BackupUiState
    data object ExportDone : BackupUiState
    data object RestoreDone : BackupUiState
    data class Error(val message: String) : BackupUiState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun exportBackup(destination: Uri, password: String) {
        viewModelScope.launch {
            _state.value = BackupUiState.Working
            val result = backupManager.exportBackup(destination, password.toCharArray())
            _state.value = result.fold(
                onSuccess = { BackupUiState.ExportDone },
                onFailure = { BackupUiState.Error(it.message ?: "Export fehlgeschlagen") },
            )
        }
    }

    fun restoreBackup(source: Uri, password: String) {
        viewModelScope.launch {
            _state.value = BackupUiState.Working
            val result = backupManager.restoreBackup(source, password.toCharArray())
            _state.value = result.fold(
                onSuccess = { BackupUiState.RestoreDone },
                onFailure = { BackupUiState.Error(it.message ?: "Wiederherstellung fehlgeschlagen") },
            )
        }
    }

    fun resetState() {
        _state.value = BackupUiState.Idle
    }
}
