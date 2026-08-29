package com.medvault.app.ui.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medvault.app.data.files.EncryptedFileStore
import com.medvault.app.data.local.entities.DocumentEntity
import com.medvault.app.data.local.entities.DocumentWithTags
import com.medvault.app.data.model.Category
import com.medvault.app.data.repository.DocumentRepository
import com.medvault.app.importing.MimeUtils
import com.medvault.app.ui.navigation.Destinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val fileStore: EncryptedFileStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val documentId: Long = checkNotNull(savedStateHandle[Destinations.ARG_DOCUMENT_ID])

    private val _document = MutableStateFlow<DocumentWithTags?>(null)
    val document: StateFlow<DocumentWithTags?> = _document.asStateFlow()

    private val _pdfPages = MutableStateFlow<List<Bitmap>>(emptyList())
    val pdfPages: StateFlow<List<Bitmap>> = _pdfPages.asStateFlow()

    private val _imageBitmap = MutableStateFlow<Bitmap?>(null)
    val imageBitmap: StateFlow<Bitmap?> = _imageBitmap.asStateFlow()

    private val _extractedText = MutableStateFlow("")
    val extractedText: StateFlow<String> = _extractedText.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    var title by mutableStateOf("")
    var category by mutableStateOf(Category.SONSTIGES)
    var doctor by mutableStateOf("")
    var dateText by mutableStateOf("")
    var notes by mutableStateOf("")
    var tagsText by mutableStateOf("")

    init {
        viewModelScope.launch {
            val doc = documentRepository.getDocument(documentId)
            _document.value = doc
            doc?.let {
                populateForm(it)
                loadContent(it.document)
            }
        }
    }

    private fun populateForm(doc: DocumentWithTags) {
        title = doc.document.title
        category = doc.document.category
        doctor = doc.document.doctorOrInstitution.orEmpty()
        dateText = doc.document.documentDate?.toString().orEmpty()
        notes = doc.document.notes
        tagsText = doc.tags.joinToString(", ") { it.name }
    }

    private suspend fun loadContent(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        when {
            doc.mimeType == MimeUtils.MIME_PDF -> _pdfPages.value = renderPdfPages(doc.storedFileName)
            doc.mimeType.startsWith("image/") -> _imageBitmap.value =
                fileStore.openInputStream(doc.storedFileName).use(BitmapFactory::decodeStream)
            doc.mimeType == MimeUtils.MIME_DOCX -> _extractedText.value = documentRepository.getExtractedText(documentId)
        }
    }

    /** Renders up to [MAX_PREVIEW_PAGES] pages to keep memory use bounded; the full original is
     * always available unmodified via "Teilen" regardless of this cap. */
    private fun renderPdfPages(storedFileName: String): List<Bitmap> {
        val tempFile = File.createTempFile("preview_", ".pdf")
        fileStore.openInputStream(storedFileName).use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        return try {
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    (0 until minOf(renderer.pageCount, MAX_PREVIEW_PAGES)).map { index ->
                        renderer.openPage(index).use { page ->
                            Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888).also { bitmap ->
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    fun save() {
        viewModelScope.launch {
            val current = _document.value?.document ?: return@launch
            val date = dateText.trim().takeIf(String::isNotEmpty)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val tags = tagsText.split(",").map(String::trim).filter(String::isNotEmpty)
            documentRepository.updateMetadata(
                current, title.ifBlank { "Dokument" }, category, doctor.ifBlank { null }, date, notes, tags,
            )
            _document.value = documentRepository.getDocument(documentId)
        }
    }

    fun delete() {
        viewModelScope.launch {
            _document.value?.document?.let { documentRepository.deleteDocument(it) }
            _deleted.value = true
        }
    }

    /** Decrypts a throwaway copy for "Teilen" (see EncryptedFileStore.createShareableCopy); the
     * Composable turns this into a `FileProvider` Uri + `ACTION_SEND` since that needs a Context. */
    suspend fun prepareShareFile(): File? = withContext(Dispatchers.IO) {
        val doc = _document.value?.document ?: return@withContext null
        val displayName = "${doc.title}.${MimeUtils.extensionForMimeType(doc.mimeType)}"
        fileStore.createShareableCopy(doc.storedFileName, displayName)
    }

    companion object {
        private const val MAX_PREVIEW_PAGES = 15
    }
}
