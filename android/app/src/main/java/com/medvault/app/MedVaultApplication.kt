package com.medvault.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import net.zetetic.database.sqlcipher.SQLiteDatabase
import javax.inject.Inject

@HiltAndroidApp
class MedVaultApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Loads the native SQLCipher library. TODO verify this call still matches the API of
        // net.zetetic:sqlcipher-android once this project is opened in Android Studio — it was
        // written from memory of the older net.zetetic:android-database-sqlcipher artifact
        // without being able to check the current library's docs in this environment.
        SQLiteDatabase.loadLibs(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
