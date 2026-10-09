package com.balaswamy.voicecontrol.automation

import com.balaswamy.voicecontrol.data.gemini.AutomationInputSafety
import com.balaswamy.voicecontrol.data.gemini.ValidatedAutomationAction

data class AutomationAccessState(
    val accessibilityPermissionGranted: Boolean,
    val accessibilityServiceConnected: Boolean,
)

sealed interface AutomationResult {
    val message: String

    data class Success(override val message: String) : AutomationResult
    data class Failure(override val message: String) : AutomationResult
    data class RequiresConfirmation(override val message: String) : AutomationResult
    data class PermissionRequired(override val message: String) : AutomationResult
    data class AuthenticationRequired(override val message: String) : AutomationResult
    data class ManualInterventionRequired(override val message: String) : AutomationResult
}

class AutomationEngine(
    private val accessState: () -> AutomationAccessState,
    private val securityControlVisible: () -> Boolean,
    private val performAction: (ValidatedAutomationAction) -> AutomationResult,
) {
    private var pendingConfirmation: ValidatedAutomationAction? = null

    fun execute(
        action: ValidatedAutomationAction,
        ownerAuthenticated: Boolean,
    ): AutomationResult {
        val accessFailure = checkAccess(ownerAuthenticated)
        if (accessFailure != null) return accessFailure

        if (pendingConfirmation != null) {
            return AutomationResult.RequiresConfirmation(
                "A confirmation is already pending. Say \"confirm\" or cancel it before another action.",
            )
        }

        if (action is ValidatedAutomationAction.ConfirmRequired) {
            return AutomationResult.RequiresConfirmation(action.reason)
        }
        if (action is ValidatedAutomationAction.Type &&
            AutomationInputSafety.isSensitiveField(action.field)
        ) {
            return AutomationResult.ManualInterventionRequired(MANUAL_VERIFICATION_MESSAGE)
        }
        if (action is ValidatedAutomationAction.Click &&
            (action.target?.let(AutomationInputSafety::isDestructiveAction) == true ||
                action.target?.let(AutomationInputSafety::isSystemSettingsAction) == true)
        ) {
            return AutomationResult.ManualInterventionRequired(MANUAL_VERIFICATION_MESSAGE)
        }
        if (action is ValidatedAutomationAction.OpenApp &&
            AutomationInputSafety.isSystemSettingsAction(action.packageName)
        ) {
            return AutomationResult.ManualInterventionRequired(MANUAL_VERIFICATION_MESSAGE)
        }
        if (securityControlVisible()) {
            return AutomationResult.ManualInterventionRequired(MANUAL_VERIFICATION_MESSAGE)
        }
        if (requiresConfirmation(action)) {
            pendingConfirmation = action
            return AutomationResult.RequiresConfirmation(
                "Confirmation required before ${describe(action)}. Say \"confirm\" to continue.",
            )
        }
        return performAction(action)
    }

    fun confirmPendingAction(ownerAuthenticated: Boolean): AutomationResult {
        val action = pendingConfirmation
            ?: return AutomationResult.Failure("There is no pending action to confirm.")
        val accessFailure = checkAccess(ownerAuthenticated)
        if (accessFailure != null) return accessFailure
        if (action is ValidatedAutomationAction.Type &&
            AutomationInputSafety.isSensitiveField(action.field)
        ) {
            pendingConfirmation = null
            return AutomationResult.ManualInterventionRequired(MANUAL_VERIFICATION_MESSAGE)
        }
        if (action is ValidatedAutomationAction.Click &&
            (action.target?.let(AutomationInputSafety::isDestructiveAction) == true ||
                action.target?.let(AutomationInputSafety::isSystemSettingsAction) == true)
        ) {
            pendingConfirmation = null
            return AutomationResult.ManualInterventionRequired(MANUAL_VERIFICATION_MESSAGE)
        }
        if (action is ValidatedAutomationAction.OpenApp &&
            AutomationInputSafety.isSystemSettingsAction(action.packageName)
        ) {
            pendingConfirmation = null
            return AutomationResult.ManualInterventionRequired(MANUAL_VERIFICATION_MESSAGE)
        }
        if (securityControlVisible()) {
            pendingConfirmation = null
            return AutomationResult.ManualInterventionRequired(MANUAL_VERIFICATION_MESSAGE)
        }

        pendingConfirmation = null
        return performAction(action)
    }

    fun cancelPendingAction(): Boolean {
        if (pendingConfirmation == null) return false
        pendingConfirmation = null
        return true
    }

    fun hasPendingConfirmation(): Boolean = pendingConfirmation != null

    private fun checkAccess(ownerAuthenticated: Boolean): AutomationResult? {
        if (!ownerAuthenticated) {
            return AutomationResult.AuthenticationRequired(
                "Owner voice verification is required before automation.",
            )
        }
        val access = accessState()
        if (!access.accessibilityPermissionGranted || !access.accessibilityServiceConnected) {
            return AutomationResult.PermissionRequired(
                "Enable and connect the VoiceControl Accessibility Service before automation.",
            )
        }
        return null
    }

    private fun requiresConfirmation(action: ValidatedAutomationAction): Boolean {
        if (action is ValidatedAutomationAction.Click && action.target == null) return true
        val label = when (action) {
            is ValidatedAutomationAction.Click -> action.target.orEmpty()
            is ValidatedAutomationAction.Type -> action.field
            is ValidatedAutomationAction.OpenApp -> action.packageName
            ValidatedAutomationAction.ScrollUp,
            ValidatedAutomationAction.ScrollDown,
            is ValidatedAutomationAction.ConfirmRequired,
            -> return false
        }
        return IMPORTANT_ACTION_PATTERN.containsMatchIn(label)
    }

    private fun describe(action: ValidatedAutomationAction): String = when (action) {
        is ValidatedAutomationAction.Click -> "clicking \"${action.target ?: "the selected screen area"}\""
        is ValidatedAutomationAction.Type -> "entering text in \"${action.field}\""
        is ValidatedAutomationAction.OpenApp -> "opening ${action.packageName}"
        ValidatedAutomationAction.ScrollUp -> "scrolling up"
        ValidatedAutomationAction.ScrollDown -> "scrolling down"
        is ValidatedAutomationAction.ConfirmRequired -> action.reason
    }

    private companion object {
        val IMPORTANT_ACTION_PATTERN = Regex(
            """\b(create|delete|remove|publish|send|submit|pay|purchase|transfer|revoke|disable|erase|clear|confirm)\b""",
            RegexOption.IGNORE_CASE,
        )
        const val MANUAL_VERIFICATION_MESSAGE = "Manual verification required."
    }
}
