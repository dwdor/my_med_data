package com.medvault.app.importing

import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Configuration for the camera-scan import path (SPEC.md §6.1): ML Kit's Document Scanner UI
 * handles edge detection, perspective correction and multi-page capture itself and hands back a
 * ready-made PDF, so MedVault's own camera code stays minimal — the ImportScreen (Compose) just
 * launches the scanner via `ActivityResultContracts.StartIntentSenderForResult` using the
 * `IntentSender` from [GmsDocumentScanning.getClient(options).getStartScanIntent(activity)].
 *
 * `play-services-mlkit-document-scanner` ships as a beta artifact and needs Google Play services
 * on the device — untested here (no device/emulator available, see SPEC.md).
 */
object DocumentScannerConfig {

    fun options(): GmsDocumentScannerOptions =
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(MAX_PAGES)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

    fun client() = GmsDocumentScanning.getClient(options())

    /** Reads the produced PDF's bytes so [ImportManager.importScannedPdf] can hand them to the
     * encrypted file store, given the content resolver from the calling Activity/Context. */
    fun readPdfBytes(
        contentResolver: android.content.ContentResolver,
        result: GmsDocumentScanningResult,
    ): ByteArray? {
        val pdfUri = result.pdf?.uri ?: return null
        return contentResolver.openInputStream(pdfUri)?.use { it.readBytes() }
    }

    private const val MAX_PAGES = 20
}
