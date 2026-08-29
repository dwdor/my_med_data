package com.medvault.app.data.repository

import com.medvault.app.data.local.DatabaseHolder
import com.medvault.app.data.local.entities.TagEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository @Inject constructor(
    private val databaseHolder: DatabaseHolder,
) {
    fun observeTags(): Flow<List<TagEntity>> = databaseHolder.requireDatabase().tagDao().observeAll()

    suspend fun documentIdsForTag(tagName: String): List<Long> =
        databaseHolder.requireDatabase().tagDao().getDocumentIdsForTag(tagName)
}
