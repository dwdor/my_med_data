package com.medvault.app.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.medvault.app.R
import com.medvault.app.data.local.entities.DocumentWithTags
import com.medvault.app.data.model.Category
import com.medvault.app.data.model.OcrStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentListScreen(
    onBack: () -> Unit,
    onAddViaFile: () -> Unit,
    onAddViaScan: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    viewModel: DocumentListViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val category by viewModel.category.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val documents by viewModel.documents.collectAsState()
    var showAddMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.documents_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { showAddMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.documents_add))
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_source_file)) },
                        leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                        onClick = { showAddMenu = false; onAddViaFile() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.import_source_scan)) },
                        leadingIcon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                        onClick = { showAddMenu = false; onAddViaScan() },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.documents_search_hint)) },
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = category == null,
                        onClick = { viewModel.onCategoryChange(null) },
                        label = { Text("Alle") },
                    )
                }
                items(Category.entries.toList()) { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { viewModel.onCategoryChange(if (category == cat) null else cat) },
                        label = { Text(stringResource(cat.labelRes)) },
                    )
                }
            }

            if (tags.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tags) { tag ->
                        FilterChip(
                            selected = selectedTag == tag.name,
                            onClick = { viewModel.onTagChange(tag.name) },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }

            if (documents.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.documents_empty))
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(documents, key = { it.document.id }) { item ->
                        DocumentRow(item, onClick = { onOpenDocument(item.document.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentRow(item: DocumentWithTags, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.document.title, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(item.document.category.labelRes) +
                    (item.document.doctorOrInstitution?.let { " · $it" } ?: "") +
                    (item.document.documentDate?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (item.document.ocrStatus == OcrStatus.PENDING) {
                Text(
                    stringResource(R.string.document_ocr_pending),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else if (item.document.ocrStatus == OcrStatus.FAILED) {
                Text(
                    stringResource(R.string.document_ocr_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (item.tags.isNotEmpty()) {
                Text(
                    item.tags.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
