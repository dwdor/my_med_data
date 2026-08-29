package com.medvault.app.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

object MimeUtils {

    const val MIME_PDF = "application/pdf"
    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** Mime types MedVault accepts for import (see SPEC.md §6.1/§8). */
    val SUPPORTED_MIME_TYPES = arrayOf(MIME_PDF, "image/*", MIME_DOCX)

    fun extensionForMimeType(mimeType: String): String = when (mimeType) {
        MIME_PDF -> "pdf"
        MIME_DOCX -> "docx"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
    }

    fun isSupported(mimeType: String?): Boolean =
        mimeType != null && (mimeType == MIME_PDF || mimeType == MIME_DOCX || mimeType.startsWith("image/"))

    fun displayNameFor(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return null
    }
}
