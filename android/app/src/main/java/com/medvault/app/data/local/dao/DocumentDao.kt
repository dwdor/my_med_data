package com.medvault.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.medvault.app.data.local.entities.DocumentEntity
import com.medvault.app.data.local.entities.DocumentWithTags
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Insert
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity)

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getWithTags(id: Long): DocumentWithTags?

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    /**
     * Browse/filter without a search term. [category] is passed as the enum's `.name` or null
     * for "any category". Date bounds are inclusive ISO-8601 strings (`LocalDate.toString()`).
     */
    @Transaction
    @Query(
        """
        SELECT * FROM documents
        WHERE profileId = :profileId
        AND (:category IS NULL OR category = :category)
        AND (:fromDate IS NULL OR documentDate >= :fromDate)
        AND (:toDate IS NULL OR documentDate <= :toDate)
        ORDER BY documentDate DESC, importedAt DESC
        """,
    )
    fun observeFiltered(
        profileId: Long,
        category: String?,
        fromDate: String?,
        toDate: String?,
    ): Flow<List<DocumentWithTags>>

    /** Restricts a filtered browse to a specific set of document ids (e.g. FTS or tag matches). */
    @Transaction
    @Query(
        """
        SELECT * FROM documents
        WHERE profileId = :profileId
        AND id IN (:documentIds)
        AND (:category IS NULL OR category = :category)
        AND (:fromDate IS NULL OR documentDate >= :fromDate)
        AND (:toDate IS NULL OR documentDate <= :toDate)
        ORDER BY documentDate DESC, importedAt DESC
        """,
    )
    fun observeFilteredByIds(
        profileId: Long,
        documentIds: List<Long>,
        category: String?,
        fromDate: String?,
        toDate: String?,
    ): Flow<List<DocumentWithTags>>
}
