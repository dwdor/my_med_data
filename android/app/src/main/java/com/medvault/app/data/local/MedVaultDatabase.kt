package com.medvault.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.medvault.app.data.local.dao.DocumentDao
import com.medvault.app.data.local.dao.ProfileDao
import com.medvault.app.data.local.dao.SearchDao
import com.medvault.app.data.local.dao.TagDao
import com.medvault.app.data.local.entities.DocumentEntity
import com.medvault.app.data.local.entities.DocumentTagCrossRef
import com.medvault.app.data.local.entities.DocumentTextEntity
import com.medvault.app.data.local.entities.ProfileEntity
import com.medvault.app.data.local.entities.TagEntity

@Database(
    entities = [
        ProfileEntity::class,
        DocumentEntity::class,
        TagEntity::class,
        DocumentTagCrossRef::class,
        DocumentTextEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MedVaultDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun documentDao(): DocumentDao
    abstract fun tagDao(): TagDao
    abstract fun searchDao(): SearchDao

    companion object {
        const val FILE_NAME = "medvault.db"
    }
}
