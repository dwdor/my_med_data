package com.medvault.app.data.local.entities

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 index backing the combined full-text search (OCR/docx-extracted text + title + notes).
 * Room manages the implicit `rowid`; [documentId] is a plain column used to join back to
 * [DocumentEntity] and to delete/replace a document's indexed text on re-import or edit.
 */
@Fts4
@Entity(tableName = "document_text")
data class DocumentTextEntity(
    val documentId: Long,
    val title: String,
    val notes: String,
    val extractedText: String,
)
