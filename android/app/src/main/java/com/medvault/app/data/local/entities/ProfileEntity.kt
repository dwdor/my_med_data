package com.medvault.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB color used as an avatar tint in the profile switcher. */
    val colorTag: Int,
    val createdAt: Instant = Instant.now(),
)
