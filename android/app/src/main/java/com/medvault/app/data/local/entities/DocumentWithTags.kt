package com.medvault.app.data.local.entities

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class DocumentWithTags(
    @Embedded val document: DocumentEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = DocumentTagCrossRef::class,
            parentColumn = "documentId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<TagEntity>,
)
