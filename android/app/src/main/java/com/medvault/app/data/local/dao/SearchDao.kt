package com.medvault.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.medvault.app.data.local.entities.DocumentTextEntity

@Dao
interface SearchDao {

    @Insert
    suspend fun insertText(entity: DocumentTextEntity)

    @Query("DELETE FROM document_text WHERE documentId = :documentId")
    suspend fun deleteForDocument(documentId: Long)

    /**
     * (Re-)indexes a document's searchable text (title/notes always available immediately,
     * extractedText filled in once OCR/docx extraction finishes, see TextExtractionWorker).
     */
    @Transaction
    suspend fun upsertText(documentId: Long, title: String, notes: String, extractedText: String) {
        deleteForDocument(documentId)
        insertText(DocumentTextEntity(documentId, title, notes, extractedText))
    }

    /**
     * FTS4 MATCH against title/notes/extractedText. [ftsQuery] should already be a valid FTS
     * query string (e.g. each user-typed token suffixed with `*` for prefix matching).
     */
    @Query("SELECT documentId FROM document_text WHERE document_text MATCH :ftsQuery")
    suspend fun searchDocumentIds(ftsQuery: String): List<Long>

    @Query("SELECT extractedText FROM document_text WHERE documentId = :documentId LIMIT 1")
    suspend fun getExtractedText(documentId: Long): String?
}
