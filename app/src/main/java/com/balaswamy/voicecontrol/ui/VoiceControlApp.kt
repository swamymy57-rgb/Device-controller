package com.balaswamy.voicecontrol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.balaswamy.voicecontrol.data.gemini.CodeGenerationRepository
import com.balaswamy.voicecontrol.data.gemini.GeminiRepository
import com.balaswamy.voicecontrol.data.history.AutomationHistoryRepository
import com.balaswamy.voicecontrol.data.secure.GeminiApiKeyRepository
import com.balaswamy.voicecontrol.data.secure.SecureApiKeyStore
import com.balaswamy.voicecontrol.gemini.GeminiAssistantViewModel
import com.balaswamy.voicecontrol.history.AutomationHistoryViewModel
import com.balaswamy.voicecontrol.ui.screens.AboutScreen
import com.balaswamy.voicecontrol.ui.screens.AutomationHistoryScreen
import com.balaswamy.voicecontrol.ui.screens.GeminiAssistantScreen
import com.balaswamy.voicecontrol.ui.screens.PermissionsScreen
import com.balaswamy.voicecontrol.ui.screens.VoiceAuthenticationScreen
import com.balaswamy.voicecontrol.settings.SettingsViewModel
import com.balaswamy.voicecontrol.ui.screens.HomeScreen
import com.balaswamy.voicecontrol.ui.screens.SettingsScreen

@Composable
fun VoiceControlApp() {
    var screen by remember { mutableStateOf(AppScreen.HOME) }
    val context = LocalContext.current
    val secureApiKeyStore = remember(context) { SecureApiKeyStore(context.applicationContext) }
    val apiKeyRepository = remember(secureApiKeyStore) { GeminiApiKeyRepository(secureApiKeyStore) }
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = remember(apiKeyRepository) {
            SettingsViewModel.Factory(apiKeyRepository)
        },
    )
    val geminiRepository = remember(apiKeyRepository) { GeminiRepository(apiKeyRepository) }
    val codeGenerationRepository = remember(geminiRepository) { CodeGenerationRepository(geminiRepository) }
    val geminiAssistantViewModel: GeminiAssistantViewModel = viewModel(
        factory = remember(geminiRepository, codeGenerationRepository) {
            GeminiAssistantViewModel.Factory(geminiRepository, codeGenerationRepository)
        },
    )
    val historyRepository = remember(context) { AutomationHistoryRepository(context.applicationContext) }
    val historyViewModel: AutomationHistoryViewModel = viewModel(
        factory = remember(historyRepository) {
            AutomationHistoryViewModel.Factory(historyRepository)
        },
    )

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            settingsViewModel = settingsViewModel,
            onOpenSettings = { screen = AppScreen.SETTINGS },
            onOpenGeminiAssistant = { screen = AppScreen.GEMINI },
            onOpenVoiceAuthentication = { screen = AppScreen.VOICE_AUTHENTICATION },
            onOpenHistory = { screen = AppScreen.HISTORY },
            onOpenPermissions = { screen = AppScreen.PERMISSIONS },
            onOpenAbout = { screen = AppScreen.ABOUT },
        )
        AppScreen.SETTINGS -> SettingsScreen(
            viewModel = settingsViewModel,
            onBack = { screen = AppScreen.HOME },
        )
        AppScreen.GEMINI -> GeminiAssistantScreen(
            viewModel = geminiAssistantViewModel,
            onBack = { screen = AppScreen.HOME },
            onOpenSettings = { screen = AppScreen.SETTINGS },
        )
        AppScreen.VOICE_AUTHENTICATION -> VoiceAuthenticationScreen(
            onBack = { screen = AppScreen.HOME },
        )
        AppScreen.HISTORY -> AutomationHistoryScreen(
            viewModel = historyViewModel,
            onBack = { screen = AppScreen.HOME },
        )
        AppScreen.PERMISSIONS -> PermissionsScreen(
            onBack = { screen = AppScreen.HOME },
        )
        AppScreen.ABOUT -> AboutScreen(
            onBack = { screen = AppScreen.HOME },
        )
    }
}

private enum class AppScreen {
    HOME,
    SETTINGS,
    GEMINI,
    VOICE_AUTHENTICATION,
    HISTORY,
    PERMISSIONS,
    ABOUT,
}
