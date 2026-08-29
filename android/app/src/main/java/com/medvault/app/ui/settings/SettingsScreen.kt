package com.medvault.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.medvault.app.R
import java.time.LocalDate

private enum class PendingAction { EXPORT, RESTORE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var password by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.exportBackup(uri, password)
        password = ""
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.restoreBackup(uri, password)
        password = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "MedVault ist immer durch Biometrie/Gerätecode gesperrt und verwendet keine " +
                    "Internetverbindung. Ein Backup verlässt dein Gerät nur, wenn du es hier " +
                    "aktiv exportierst.",
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { pendingAction = PendingAction.EXPORT },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is BackupUiState.Working,
            ) {
                Text(stringResource(R.string.settings_backup_export))
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { pendingAction = PendingAction.RESTORE },
                modifier = Modifier.fillMaxWidth(),
                enabled = state !is BackupUiState.Working,
            ) {
                Text(stringResource(R.string.settings_backup_restore))
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
            when (val current = state) {
                is BackupUiState.Working -> CircularProgressIndicator()
                is BackupUiState.ExportDone -> Text("Backup erfolgreich exportiert.")
                is BackupUiState.RestoreDone -> Text(
                    "Wiederherstellung abgeschlossen. Bitte MedVault jetzt vollständig beenden und neu öffnen.",
                    color = MaterialTheme.colorScheme.primary,
                )
                is BackupUiState.Error -> Text(current.message, color = MaterialTheme.colorScheme.error)
                BackupUiState.Idle -> Unit
            }
        }
    }

    if (pendingAction != null) {
        AlertDialog(
            onDismissRequest = { pendingAction = null; password = "" },
            title = { Text(stringResource(R.string.settings_backup_password)) },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_backup_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotBlank(),
                    onClick = {
                        when (pendingAction) {
                            PendingAction.EXPORT -> exportLauncher.launch("medvault-backup-${LocalDate.now()}.mvbackup")
                            PendingAction.RESTORE -> restoreLauncher.launch(arrayOf("*/*"))
                            null -> Unit
                        }
                        pendingAction = null
                    },
                ) { Text("Weiter") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null; password = "" }) { Text("Abbrechen") }
            },
        )
    }

    LaunchedEffect(Unit) { viewModel.resetState() }
}
