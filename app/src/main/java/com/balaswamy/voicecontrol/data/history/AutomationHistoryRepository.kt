package com.balaswamy.voicecontrol.data.history

import android.content.Context
import com.balaswamy.voicecontrol.automation.AutomationResult
import com.balaswamy.voicecontrol.commands.VoiceCommand
import com.balaswamy.voicecontrol.commands.safeActionName
import com.balaswamy.voicecontrol.commands.safeDisplayText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class AutomationHistoryEntry(
    val id: String,
    val time: String,
    val command: String,
    val action: String,
    val result: String,
)

class AutomationHistoryRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun record(command: VoiceCommand, result: AutomationResult) {
        val entries = load().toMutableList()
        entries.add(
            0,
            AutomationHistoryEntry(
                id = UUID.randomUUID().toString(),
                time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                command = command.safeDisplayText(),
                action = command.safeActionName(),
                result = safeResult(result),
            ),
        )
        val persisted = JSONArray()
        entries.take(MAX_ENTRIES).forEach { entry ->
            persisted.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("time", entry.time)
                    .put("command", entry.command)
                    .put("action", entry.action)
                    .put("result", entry.result),
            )
        }
        check(preferences.edit().putString(HISTORY_KEY, persisted.toString()).commit()) {
            "Unable to save automation history."
        }
    }

    @Synchronized
    fun load(): List<AutomationHistoryEntry> {
        val encoded = preferences.getString(HISTORY_KEY, null) ?: return emptyList()
        return try {
            val history = JSONArray(encoded)
            (0 until history.length()).mapNotNull { index ->
                val item = history.optJSONObject(index) ?: return@mapNotNull null
                AutomationHistoryEntry(
                    id = item.getString("id"),
                    time = item.getString("time"),
                    command = item.getString("command"),
                    action = item.getString("action"),
                    result = item.getString("result"),
                )
            }.take(MAX_ENTRIES)
        } catch (exception: JSONException) {
            throw IllegalStateException("Saved automation history is invalid.", exception)
        }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(HISTORY_KEY).commit()) {
            "Unable to clear automation history."
        }
    }

    private fun safeResult(result: AutomationResult): String = when (result) {
        is AutomationResult.Success -> "Success"
        is AutomationResult.Failure -> "Failed"
        is AutomationResult.RequiresConfirmation -> "Confirmation required"
        is AutomationResult.PermissionRequired -> "Permission required"
        is AutomationResult.AuthenticationRequired -> "Authentication required"
        is AutomationResult.ManualInterventionRequired -> "Manual verification required"
    }

    private companion object {
        const val PREFERENCES_NAME = "automation_history"
        const val HISTORY_KEY = "entries"
        const val MAX_ENTRIES = 50
    }
}
