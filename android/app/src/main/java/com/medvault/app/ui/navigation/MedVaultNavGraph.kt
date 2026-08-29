package com.medvault.app.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.medvault.app.crypto.UnlockState
import com.medvault.app.ui.documents.DocumentDetailScreen
import com.medvault.app.ui.documents.DocumentListScreen
import com.medvault.app.ui.importflow.ImportScreen
import com.medvault.app.ui.profiles.ProfileListScreen
import com.medvault.app.ui.settings.SettingsScreen
import com.medvault.app.ui.unlock.UnlockScreen
import com.medvault.app.ui.unlock.UnlockViewModel

/**
 * [pendingSharedUri] carries a document handed in via the Android share sheet (see
 * `importing/ShareReceiverActivity.kt` and `MainActivity`): since a shared document doesn't say
 * which [com.medvault.app.data.local.entities.ProfileEntity] it belongs to, ProfileListScreen
 * routes straight into the import flow for whichever profile the user taps next, instead of that
 * profile's document list, and then clears this state.
 */
@Composable
fun MedVaultNavHost(pendingSharedUri: MutableState<Uri?>) {
    val navController = rememberNavController()
    val unlockViewModel: UnlockViewModel = hiltViewModel()
    val lockState by unlockViewModel.lockState.collectAsState()
    val currentEntry by navController.currentBackStackEntryAsState()

    // Re-locking (MainActivity.onStop -> UnlockManager.lock()) must yank the user back to the
    // unlock screen no matter where they were, since the database/file keys are gone either way.
    LaunchedEffect(lockState, currentEntry) {
        if (lockState == UnlockState.LOCKED && currentEntry?.destination?.route != Destinations.UNLOCK) {
            navController.navigate(Destinations.UNLOCK) {
                popUpTo(0)
            }
        }
    }

    NavHost(navController = navController, startDestination = Destinations.UNLOCK) {
        composable(Destinations.UNLOCK) {
            UnlockScreen(
                onUnlocked = {
                    navController.navigate(Destinations.PROFILES) {
                        popUpTo(Destinations.UNLOCK) { inclusive = true }
                    }
                },
            )
        }
        composable(Destinations.PROFILES) {
            ProfileListScreen(
                onProfileSelected = { profileId ->
                    val sharedUri = pendingSharedUri.value
                    if (sharedUri != null) {
                        pendingSharedUri.value = null
                        navController.navigate(
                            Destinations.import(profileId, Destinations.ImportMode.SHARED, sharedUri.toString()),
                        )
                    } else {
                        navController.navigate(Destinations.documents(profileId))
                    }
                },
                onOpenSettings = { navController.navigate(Destinations.SETTINGS) },
            )
        }
        composable(
            Destinations.DOCUMENTS,
            arguments = listOf(navArgument(Destinations.ARG_PROFILE_ID) { type = NavType.LongType }),
        ) {
            val profileId = it.arguments?.getLong(Destinations.ARG_PROFILE_ID) ?: return@composable
            DocumentListScreen(
                onBack = { navController.popBackStack() },
                onAddViaFile = { navController.navigate(Destinations.import(profileId, Destinations.ImportMode.FILE)) },
                onAddViaScan = { navController.navigate(Destinations.import(profileId, Destinations.ImportMode.SCAN)) },
                onOpenDocument = { documentId -> navController.navigate(Destinations.documentDetail(documentId)) },
            )
        }
        composable(
            Destinations.IMPORT,
            arguments = listOf(
                navArgument(Destinations.ARG_PROFILE_ID) { type = NavType.LongType },
                navArgument(Destinations.ARG_MODE) { type = NavType.StringType },
                navArgument(Destinations.ARG_SHARED_URI) {
                    type = NavType.StringType
                    nullable = true
                },
            ),
        ) {
            ImportScreen(onDone = { navController.popBackStack() })
        }
        composable(
            Destinations.DOCUMENT_DETAIL,
            arguments = listOf(navArgument(Destinations.ARG_DOCUMENT_ID) { type = NavType.LongType }),
        ) {
            DocumentDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(Destinations.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
