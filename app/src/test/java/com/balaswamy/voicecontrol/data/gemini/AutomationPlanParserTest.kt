package com.balaswamy.voicecontrol.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationPlanParserTest {
    @Test
    fun parsesAllowlistedActionsFromFencedJson() {
        val result = AutomationPlanParser.parse(
            """
                ```json
                {"actions":[
                  {"action":"CLICK","target":"New Repository"},
                  {"action":"TYPE","field":"Repository name","text":"VoiceControl"},
                  {"action":"SCROLL_DOWN"},
                  {"action":"OPEN_APP","package":"com.android.chrome"}
                ]}
                ```
            """.trimIndent(),
        )

        assertEquals(
            GeminiResult.Success(
                ValidatedAutomationPlan(
                    listOf(
                        ValidatedAutomationAction.Click("New Repository"),
                        ValidatedAutomationAction.Type("Repository name", "VoiceControl"),
                        ValidatedAutomationAction.ScrollDown,
                        ValidatedAutomationAction.OpenApp("com.android.chrome"),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun rejectsActionsOutsideAllowlist() {
        val result = AutomationPlanParser.parse(
            """{"actions":[{"action":"DELETE_FILES","target":"all"}]}""",
        )

        assertTrue(result is GeminiResult.Error)
        assertEquals(
            GeminiErrorState.INVALID_RESPONSE,
            (result as GeminiResult.Error).state,
        )
    }

    @Test
    fun rejectsInvalidOpenAppPackage() {
        val result = AutomationPlanParser.parse(
            """{"actions":[{"action":"OPEN_APP","package":"com.android.chrome;rm -rf"}]}""",
        )

        assertTrue(result is GeminiResult.Error)
    }

    @Test
    fun rejectsUnrecognizedActionFields() {
        val result = AutomationPlanParser.parse(
            """{"actions":[{"action":"CLICK","target":"Settings","shell":"delete everything"}]}""",
        )

        assertTrue(result is GeminiResult.Error)
    }

    @Test
    fun parsesConfirmationActionsAppLabelsAndBounds() {
        val result = AutomationPlanParser.parse(
            """
                {"actions":[
                  {"action":"OPEN_APP","target":"Chrome"},
                  {"action":"CLICK","bounds":{"left":10,"top":20,"right":80,"bottom":100}},
                  {"action":"CONFIRM_REQUIRED","reason":"Create repository"}
                ]}
            """.trimIndent(),
        )

        assertEquals(
            GeminiResult.Success(
                ValidatedAutomationPlan(
                    listOf(
                        ValidatedAutomationAction.OpenApp("Chrome"),
                        ValidatedAutomationAction.Click(bounds = ScreenBounds(10, 20, 80, 100)),
                        ValidatedAutomationAction.ConfirmRequired("Create repository"),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun rejectsAmbiguousOrInvalidClickSelectors() {
        val ambiguous = AutomationPlanParser.parse(
            """{"actions":[{"action":"CLICK","target":"Create","bounds":{"left":0,"top":0,"right":1,"bottom":1}}]}""",
        )
        val malformedBounds = AutomationPlanParser.parse(
            """{"actions":[{"action":"CLICK","bounds":{"left":5,"top":0,"right":1,"bottom":1}}]}""",
        )

        assertTrue(ambiguous is GeminiResult.Error)
        assertTrue(malformedBounds is GeminiResult.Error)
    }

    @Test
    fun rejectsPasswordAndVerificationFieldTyping() {
        val result = AutomationPlanParser.parse(
            """{"actions":[{"action":"TYPE","field":"Verification code","text":"123456"}]}""",
        )

        assertTrue(result is GeminiResult.Error)
    }

    @Test
    fun rejectsDestructiveActionsAndSystemSettingsTargets() {
        val deletion = AutomationPlanParser.parse(
            """{"actions":[{"action":"CLICK","target":"Delete file"}]}""",
        )
        val settings = AutomationPlanParser.parse(
            """{"actions":[{"action":"OPEN_APP","target":"Settings"}]}""",
        )

        assertTrue(deletion is GeminiResult.Error)
        assertTrue(settings is GeminiResult.Error)
    }

    @Test
    fun rejectsNonStringActionValues() {
        val result = AutomationPlanParser.parse(
            """{"actions":[{"action":"CLICK","target":12}]}""",
        )

        assertTrue(result is GeminiResult.Error)
    }
}
