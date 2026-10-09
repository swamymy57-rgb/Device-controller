package com.balaswamy.voicecontrol.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.balaswamy.voicecontrol.data.gemini.GeminiErrorState
import com.balaswamy.voicecontrol.gemini.GeminiAssistantViewModel
import com.balaswamy.voicecontrol.gemini.GeminiTask

@Composable
fun GeminiAssistantScreen(
    viewModel: GeminiAssistantViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Gemini Assistant", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Questions, generated text and code use your encrypted saved API key. Automation output is validated and preview-only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GeminiTask.entries.chunked(2).forEach { tasks ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tasks.forEach { task ->
                        val label = when (task) {
                            GeminiTask.QUESTION -> "Ask"
                            GeminiTask.TEXT -> "Text"
                            GeminiTask.CODE -> "Code"
                            GeminiTask.AUTOMATION_PLAN -> "Plan"
                        }
                        if (state.task == task) {
                            Button(onClick = { viewModel.setTask(task) }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { viewModel.setTask(task) }) { Text(label) }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::setPrompt,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        when (state.task) {
                            GeminiTask.QUESTION -> "Ask a question"
                            GeminiTask.TEXT -> "Text generation prompt"
                            GeminiTask.CODE -> "Code generation prompt"
                            GeminiTask.AUTOMATION_PLAN -> "Describe an automation plan"
                        },
                    )
                },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Button(
                onClick = viewModel::submit,
                enabled = state.prompt.isNotBlank() && !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        state.isLoading -> "Working…"
                        state.task == GeminiTask.AUTOMATION_PLAN -> "Create Safe Plan Preview"
                        else -> "Send to Gemini"
                    },
                )
            }

            if (state.isLoading) CircularProgressIndicator()
            state.response?.let { response ->
                Text(
                    text = response,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.errorState == null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                )
                if (state.errorState == GeminiErrorState.GEMINI_API_KEY_NOT_CONFIGURED) {
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Settings to add your Gemini API key")
                    }
                }
            }
        }
    }
}
