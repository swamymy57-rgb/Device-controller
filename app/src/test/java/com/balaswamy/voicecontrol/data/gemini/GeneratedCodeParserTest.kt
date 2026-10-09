package com.balaswamy.voicecontrol.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedCodeParserTest {
    @Test
    fun parsesJsonCodeResponse() {
        val result = GeneratedCodeParser.parse(
            """{"language":"kotlin","code":"fun main() {}","explanation":"Entry point"}""",
        )

        assertEquals(
            GeminiResult.Success(GeneratedCode("kotlin", "fun main() {}", "Entry point")),
            result,
        )
    }

    @Test
    fun extractsCodeFromMarkdownFence() {
        val result = GeneratedCodeParser.parse("Example:\n```kotlin\nfun main() {}\n```")

        assertEquals(
            GeminiResult.Success(
                GeneratedCode("kotlin", "fun main() {}", "Example:"),
            ),
            result,
        )
    }

    @Test
    fun rejectsTextWithoutCode() {
        assertTrue(GeneratedCodeParser.parse("No code here.") is GeminiResult.Error)
    }
}
