package com.balaswamy.voicecontrol.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.balaswamy.voicecontrol.settings.SettingsViewModel
import com.balaswamy.voicecontrol.ui.components.SettingsSection

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var apiKeyInput by remember { mutableStateOf("") }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            SettingsSection(title = "Gemini AI") {
                Text(
                    "Gemini API Key",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Enter API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(
                    onClick = {
                        viewModel.saveApiKey(apiKeyInput)
                        apiKeyInput = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isSaving,
                ) {
                    Text("Save API Key")
                }
                OutlinedButton(
                    onClick = {
                        apiKeyInput = ""
                        viewModel.clearApiKey()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.isApiKeyConfigured && !state.isSaving,
                ) {
                    Text("Clear API Key")
                }
                Text(
                    "API Key Status: ${if (state.isApiKeyConfigured) "Configured" else "Not Configured"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.isApiKeyConfigured) {
                    Text(
                        "Saved key: ••••••••••••••••",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.message?.let {
                    Text(
                        text = it,
                        color = if (state.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "The key is encrypted on this device and is never displayed after saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection(title = "Accessibility") {
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Accessibility Settings")
                }
            }

            SettingsSection(title = "Overlay") {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow Floating Overlay")
                }
                Text(
                    "Overlay access is optional and must be enabled in Android settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection(title = "Voice Authentication") {
                OutlinedButton(onClick = { }, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Enroll Owner Voice")
                }
                OutlinedButton(onClick = { }, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Verify Owner Voice")
                }
                Text(
                    "Speaker verification model not configured.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
