package com.balaswamy.voicecontrol.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.balaswamy.voicecontrol.data.secure.GeminiApiKeyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val isApiKeyConfigured: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

class SettingsViewModel(
    private val apiKeyRepository: GeminiApiKeyRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshApiKeyStatus()
    }

    fun saveApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(message = "Enter an API key before saving.", isError = true)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, message = null, isError = false)
            try {
                withContext(Dispatchers.IO) { apiKeyRepository.save(apiKey) }
                _uiState.value = SettingsUiState(isApiKeyConfigured = true, message = "API key saved securely.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    message = "Could not save the API key. Please try again.",
                    isError = true,
                )
            }
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, message = null, isError = false)
            try {
                withContext(Dispatchers.IO) { apiKeyRepository.clear() }
                _uiState.value = SettingsUiState(message = "API key cleared.")
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    message = "Could not clear the API key. Please try again.",
                    isError = true,
                )
            }
        }
    }

    private fun refreshApiKeyStatus() {
        viewModelScope.launch {
            try {
                val configured = withContext(Dispatchers.IO) { apiKeyRepository.isConfigured() }
                _uiState.value = _uiState.value.copy(isApiKeyConfigured = configured)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = _uiState.value.copy(
                    message = "Could not read API key status.",
                    isError = true,
                )
            }
        }
    }

    class Factory(private val apiKeyRepository: GeminiApiKeyRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(apiKeyRepository) as T
        }
    }
}
