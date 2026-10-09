package com.balaswamy.voicecontrol.automation

import com.balaswamy.voicecontrol.data.gemini.ValidatedAutomationAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEngineTest {
    private var access = AutomationAccessState(
        accessibilityPermissionGranted = true,
        accessibilityServiceConnected = true,
    )
    private var securityControlVisible = false
    private val performedActions = mutableListOf<ValidatedAutomationAction>()
    private val engine = AutomationEngine(
        accessState = { access },
        securityControlVisible = { securityControlVisible },
        performAction = { action ->
            performedActions += action
            AutomationResult.Success("Action completed.")
        },
    )

    @Test
    fun requiresAuthenticationBeforeAutomation() {
        val result = engine.execute(
            ValidatedAutomationAction.Click(target = "Home"),
            ownerAuthenticated = false,
        )

        assertTrue(result is AutomationResult.AuthenticationRequired)
        assertTrue(performedActions.isEmpty())
    }

    @Test
    fun requiresAccessibilityPermissionAndConnection() {
        access = access.copy(accessibilityPermissionGranted = false)
        assertTrue(
            engine.execute(
                ValidatedAutomationAction.ScrollDown,
                ownerAuthenticated = true,
            ) is AutomationResult.PermissionRequired,
        )

        access = AutomationAccessState(
            accessibilityPermissionGranted = true,
            accessibilityServiceConnected = false,
        )
        assertTrue(
            engine.execute(
                ValidatedAutomationAction.ScrollDown,
                ownerAuthenticated = true,
            ) is AutomationResult.PermissionRequired,
        )
        assertTrue(performedActions.isEmpty())
    }

    @Test
    fun importantActionWaitsForOwnerConfirmation() {
        val action = ValidatedAutomationAction.Click(target = "Create Repository")
        val result = engine.execute(action, ownerAuthenticated = true)

        assertTrue(result is AutomationResult.RequiresConfirmation)
        assertTrue(engine.hasPendingConfirmation())
        assertTrue(performedActions.isEmpty())

        assertTrue(engine.confirmPendingAction(ownerAuthenticated = false) is AutomationResult.AuthenticationRequired)
        assertTrue(engine.hasPendingConfirmation())

        assertEquals(
            AutomationResult.Success("Action completed."),
            engine.confirmPendingAction(ownerAuthenticated = true),
        )
        assertEquals(listOf(action), performedActions)
        assertFalse(engine.hasPendingConfirmation())
    }

    @Test
    fun securityControlStopsActionAndClearsPendingConfirmation() {
        val action = ValidatedAutomationAction.Click(target = "Create account")
        assertTrue(engine.execute(action, ownerAuthenticated = true) is AutomationResult.RequiresConfirmation)
        securityControlVisible = true

        val result = engine.confirmPendingAction(ownerAuthenticated = true)

        assertEquals(
            AutomationResult.ManualInterventionRequired("Manual verification required."),
            result,
        )
        assertFalse(engine.hasPendingConfirmation())
        assertTrue(performedActions.isEmpty())
    }

    @Test
    fun sensitiveTypingAndUnclearBoundsNeedHumanReview() {
        assertTrue(
            engine.execute(
                ValidatedAutomationAction.Type(field = "Password", text = "not-a-real-password"),
                ownerAuthenticated = true,
            ) is AutomationResult.ManualInterventionRequired,
        )
        assertTrue(
            engine.execute(
                ValidatedAutomationAction.Click(bounds = null),
                ownerAuthenticated = true,
            ) is AutomationResult.RequiresConfirmation,
        )
        assertTrue(performedActions.isEmpty())
    }

    @Test
    fun blocksDestructiveActionsAndSystemSettingsChanges() {
        assertTrue(
            engine.execute(
                ValidatedAutomationAction.Click(target = "Delete file"),
                ownerAuthenticated = true,
            ) is AutomationResult.ManualInterventionRequired,
        )
        assertTrue(
            engine.execute(
                ValidatedAutomationAction.Click(target = "Airplane mode"),
                ownerAuthenticated = true,
            ) is AutomationResult.ManualInterventionRequired,
        )
        assertTrue(
            engine.execute(
                ValidatedAutomationAction.OpenApp("Settings"),
                ownerAuthenticated = true,
            ) is AutomationResult.ManualInterventionRequired,
        )
        assertTrue(performedActions.isEmpty())
    }

    @Test
    fun reportsExecutorFailuresAndDoesNotCreateConfirmationForPlanMarkers() {
        val failingEngine = AutomationEngine(
            accessState = { access },
            securityControlVisible = { false },
            performAction = { AutomationResult.Failure("The target was unavailable.") },
        )
        assertEquals(
            AutomationResult.Failure("The target was unavailable."),
            failingEngine.execute(
                ValidatedAutomationAction.Click(target = "Home"),
                ownerAuthenticated = true,
            ),
        )

        assertTrue(
            engine.execute(
                ValidatedAutomationAction.ConfirmRequired("Create repository"),
                ownerAuthenticated = true,
            ) is AutomationResult.RequiresConfirmation,
        )
        assertFalse(engine.hasPendingConfirmation())
        assertTrue(performedActions.isEmpty())
    }

    @Test
    fun cancelingConfirmationPreventsExecution() {
        engine.execute(
            ValidatedAutomationAction.Click(target = "Publish"),
            ownerAuthenticated = true,
        )

        assertTrue(engine.cancelPendingAction())
        assertFalse(engine.hasPendingConfirmation())
        assertTrue(performedActions.isEmpty())
    }
}
