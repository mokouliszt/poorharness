package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.plugins.PluginPackage
import dev.mokouliszt.poordsh.plugins.PluginPackage.Agent
import dev.mokouliszt.poordsh.plugins.PluginPackage.McpServer
import dev.mokouliszt.poordsh.plugins.PluginPackage.Skill
import dev.mokouliszt.poordsh.settings.Settings.Approval
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What opencode is given of the user's plugins (docs/adr/plugins.md). */
class OpencodePluginsTest {
    private fun manifest(
        name: String,
        skills: List<String> = emptyList(),
        servers: List<McpServer> = emptyList(),
        agents: List<Agent> = emptyList(),
    ) = PluginPackage.Manifest(
        name = name, version = "1.0.0", description = "", format = PluginPackage.Format.CLAUDE,
        skillRoots = if (skills.isEmpty()) emptyList() else listOf("skills"),
        skills = skills.map { Skill(it, "about $it", "skills/$it") },
        mcpServers = servers, hooks = emptyList(), agents = agents, commands = 0, apps = 0,
    )

    private val kit = manifest(
        "review-kit",
        skills = listOf("checklist"),
        servers = listOf(
            McpServer("docs.search", "https://example.com/mcp", "http", mapOf("X-Team" to "a"), null),
            McpServer("local", null, "stdio", emptyMap(), "node"),
        ),
        agents = listOf(
            Agent("reviewer", "Reviews code", listOf("Read", "Grep"), "You review code."),
            Agent("free", "", null, "Anything."),
        ),
    )

    @Test
    fun skillsAreReadWhereTheSandboxHasThemToo() {
        val p = OpencodePlugins(listOf(kit, manifest("other", skills = listOf("notes"))))
        assertEquals(listOf("/tmp/poordsh/plugins/review-kit/skills", "/tmp/poordsh/plugins/other/skills"), p.skillPaths)
        assertEquals(listOf("checklist", "notes"), p.skillNames)
    }

    @Test
    fun onlyRemoteServersReachOpencode() {
        val p = OpencodePlugins(listOf(kit, manifest("dup", servers = listOf(McpServer("docs.search", "https://other.example/mcp", "http", emptyMap(), null)))))
        // A command server would run on the phone; the first plugin wins a shared name.
        assertEquals(listOf("docs-search"), p.mcpServers)
        val docs = p.mcp().getJSONObject("docs-search")
        assertEquals("remote", docs.getString("type"))
        assertEquals("https://example.com/mcp", docs.getString("url"))
        assertEquals("a", docs.getJSONObject("headers").getString("X-Team"))
    }

    @Test
    fun agentsAreSubagentsNamedAfterTheirPlugin() {
        val agents = OpencodePlugins(listOf(kit)).agents()
        val reviewer = agents.getJSONObject("review-kit:reviewer")
        assertEquals("subagent", reviewer.getString("mode"))
        assertEquals("You review code.", reviewer.getString("prompt"))
        val perm = reviewer.getJSONObject("permission")
        // Listed tools keep the chat's own rules; the rest are off for this agent.
        assertFalse(perm.has("read"))
        assertFalse(perm.has("grep"))
        assertEquals("deny", perm.getString("bash"))
        assertEquals("deny", perm.getString("edit"))
        val free = agents.getJSONObject("review-kit:free")
        assertFalse(free.has("permission"))
        assertEquals("review-kit:free", free.getString("description"))
    }

    @Test
    fun mcpToolsAreAskedForLikeCommands() {
        val servers = listOf("docs-search")
        assertEquals("allow", OpencodeConfig.permission(Approval.FULL_AUTO, emptySet(), emptyList(), servers).getString("docs-search_*"))
        assertEquals("ask", OpencodeConfig.permission(Approval.RISKY, emptySet(), emptyList(), servers).getString("docs-search_*"))
        assertEquals("ask", OpencodeConfig.permission(Approval.ALWAYS, emptySet(), emptyList(), servers).getString("docs-search_*"))
    }

    /**
     * The config the app would write for [kit], kept in build/ so it can be checked
     * against a real opencode (docs/maintenance.md).
     */
    @Test
    fun writesAConfigOpencodeCanCheck() {
        val p = OpencodePlugins(listOf(kit))
        val config = JSONObject()
            .put("\$schema", "https://opencode.ai/config.json")
            .put("permission", OpencodeConfig.permission(Approval.RISKY, emptySet(), p.skillNames, p.mcpServers))
            .put("skills", JSONObject().put("paths", org.json.JSONArray(p.skillPaths)))
            .put("mcp", p.mcp())
            .put("agent", p.agents())
        val out = File("build/opencode-plugins-config.json")
        out.parentFile?.mkdirs()
        out.writeText(config.toString(2))
        assertTrue(out.isFile)
    }

    /** docs/adr/subagent-model.md: one model for opencode's and the plugins' subagents. */
    @Test
    fun aSubagentModelGoesToEverySubagent() {
        val none = OpencodeConfig.agents(OpencodePlugins(listOf(kit)), null)
        assertEquals(setOf("general", "explore", "review-kit:reviewer", "review-kit:free"), none.keys().asSequence().toSet())
        assertFalse(none.getJSONObject("review-kit:reviewer").has("model"))
        // No subagent asks the user (docs/adr/questions.md); a plugin agent's own denials stay.
        for (name in none.keys()) assertEquals(name, "deny", none.getJSONObject(name).getJSONObject("permission").getString("question"))
        assertEquals(setOf("question"), none.getJSONObject("general").getJSONObject("permission").keys().asSequence().toSet())

        val model = dev.mokouliszt.poordsh.backend.ModelRef.parse("opencode:infron/inclusionai/ling-3.1-flash:free", "low")
        val set = OpencodeConfig.agents(OpencodePlugins(listOf(kit)), model)
        assertEquals(setOf("general", "explore", "review-kit:reviewer", "review-kit:free"), set.keys().asSequence().toSet())
        for (name in set.keys()) {
            assertEquals(name, "infron/inclusionai/ling-3.1-flash:free", set.getJSONObject(name).getString("model"))
            assertEquals(name, "low", set.getJSONObject(name).getString("variant"))
        }
        // opencode's own subagents keep their prompt and tools: only the model (and the question denial) is set.
        assertEquals(setOf("model", "variant", "permission"), set.getJSONObject("general").keys().asSequence().toSet())
        assertEquals("You review code.", set.getJSONObject("review-kit:reviewer").getString("prompt"))
        // No level picked: the model's own default.
        val plain = OpencodeConfig.agents(OpencodePlugins(emptyList()), dev.mokouliszt.poordsh.backend.ModelRef.parse("opencode:opencode/big-pickle", null))
        assertFalse(plain.getJSONObject("explore").has("variant"))
    }
}
