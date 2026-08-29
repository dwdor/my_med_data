package com.medvault.app.importing

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.medvault.app.MainActivity
import java.io.File

/**
 * No-UI entry point for "Teilen an MedVault" from the share sheet (Mail-App, Dateien-App, …).
 *
 * Content shared into the app can only be read for the lifetime of this Binder call, and at this
 * point the app may still be locked (no open database, no usable file-encryption key — see
 * SPEC.md §4) — so rather than deferring the read, this activity immediately copies every shared
 * item into the app's own cache and hands local `content://…/incoming_cache/...` URIs off to
 * [MainActivity], which routes to the import flow once the user has unlocked the app.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val localUris = extractSharedUris(intent).mapNotNull(::copyToLocalCache)

        if (localUris.isEmpty()) {
            finish()
            return
        }

        val forwardIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_IMPORT_SHARED
            putParcelableArrayListExtra(MainActivity.EXTRA_SHARED_URIS, ArrayList(localUris))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(forwardIntent)
        finish()
    }

    private fun extractSharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(getStreamExtra(intent))
        Intent.ACTION_SEND_MULTIPLE -> getStreamArrayExtra(intent)
        else -> emptyList()
    }

    @Suppress("DEPRECATION")
    private fun getStreamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun getStreamArrayExtra(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun copyToLocalCache(uri: Uri): Uri? = runCatching {
        val displayName = MimeUtils.displayNameFor(this, uri) ?: "geteiltes_dokument_${System.currentTimeMillis()}"
        val incomingDir = File(cacheDir, "incoming").apply { mkdirs() }
        val target = File(incomingDir, displayName)
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
    }.getOrNull()
}
