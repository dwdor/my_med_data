package com.medvault.app.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.medvault.app.data.model.Category
import com.medvault.app.data.model.OcrStatus
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId"), Index("category"), Index("documentDate")],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val title: String,
    val category: Category,
    val doctorOrInstitution: String?,
    val documentDate: LocalDate?,
    val importedAt: Instant = Instant.now(),
    /** File name inside the encrypted document store, see files/EncryptedFileStore.kt. */
    @ColumnInfo(name = "storedFileName") val storedFileName: String,
    val mimeType: String,
    val ocrStatus: OcrStatus = OcrStatus.PENDING,
    val notes: String = "",
)
