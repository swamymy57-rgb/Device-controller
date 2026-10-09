package com.balaswamy.voicecontrol.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.balaswamy.voicecontrol.data.history.AutomationHistoryEntry
import com.balaswamy.voicecontrol.data.history.AutomationHistoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AutomationHistoryUiState(
    val entries: List<AutomationHistoryEntry> = emptyList(),
    val message: String? = null,
    val isError: Boolean = false,
)

class AutomationHistoryViewModel(
    private val repository: AutomationHistoryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AutomationHistoryUiState())
    val uiState: StateFlow<AutomationHistoryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        try {
            _uiState.value = AutomationHistoryUiState(entries = repository.load())
        } catch (exception: IllegalStateException) {
            _uiState.value = AutomationHistoryUiState(
                message = "Could not read automation history.",
                isError = true,
            )
        } catch (exception: SecurityException) {
            _uiState.value = AutomationHistoryUiState(
                message = "Android could not access automation history.",
                isError = true,
            )
        }
    }

    fun clearHistory() {
        try {
            repository.clear()
            _uiState.value = AutomationHistoryUiState(message = "History cleared.")
        } catch (exception: IllegalStateException) {
            _uiState.value = _uiState.value.copy(
                message = "Could not clear automation history.",
                isError = true,
            )
        } catch (exception: SecurityException) {
            _uiState.value = _uiState.value.copy(
                message = "Android could not clear automation history.",
                isError = true,
            )
        }
    }

    class Factory(
        private val repository: AutomationHistoryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AutomationHistoryViewModel::class.java))
            return AutomationHistoryViewModel(repository) as T
        }
    }
}
