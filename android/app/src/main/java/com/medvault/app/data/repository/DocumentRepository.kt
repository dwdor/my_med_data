package com.medvault.app.data.repository

import com.medvault.app.data.files.EncryptedFileStore
import com.medvault.app.data.local.DatabaseHolder
import com.medvault.app.data.local.entities.DocumentEntity
import com.medvault.app.data.local.entities.DocumentWithTags
import com.medvault.app.data.model.Category
import com.medvault.app.data.model.OcrStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MVP simplification (documented, not accidental): free browsing is a live [Flow] straight off
 * Room, so the list updates automatically as documents/tags change. Full-text search is a
 * one-shot suspend call instead, because combining an FTS4 MATCH lookup with a dynamic tag
 * filter and a reactively-invalidating Flow would need hand-rolled invalidation tracking; the UI
 * re-runs [search] on every query/filter change instead (see ui/documents/DocumentListViewModel).
 */
@Singleton
class DocumentRepository @Inject constructor(
    private val databaseHolder: DatabaseHolder,
    private val fileStore: EncryptedFileStore,
) {
    fun observeDocuments(
        profileId: Long,
        category: Category?,
        fromDate: LocalDate?,
        toDate: LocalDate?,
    ): Flow<List<DocumentWithTags>> =
        databaseHolder.requireDatabase().documentDao()
            .observeFiltered(profileId, category?.name, fromDate?.toString(), toDate?.toString())

    suspend fun search(
        profileId: Long,
        query: String,
        category: Category?,
        fromDate: LocalDate?,
        toDate: LocalDate?,
        tag: String?,
    ): List<DocumentWithTags> {
        val db = databaseHolder.requireDatabase()
        val ftsIds = if (query.isNotBlank()) db.searchDao().searchDocumentIds(buildFtsQuery(query)) else null
        val tagIds = if (!tag.isNullOrBlank()) db.tagDao().getDocumentIdsForTag(tag) else null

        val candidateIds = when {
            ftsIds != null && tagIds != null -> ftsIds.intersect(tagIds.toSet()).toList()
            ftsIds != null -> ftsIds
            tagIds != null -> tagIds
            else -> null
        }

        return when {
            candidateIds == null -> db.documentDao()
                .observeFiltered(profileId, category?.name, fromDate?.toString(), toDate?.toString())
                .first()
            candidateIds.isEmpty() -> emptyList()
            else -> db.documentDao()
                .observeFilteredByIds(profileId, candidateIds, category?.name, fromDate?.toString(), toDate?.toString())
                .first()
        }
    }

    suspend fun getDocument(id: Long): DocumentWithTags? =
        databaseHolder.requireDatabase().documentDao().getWithTags(id)

    suspend fun getExtractedText(documentId: Long): String =
        databaseHolder.requireDatabase().searchDao().getExtractedText(documentId).orEmpty()

    suspend fun insertDocument(
        profileId: Long,
        title: String,
        category: Category,
        doctorOrInstitution: String?,
        documentDate: LocalDate?,
        storedFileName: String,
        mimeType: String,
        notes: String,
        tags: List<String>,
    ): Long {
        val db = databaseHolder.requireDatabase()
        val id = db.documentDao().insert(
            DocumentEntity(
                profileId = profileId,
                title = title,
                category = category,
                doctorOrInstitution = doctorOrInstitution,
                documentDate = documentDate,
                storedFileName = storedFileName,
                mimeType = mimeType,
                notes = notes,
            ),
        )
        db.tagDao().setTagsForDocument(id, tags)
        db.searchDao().upsertText(id, title, notes, extractedText = "")
        return id
    }

    suspend fun updateMetadata(
        document: DocumentEntity,
        title: String,
        category: Category,
        doctorOrInstitution: String?,
        documentDate: LocalDate?,
        notes: String,
        tags: List<String>,
    ) {
        val db = databaseHolder.requireDatabase()
        db.documentDao().update(
            document.copy(
                title = title,
                category = category,
                doctorOrInstitution = doctorOrInstitution,
                documentDate = documentDate,
                notes = notes,
            ),
        )
        db.tagDao().setTagsForDocument(document.id, tags)
        val extractedText = db.searchDao().getExtractedText(document.id).orEmpty()
        db.searchDao().upsertText(document.id, title, notes, extractedText)
    }

    /** Called by [com.medvault.app.ocr.TextExtractionWorker] once OCR/docx extraction finishes. */
    suspend fun updateOcrResult(documentId: Long, status: OcrStatus, extractedText: String) {
        val db = databaseHolder.requireDatabase()
        val document = db.documentDao().getById(documentId) ?: return
        db.documentDao().update(document.copy(ocrStatus = status))
        db.searchDao().upsertText(documentId, document.title, document.notes, extractedText)
    }

    suspend fun deleteDocument(document: DocumentEntity) {
        val db = databaseHolder.requireDatabase()
        db.documentDao().delete(document)
        db.searchDao().deleteForDocument(document.id)
        fileStore.delete(document.storedFileName)
    }

    private fun buildFtsQuery(raw: String): String =
        raw.trim()
            .split(Regex("\\s+"))
            .map { token -> token.filter(Char::isLetterOrDigit) }
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }
}
