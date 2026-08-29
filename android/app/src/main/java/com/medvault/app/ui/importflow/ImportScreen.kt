package com.medvault.app.ui.importflow

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.medvault.app.R
import com.medvault.app.data.model.Category
import com.medvault.app.importing.DocumentScannerConfig
import com.medvault.app.importing.MimeUtils
import com.medvault.app.ui.navigation.Destinations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onDone: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val pendingFileUri by viewModel.pendingFileUri.collectAsState()
    val saveState by viewModel.saveState.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.onFileSelected(uri)
            MimeUtils.displayNameFor(context, uri)?.let(viewModel::setDefaultTitle)
        }
    }

    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.let { scanResult -> DocumentScannerConfig.readPdfBytes(context.contentResolver, scanResult) }
                ?.let(viewModel::onScanResult)
        }
    }

    // Auto-launch the right source picker for how this screen was entered (see Destinations.ImportMode);
    // "shared" mode already has its Uri from the nav argument and needs no launcher.
    LaunchedEffect(viewModel.mode) {
        when (viewModel.mode) {
            Destinations.ImportMode.FILE -> if (pendingFileUri == null) {
                filePickerLauncher.launch(arrayOf(MimeUtils.MIME_PDF, "image/*", MimeUtils.MIME_DOCX))
            }
            Destinations.ImportMode.SCAN -> DocumentScannerConfig.client()
                .getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
            // On failure (e.g. no Play services) the form still opens with no source selected;
            // save() reports "keine Datei ausgewählt" — acceptable MVP fallback, see SPEC.md.
            else -> Unit // Destinations.ImportMode.SHARED: Uri already came in via the nav argument.
        }
    }

    LaunchedEffect(saveState) {
        if (saveState is SaveState.Done) onDone()
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.import_title)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                when {
                    pendingFileUri != null -> pendingFileUri.toString()
                    else -> "Quelle wird ausgewählt…"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer()
            OutlinedTextField(
                value = viewModel.title,
                onValueChange = { viewModel.title = it },
                label = { Text(stringResource(R.string.import_field_title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer()
            CategoryDropdown(selected = viewModel.category, onSelected = { viewModel.category = it })

            Spacer()
            OutlinedTextField(
                value = viewModel.doctor,
                onValueChange = { viewModel.doctor = it },
                label = { Text(stringResource(R.string.import_field_doctor)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer()
            OutlinedTextField(
                value = viewModel.dateText,
                onValueChange = { viewModel.dateText = it },
                label = { Text(stringResource(R.string.import_field_date) + " (JJJJ-MM-TT)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer()
            OutlinedTextField(
                value = viewModel.tagsText,
                onValueChange = { viewModel.tagsText = it },
                label = { Text(stringResource(R.string.import_field_tags) + " (Komma-getrennt)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer()
            OutlinedTextField(
                value = viewModel.notes,
                onValueChange = { viewModel.notes = it },
                label = { Text(stringResource(R.string.import_field_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Spacer()
            if (saveState is SaveState.Error) {
                Text((saveState as SaveState.Error).message, color = MaterialTheme.colorScheme.error)
                Spacer()
            }
            Button(
                onClick = viewModel::save,
                enabled = saveState !is SaveState.Saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.import_save))
            }
        }
    }
}

@Composable
private fun Spacer() = androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(selected: Category, onSelected: (Category) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource(selected.labelRes),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.import_field_category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        androidx.compose.material3.ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Category.entries.forEach { category ->
                DropdownMenuItem(
                    text = { Text(stringResource(category.labelRes)) },
                    onClick = { onSelected(category); expanded = false },
                )
            }
        }
    }
}
