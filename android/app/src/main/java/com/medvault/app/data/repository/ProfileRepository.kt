package com.medvault.app.data.repository

import com.medvault.app.data.local.DatabaseHolder
import com.medvault.app.data.local.entities.ProfileEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val databaseHolder: DatabaseHolder,
) {
    fun observeProfiles(): Flow<List<ProfileEntity>> =
        databaseHolder.requireDatabase().profileDao().observeAll()

    suspend fun createProfile(name: String, colorTag: Int): Long =
        databaseHolder.requireDatabase().profileDao().insert(ProfileEntity(name = name, colorTag = colorTag))

    suspend fun renameProfile(profile: ProfileEntity, newName: String) {
        databaseHolder.requireDatabase().profileDao().update(profile.copy(name = newName))
    }

    suspend fun deleteProfile(profile: ProfileEntity) {
        databaseHolder.requireDatabase().profileDao().delete(profile)
    }
}
