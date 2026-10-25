package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.backend.EngineOptions
import dev.mokouliszt.poordsh.settings.Settings.Approval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** opencode must not be offered anything PoorHarness has not placed in the sandbox (docs/adr/opencode-tools-fail-closed.md). */
class OpencodeConfigTest {
    @Test
    fun deniesByDefaultInEveryMode() {
        for (a in Approval.entries) {
            val p = OpencodeConfig.permission(a, emptySet())
            assertEquals("deny", p.getString("*"))
            // Tools that would run on the phone.
            for (tool in listOf("skill", "lsp", "list", "patch")) assertFalse("$a $tool", p.has(tool))
            // Questions go to the user in every mode (docs/adr/questions.md).
            assertEquals("$a question", "allow", p.getString("question"))
            // The batch tool is offered only when its option is on.
            assertFalse("$a batch", p.has("batch"))
        }
    }

    @Test
    fun approvalsMapOntoCommandsAndEdits() {
        val auto = OpencodeConfig.permission(Approval.FULL_AUTO, EngineOptions.DEFAULTS)
        val risky = OpencodeConfig.permission(Approval.RISKY, EngineOptions.DEFAULTS)
        val always = OpencodeConfig.permission(Approval.ALWAYS, EngineOptions.DEFAULTS)
        assertEquals(listOf("allow", "ask", "ask"), listOf(auto, risky, always).map { it.getString("bash") })
        assertEquals(listOf("allow", "allow", "ask"), listOf(auto, risky, always).map { it.getString("edit") })
        assertEquals(listOf("allow", "allow", "ask"), listOf(auto, risky, always).map { it.getString("websearch") })
        for (p in listOf(auto, risky, always)) {
            for (tool in listOf("read", "glob", "grep", "ls", "task", "todowrite", "invalid")) assertEquals(tool, "allow", p.getString(tool))
        }
    }

    @Test
    fun onlyTheUsersSkillsMayLoad() {
        val p = OpencodeConfig.permission(Approval.FULL_AUTO, emptySet(), listOf("csv-tidy", "pdf-forms"))
        val skill = p.getJSONObject("skill")
        assertEquals("deny", skill.getString("*"))
        assertEquals("allow", skill.getString("csv-tidy"))
        assertEquals("allow", skill.getString("pdf-forms"))
        // opencode's built-in skill edits opencode's own config: never offered.
        assertFalse(skill.has("customize-opencode"))
    }

    @Test
    fun subagentsCanBeSwitchedOff() {
        assertEquals("allow", OpencodeConfig.permission(Approval.RISKY, setOf(EngineOptions.SUBAGENTS)).getString("task"))
        // Off: the task tool falls under "*": "deny" and is not offered.
        assertFalse(OpencodeConfig.permission(Approval.FULL_AUTO, emptySet()).has("task"))
    }

    @Test
    fun batchToolAllowedOnlyWhenEnabled() {
        val on = OpencodeConfig.permission(Approval.FULL_AUTO, setOf("batch_tool"))
        assertEquals("allow", on.getString("batch"))
    }

    @Test
    fun chatAgentDeniesEveryTool() {
        val agent = OpencodeConfig.chatAgent()
        // opencode sends only the tools its permissions allow; a model that rejects tools needs none.
        assertEquals("deny", agent.getJSONObject("permission").getString("*"))
        assertEquals("primary", agent.getString("mode"))
    }

    @Test
    fun veniceKeysAreStoredLikeOthers() {
        assertTrue("venice" in OpencodeConfig.KEY_PROVIDERS)
    }
}
