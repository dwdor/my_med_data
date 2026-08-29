package com.medvault.app.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.medvault.app.data.files.EncryptedFileStore
import com.medvault.app.data.model.OcrStatus
import com.medvault.app.data.repository.DocumentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs after every import to make a document's content searchable (see SPEC.md §6.2): rasterizes
 * PDF pages / decodes images and feeds them to on-device ML Kit OCR, or pulls plain text out of
 * `.docx`. Runs via WorkManager so a big multi-page scan doesn't block the UI thread.
 *
 * PDFs and images are briefly decrypted into `cacheDir` because `PdfRenderer` needs a real file
 * descriptor; the temp file is deleted in a `finally` block regardless of outcome.
 */
@HiltWorker
class TextExtractionWorker @AssistedInject constructor(
    @Assisted context: android.content.Context,
    @Assisted params: WorkerParameters,
    private val documentRepository: DocumentRepository,
    private val fileStore: EncryptedFileStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getLong(KEY_DOCUMENT_ID, -1L)
        if (documentId == -1L) return Result.failure()
        val document = documentRepository.getDocument(documentId)?.document ?: return Result.failure()

        return try {
            val text = when {
                document.mimeType == MIME_PDF -> extractFromPdf(document.storedFileName)
                document.mimeType.startsWith("image/") -> extractFromImage(document.storedFileName)
                document.mimeType == MIME_DOCX ->
                    fileStore.openInputStream(document.storedFileName).use(DocxTextExtractor::extract)
                else -> ""
            }
            documentRepository.updateOcrResult(documentId, OcrStatus.DONE, text)
            Result.success()
        } catch (e: Exception) {
            documentRepository.updateOcrResult(documentId, OcrStatus.FAILED, "")
            Result.failure()
        }
    }

    private suspend fun extractFromPdf(storedFileName: String): String {
        val tempFile = File(applicationContext.cacheDir, "ocr_${storedFileName}.pdf")
        fileStore.openInputStream(storedFileName).use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val sb = StringBuilder()
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                page.width * RENDER_SCALE,
                                page.height * RENDER_SCALE,
                                Bitmap.Config.ARGB_8888,
                            )
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            sb.appendLine(recognizeText(bitmap))
                        }
                    }
                    return sb.toString()
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun extractFromImage(storedFileName: String): String {
        val bytes = fileStore.openInputStream(storedFileName).use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        return recognizeText(bitmap)
    }

    private suspend fun recognizeText(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { visionText -> continuation.resume(visionText.text) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    companion object {
        const val WORK_NAME_PREFIX = "text_extraction_"
        const val KEY_DOCUMENT_ID = "document_id"
        private const val RENDER_SCALE = 2
        private const val MIME_PDF = "application/pdf"
        private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        fun inputData(documentId: Long): Data =
            Data.Builder().putLong(KEY_DOCUMENT_ID, documentId).build()
    }
}
