package com.balaswamy.voicecontrol.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.balaswamy.voicecontrol.accessibility.VoiceAccessibilityService
import com.balaswamy.voicecontrol.overlay.OverlayService
import com.balaswamy.voicecontrol.settings.SettingsViewModel
import com.balaswamy.voicecontrol.ui.components.StatusCard

@Composable
fun HomeScreen(
    settingsViewModel: SettingsViewModel,
    onOpenSettings: () -> Unit,
    onOpenGeminiAssistant: () -> Unit,
    onOpenVoiceAuthentication: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val settingsState by settingsViewModel.uiState.collectAsState()
    val assistantStatus by OverlayService.assistantStatus.collectAsState()
    val assistantRunning by OverlayService.isRunning.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var overlayEnabled by remember { mutableStateOf(false) }
    var microphonePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        microphonePermission = granted
        permissionMessage = if (granted) null else "Microphone permission is required for speech recognition."
    }

    DisposableEffect(context, lifecycleOwner) {
        fun refreshStatus() {
            accessibilityEnabled = VoiceAccessibilityService.isServiceEnabled(context)
            overlayEnabled = Settings.canDrawOverlays(context)
            microphonePermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refreshStatus()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("VoiceControl Assistant", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Voice, safe automation, and Gemini tools in one place.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatusCard(
                "Assistant",
                if (assistantRunning) assistantStatus else "Stopped — phone interactions are unaffected",
            )
            StatusCard("Voice Authentication", "Not configured — execution remains disabled")
            StatusCard("Accessibility", if (accessibilityEnabled) "Enabled" else "Needs setup")
            StatusCard("Overlay", if (overlayEnabled) "Enabled" else "Needs setup")
            StatusCard("Audio", if (microphonePermission) "Microphone permission granted" else "Needs permission")
            StatusCard(
                "Gemini API",
                if (settingsState.isApiKeyConfigured) "User key securely configured"
                else "Optional — configure a key in Settings",
            )

            if (!microphonePermission) {
                OutlinedButton(
                    onClick = { microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant Microphone Permission")
                }
            }
            if (!accessibilityEnabled) {
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Enable Accessibility Service")
                }
            }
            if (!overlayEnabled) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow Floating Overlay")
                }
            }
            permissionMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (assistantRunning) {
                        context.stopService(Intent(context, OverlayService::class.java))
                    } else if (
                        microphonePermission && accessibilityEnabled && overlayEnabled
                    ) {
                        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
                    } else {
                        permissionMessage = "Grant microphone and overlay permissions and enable Accessibility first."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = assistantRunning || (microphonePermission && accessibilityEnabled && overlayEnabled),
            ) {
                Text(if (assistantRunning) "Stop Assistant" else "Start Assistant")
            }
            OutlinedButton(
                onClick = onOpenGeminiAssistant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Gemini Assistant")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Settings")
            }
            OutlinedButton(onClick = onOpenVoiceAuthentication, modifier = Modifier.fillMaxWidth()) {
                Text("Voice Authentication")
            }
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Automation History")
            }
            OutlinedButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth()) {
                Text("Permissions")
            }
            OutlinedButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
                Text("About & Safety Guide")
            }
        }
    }
}
