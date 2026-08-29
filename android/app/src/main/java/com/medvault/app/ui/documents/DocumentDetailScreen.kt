package com.medvault.app.ui.documents

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.medvault.app.R
import com.medvault.app.data.model.Category
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    onBack: () -> Unit,
    viewModel: DocumentDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val document by viewModel.document.collectAsState()
    val pdfPages by viewModel.pdfPages.collectAsState()
    val imageBitmap by viewModel.imageBitmap.collectAsState()
    val extractedText by viewModel.extractedText.collectAsState()
    val deleted by viewModel.deleted.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document?.document?.title.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val file = viewModel.prepareShareFile() ?: return@launch
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = document?.document?.mimeType ?: "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.document_detail_share))
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.document_detail_delete))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            imageBitmap?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth())
                Spacer12()
            }
            pdfPages.forEach { page ->
                Image(bitmap = page.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth())
                Spacer12()
            }
            if (extractedText.isNotBlank()) {
                Text(extractedText, style = MaterialTheme.typography.bodyMedium)
                Spacer12()
            }

            OutlinedTextField(
                value = viewModel.title,
                onValueChange = { viewModel.title = it },
                label = { Text(stringResource(R.string.import_field_title)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer12()
            CategoryDropdownDetail(selected = viewModel.category, onSelected = { viewModel.category = it })
            Spacer12()
            OutlinedTextField(
                value = viewModel.doctor,
                onValueChange = { viewModel.doctor = it },
                label = { Text(stringResource(R.string.import_field_doctor)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer12()
            OutlinedTextField(
                value = viewModel.dateText,
                onValueChange = { viewModel.dateText = it },
                label = { Text(stringResource(R.string.import_field_date) + " (JJJJ-MM-TT)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer12()
            OutlinedTextField(
                value = viewModel.tagsText,
                onValueChange = { viewModel.tagsText = it },
                label = { Text(stringResource(R.string.import_field_tags) + " (Komma-getrennt)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer12()
            OutlinedTextField(
                value = viewModel.notes,
                onValueChange = { viewModel.notes = it },
                label = { Text(stringResource(R.string.import_field_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer12()
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.import_save))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.document_detail_delete)) },
            text = { Text(stringResource(R.string.document_detail_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; viewModel.delete() }) {
                    Text(stringResource(R.string.document_detail_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun Spacer12() = androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdownDetail(selected: Category, onSelected: (Category) -> Unit) {
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
