package com.medvault.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.medvault.app.crypto.UnlockManager
import com.medvault.app.ui.navigation.MedVaultNavHost
import com.medvault.app.ui.theme.MedVaultTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host (see SPEC.md §3). Uses `FragmentActivity` rather than plain
 * `ComponentActivity` because `androidx.biometric.BiometricPrompt` requires one (see
 * ui/unlock/UnlockScreen.kt).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var unlockManager: UnlockManager

    private val pendingSharedUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            MedVaultTheme {
                MedVaultNavHost(pendingSharedUri = pendingSharedUri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Re-locks immediately on backgrounding — no timeout in the MVP, see SPEC.md §4. The nav
     * graph reacts to [UnlockManager.state] and pops back to the unlock screen on its own. */
    override fun onStop() {
        super.onStop()
        unlockManager.lock()
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action != ACTION_IMPORT_SHARED) return
        val uris = getSharedUrisExtra(intent)
        pendingSharedUri.value = uris.firstOrNull()
    }

    @Suppress("DEPRECATION")
    private fun getSharedUrisExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(EXTRA_SHARED_URIS, Uri::class.java)
        else intent.getParcelableArrayListExtra(EXTRA_SHARED_URIS)) ?: emptyList()

    companion object {
        const val ACTION_IMPORT_SHARED = "com.medvault.app.action.IMPORT_SHARED"
        const val EXTRA_SHARED_URIS = "com.medvault.app.extra.SHARED_URIS"
    }
}
