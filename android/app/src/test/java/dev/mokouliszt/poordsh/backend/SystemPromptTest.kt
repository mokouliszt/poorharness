package dev.mokouliszt.poordsh.backend

import dev.mokouliszt.poordsh.outputs.Outputs
import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/adr/system-prompt.md. */
class SystemPromptTest {
    @Test
    fun theUsersPromptFollowsTheAppsInstructions() {
        assertEquals(Outputs.INSTRUCTIONS, SystemPrompt.compose("  "))
        assertEquals(Outputs.INSTRUCTIONS + "\n\nBe brief.", SystemPrompt.compose("Be brief.\n"))
        assertEquals("A\n\nB\n\nBe brief.", SystemPrompt.compose("Be brief.", listOf("A", "", "B")))
    }
}
