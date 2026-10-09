package com.balaswamy.voicecontrol.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.balaswamy.voicecontrol.accessibility.VoiceAccessibilityService
import com.balaswamy.voicecontrol.overlay.OverlayService
import com.balaswamy.voicecontrol.data.history.AutomationHistoryEntry
import com.balaswamy.voicecontrol.history.AutomationHistoryViewModel
import com.balaswamy.voicecontrol.ui.components.SettingsSection
import com.balaswamy.voicecontrol.ui.components.StatusCard

@Composable
fun VoiceAuthenticationScreen(onBack: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Voice Authentication", style = MaterialTheme.typography.headlineMedium)
            StatusCard("Owner voice", "Not configured")
            SettingsSection(title = "Verification status") {
                Text(
                    "No on-device speaker embedding model is installed. Speech-to-text is not owner " +
                        "verification, so voice commands cannot authorize actions.",
                )
                Text(
                    "Enrollment and verification controls become available only when a real speaker " +
                        "verification model and its secure local enrollment flow are integrated.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "No voice samples or biometric embeddings are saved by this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AutomationHistoryScreen(
    viewModel: AutomationHistoryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Automation History", style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = viewModel::clearHistory, enabled = state.entries.isNotEmpty()) {
                    Text("Clear")
                }
            }
            Text(
                "History is limited to recent actions. Entered text, passwords, tokens, and API keys are never stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.message?.let {
                Text(
                    it,
                    color = if (state.isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
            if (state.entries.isEmpty()) {
                Text(
                    "No automation history yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.entries, key = AutomationHistoryEntry::id) { entry ->
                        SettingsSection(title = entry.command) {
                            HistoryValue("Time", entry.time)
                            HistoryValue("Action", entry.action)
                            HistoryValue("Result", entry.result)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val assistantStatus by OverlayService.assistantStatus.collectAsState()
    val assistantRunning by OverlayService.isRunning.collectAsState()
    var microphoneGranted by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    val microphoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { microphoneGranted = it }

    DisposableEffect(context, lifecycleOwner) {
        fun refresh() {
            microphoneGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            overlayGranted = Settings.canDrawOverlays(context)
            accessibilityEnabled = VoiceAccessibilityService.isServiceEnabled(context)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Permissions", style = MaterialTheme.typography.headlineMedium)
            SettingsSection(title = "Microphone") {
                StatusCard("Audio permission", if (microphoneGranted) "Granted" else "Not granted")
                if (!microphoneGranted) {
                    Button(
                        onClick = { microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant microphone permission")
                    }
                }
            }
            SettingsSection(title = "Accessibility") {
                StatusCard(
                    "VoiceControl Accessibility Service",
                    if (accessibilityEnabled) "Enabled" else "Not enabled",
                )
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Accessibility Settings")
                }
            }
            SettingsSection(title = "Overlay") {
                StatusCard("Display over other apps", if (overlayGranted) "Granted" else "Not granted")
                if (!overlayGranted) {
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
                        Text("Open Overlay Permission Settings")
                    }
                }
            }
            StatusCard(
                "Assistant",
                if (assistantRunning) assistantStatus else "Stopped",
            )
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("About", style = MaterialTheme.typography.headlineMedium)
            SettingsSection(title = "VoiceControl Assistant") {
                Text("Version 1.0")
                Text(
                    "An Android voice-assistant foundation with Gemini planning, on-device secure key storage, " +
                        "and opt-in Accessibility automation.",
                )
            }
            SettingsSection(title = "Getting started") {
                Text("1. Grant microphone and overlay permissions.")
                Text("2. Enable VoiceControl Assistant in Android Accessibility settings.")
                Text("3. Add your own Gemini API key in Settings if you want Gemini features.")
                Text("4. Start the assistant overlay only when you want to use voice controls.")
            }
            SettingsSection(title = "Security") {
                Text(
                    "Owner voice verification is not configured; automation therefore remains disabled. " +
                        "The app will not enter passwords, bypass CAPTCHA/2FA/Cloudflare, change system " +
                        "settings, or perform file-deletion actions.",
                )
                Text(
                    "Gemini automation responses are validated previews and are not executed automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
