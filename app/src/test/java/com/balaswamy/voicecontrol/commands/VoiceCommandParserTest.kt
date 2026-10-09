package com.balaswamy.voicecontrol.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun parsesClickTargets() {
        assertEquals(VoiceCommand.Click("GitHub"), VoiceCommandParser.parse("Click GitHub"))
        assertEquals(
            VoiceCommand.Click("New Repository"),
            VoiceCommandParser.parse("Click New Repository"),
        )
    }

    @Test
    fun parsesScrollDirections() {
        assertEquals(
            VoiceCommand.Scroll(VoiceCommand.Scroll.Direction.UP),
            VoiceCommandParser.parse("Scroll up"),
        )
        assertEquals(
            VoiceCommand.Scroll(VoiceCommand.Scroll.Direction.DOWN),
            VoiceCommandParser.parse("Scroll down"),
        )
    }

    @Test
    fun parsesTextWithOrWithoutField() {
        assertEquals(
            VoiceCommand.Type("Hello", "Username"),
            VoiceCommandParser.parse("Type Hello in Username"),
        )
        assertEquals(VoiceCommand.Type("Hello"), VoiceCommandParser.parse("Type Hello"))
    }

    @Test
    fun parsesGenerateCodePrompt() {
        assertEquals(
            VoiceCommand.GenerateCode("Kotlin login screen"),
            VoiceCommandParser.parse("Generate code for Kotlin login screen"),
        )
    }

    @Test
    fun parsesConfirmationAndCancellation() {
        assertEquals(VoiceCommand.Confirm, VoiceCommandParser.parse("Confirm"))
        assertEquals(VoiceCommand.Cancel, VoiceCommandParser.parse("Cancel."))
    }

    @Test
    fun preservesUnknownCommand() {
        assertEquals(
            VoiceCommand.Unknown("Do something unexpected"),
            VoiceCommandParser.parse("Do something unexpected"),
        )
    }

    @Test
    fun safeCommandDescriptionsNeverIncludeTypedContents() {
        assertEquals(
            "Type into Repository name",
            VoiceCommand.Type("sensitive-value", "Repository name").safeDisplayText(),
        )
        assertEquals(
            "Click [redacted]",
            VoiceCommand.Click("token: hidden-value").safeDisplayText(),
        )
    }
}
