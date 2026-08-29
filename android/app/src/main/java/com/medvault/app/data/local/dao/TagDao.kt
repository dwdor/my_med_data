package com.medvault.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medvault.app.data.local.entities.DocumentTagCrossRef
import com.medvault.app.data.local.entities.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: DocumentTagCrossRef)

    @Query("DELETE FROM document_tag_cross_ref WHERE documentId = :documentId")
    suspend fun clearTagsForDocument(documentId: Long)

    @Query(
        """
        SELECT DISTINCT documentId FROM document_tag_cross_ref
        INNER JOIN tags ON tags.id = document_tag_cross_ref.tagId
        WHERE tags.name = :tagName
        """,
    )
    suspend fun getDocumentIdsForTag(tagName: String): List<Long>

    /** Looks up (or creates) each tag by name and links it to [documentId], replacing prior links. */
    suspend fun setTagsForDocument(documentId: Long, tagNames: List<String>) {
        clearTagsForDocument(documentId)
        tagNames.map(String::trim).filter(String::isNotEmpty).distinct().forEach { name ->
            val existing = findByName(name)
            val tagId = existing?.id ?: insert(TagEntity(name = name))
            insertCrossRef(DocumentTagCrossRef(documentId = documentId, tagId = tagId))
        }
    }
}
