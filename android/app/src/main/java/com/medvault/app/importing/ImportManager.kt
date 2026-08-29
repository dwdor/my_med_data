package com.medvault.app.importing

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.medvault.app.data.files.EncryptedFileStore
import com.medvault.app.data.model.Category
import com.medvault.app.data.repository.DocumentRepository
import com.medvault.app.ocr.TextExtractionWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point all three import paths funnel through (share sheet, manual file picker,
 * camera scan — see SPEC.md §6.1): copy the source into the encrypted file store, write the
 * metadata row, then kick off background OCR/text extraction.
 */
@Singleton
class ImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileStore: EncryptedFileStore,
    private val documentRepository: DocumentRepository,
) {
    private val workManager by lazy { WorkManager.getInstance(context) }

    suspend fun importFromUri(
        uri: Uri,
        profileId: Long,
        title: String,
        category: Category,
        doctorOrInstitution: String?,
        documentDate: LocalDate?,
        notes: String,
        tags: List<String>,
    ): Long {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val storedFileName = fileStore.store(uri, context.contentResolver, MimeUtils.extensionForMimeType(mimeType))
        return finishImport(profileId, title, category, doctorOrInstitution, documentDate, notes, tags, storedFileName, mimeType)
    }

    /** Used for the camera-scan path: ML Kit's Document Scanner already produced a finished PDF
     * (see importing/DocumentScannerConfig.kt), so we only need to persist its bytes. */
    suspend fun importScannedPdf(
        pdfBytes: ByteArray,
        profileId: Long,
        title: String,
        category: Category,
        doctorOrInstitution: String?,
        documentDate: LocalDate?,
        notes: String,
        tags: List<String>,
    ): Long {
        val storedFileName = fileStore.storeBytes(pdfBytes, "pdf")
        return finishImport(profileId, title, category, doctorOrInstitution, documentDate, notes, tags, storedFileName, MimeUtils.MIME_PDF)
    }

    private suspend fun finishImport(
        profileId: Long,
        title: String,
        category: Category,
        doctorOrInstitution: String?,
        documentDate: LocalDate?,
        notes: String,
        tags: List<String>,
        storedFileName: String,
        mimeType: String,
    ): Long {
        val documentId = documentRepository.insertDocument(
            profileId = profileId,
            title = title,
            category = category,
            doctorOrInstitution = doctorOrInstitution,
            documentDate = documentDate,
            storedFileName = storedFileName,
            mimeType = mimeType,
            notes = notes,
            tags = tags,
        )
        enqueueTextExtraction(documentId)
        return documentId
    }

    private fun enqueueTextExtraction(documentId: Long) {
        val request = OneTimeWorkRequestBuilder<TextExtractionWorker>()
            .setInputData(TextExtractionWorker.inputData(documentId))
            .build()
        workManager.enqueueUniqueWork(
            TextExtractionWorker.WORK_NAME_PREFIX + documentId,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
