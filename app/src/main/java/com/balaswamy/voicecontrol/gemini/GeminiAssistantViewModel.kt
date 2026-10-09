package com.balaswamy.voicecontrol.gemini

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.balaswamy.voicecontrol.data.gemini.CodeGenerationRepository
import com.balaswamy.voicecontrol.data.gemini.GeminiErrorState
import com.balaswamy.voicecontrol.data.gemini.GeminiRepository
import com.balaswamy.voicecontrol.data.gemini.GeminiResult
import com.balaswamy.voicecontrol.data.gemini.ValidatedAutomationAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class GeminiTask {
    QUESTION,
    TEXT,
    CODE,
    AUTOMATION_PLAN,
}

data class GeminiAssistantUiState(
    val task: GeminiTask = GeminiTask.QUESTION,
    val prompt: String = "",
    val response: String? = null,
    val errorState: GeminiErrorState? = null,
    val isLoading: Boolean = false,
)

class GeminiAssistantViewModel(
    private val geminiRepository: GeminiRepository,
    private val codeGenerationRepository: CodeGenerationRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(GeminiAssistantUiState())
    val uiState: StateFlow<GeminiAssistantUiState> = _uiState.asStateFlow()

    fun setTask(task: GeminiTask) {
        _uiState.value = _uiState.value.copy(task = task, response = null, errorState = null)
    }

    fun setPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(prompt = prompt)
    }

    fun submit() {
        val state = _uiState.value
        if (state.isLoading) return
        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, response = null, errorState = null)
            try {
                when (state.task) {
                    GeminiTask.QUESTION -> display(geminiRepository.askQuestion(state.prompt))
                    GeminiTask.TEXT -> display(geminiRepository.generateText(state.prompt))
                    GeminiTask.CODE -> display(codeGenerationRepository.generateCode(state.prompt))
                    GeminiTask.AUTOMATION_PLAN -> display(geminiRepository.planAutomation(state.prompt))
                }
            } catch (exception: CancellationException) {
                throw exception
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private fun display(result: GeminiResult<*>) {
        _uiState.value = when (result) {
            is GeminiResult.Error -> _uiState.value.copy(
                errorState = result.state,
                response = result.message,
            )
            is GeminiResult.Success<*> -> _uiState.value.copy(
                errorState = null,
                response = renderSuccess(result.value),
            )
        }
    }

    private fun renderSuccess(value: Any?): String = when (value) {
        is com.balaswamy.voicecontrol.data.gemini.GeneratedCode ->
            buildString {
                appendLine(value.language)
                appendLine()
                appendLine(value.code)
                value.explanation?.let {
                    appendLine()
                    append("Explanation: ")
                    append(it)
                }
            }.trim()
        is com.balaswamy.voicecontrol.data.gemini.ValidatedAutomationPlan ->
            if (value.actions.isEmpty()) {
                "No safe actions were identified."
            } else {
                value.actions.mapIndexed { index, action -> "${index + 1}. ${action.toDisplayText()}" }
                    .joinToString("\n")
                    .let { "Validated plan preview — nothing has been executed:\n$it" }
            }
        is String -> value
        else -> "Gemini returned no displayable content."
    }

    private fun ValidatedAutomationAction.toDisplayText(): String = when (this) {
        is ValidatedAutomationAction.Click ->
            target?.let { "Click \"$it\"" } ?: "Click at ${bounds?.let { "${it.left},${it.top}-${it.right},${it.bottom}" }}"
        is ValidatedAutomationAction.Type -> "Type \"$text\" in \"$field\""
        ValidatedAutomationAction.ScrollUp -> "Scroll up"
        ValidatedAutomationAction.ScrollDown -> "Scroll down"
        is ValidatedAutomationAction.OpenApp -> "Open app $packageName"
        is ValidatedAutomationAction.ConfirmRequired -> "Confirmation required: $reason"
    }

    class Factory(
        private val geminiRepository: GeminiRepository,
        private val codeGenerationRepository: CodeGenerationRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GeminiAssistantViewModel::class.java))
            return GeminiAssistantViewModel(geminiRepository, codeGenerationRepository) as T
        }
    }
}
