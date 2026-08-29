package com.medvault.app.ui.unlock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.medvault.app.R

@Composable
fun UnlockScreen(
    onUnlocked: () -> Unit,
    viewModel: UnlockViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val activity = LocalContext.current as FragmentActivity

    fun launchPrompt() {
        val biometricManager = BiometricManager.from(activity)
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (biometricManager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            viewModel.onAuthError(activity.getString(R.string.unlock_no_biometrics))
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.unlock_prompt_title))
            .setSubtitle(activity.getString(R.string.unlock_prompt_subtitle))
            .setAllowedAuthenticators(allowed)
            .build()

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModel.onDeviceAuthSucceeded(onUnlocked)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.onAuthError(errString.toString())
                }
            },
        )
        prompt.authenticate(promptInfo)
    }

    // Prompt immediately on entering the screen (app start / return from background), the
    // "Entsperren" button below is the retry path if the user dismisses it or it errors out.
    LaunchedEffect(Unit) { launchPrompt() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
            Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.unlock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            if (uiState is UnlockUiState.Error) {
                Text(
                    (uiState as UnlockUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            Button(onClick = ::launchPrompt) {
                Text(stringResource(R.string.unlock_button))
            }
        }
    }
}
