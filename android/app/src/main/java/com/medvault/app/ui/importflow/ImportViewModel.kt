package com.medvault.app.ui.importflow

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medvault.app.data.model.Category
import com.medvault.app.importing.ImportManager
import com.medvault.app.ui.navigation.Destinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data object Done : SaveState
    data class Error(val message: String) : SaveState
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importManager: ImportManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val profileId: Long = checkNotNull(savedStateHandle[Destinations.ARG_PROFILE_ID])
    val mode: String = savedStateHandle[Destinations.ARG_MODE] ?: Destinations.ImportMode.FILE

    var title by mutableStateOf("")
    var category by mutableStateOf(Category.SONSTIGES)
    var doctor by mutableStateOf("")
    /** Plain ISO text field (yyyy-MM-dd) for the MVP rather than a full DatePicker dependency. */
    var dateText by mutableStateOf("")
    var notes by mutableStateOf("")
    var tagsText by mutableStateOf("")

    private val initialSharedUri: Uri? = (savedStateHandle.get<String>(Destinations.ARG_SHARED_URI))
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private val _pendingFileUri = MutableStateFlow(initialSharedUri)
    val pendingFileUri: StateFlow<Uri?> = _pendingFileUri.asStateFlow()

    private var pendingScanBytes: ByteArray? = null

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    fun onFileSelected(uri: Uri) {
        _pendingFileUri.value = uri
        pendingScanBytes = null
    }

    fun onScanResult(pdfBytes: ByteArray) {
        pendingScanBytes = pdfBytes
        _pendingFileUri.value = null
        if (title.isBlank()) title = "Scan ${LocalDate.now()}"
    }

    fun setDefaultTitle(name: String) {
        if (title.isBlank()) title = name.substringBeforeLast('.')
    }

    fun save() {
        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            val effectiveTitle = title.ifBlank { "Dokument" }
            val date = dateText.trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val tags = tagsText.split(",").map(String::trim).filter(String::isNotEmpty)

            val result = runCatching {
                val scanBytes = pendingScanBytes
                val fileUri = _pendingFileUri.value
                when {
                    scanBytes != null -> importManager.importScannedPdf(
                        scanBytes, profileId, effectiveTitle, category,
                        doctor.ifBlank { null }, date, notes, tags,
                    )
                    fileUri != null -> importManager.importFromUri(
                        fileUri, profileId, effectiveTitle, category,
                        doctor.ifBlank { null }, date, notes, tags,
                    )
                    else -> error("Keine Datei zum Importieren ausgewählt")
                }
            }
            _saveState.value = result.fold(
                onSuccess = { SaveState.Done },
                onFailure = { SaveState.Error(it.message ?: "Fehler beim Speichern") },
            )
        }
    }
}
