package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.plugins.PluginPackage
import dev.mokouliszt.poordsh.plugins.PluginStore
import org.json.JSONObject

/**
 * What opencode takes from the user's plugins (docs/adr/plugins.md). opencode has
 * no plugin bundles of this kind (its own plugins are JavaScript run inside it,
 * on the phone), so each part a bundle holds goes where opencode documents it:
 *
 * - skills: their folders in `skills.paths`, read in place at [PluginStore.ROOT]
 *   (the same path holds a copy in every chat's sandbox);
 * - MCP servers reached by URL: `mcp.<name>` as remote servers. One started as a
 *   command would run beside opencode on the phone, outside the chat's sandbox,
 *   so those are not used;
 * - subagents: `agent.<plugin>:<name>` (the name Claude gives a plugin's agent),
 *   with the tools they list mapped onto opencode's permissions.
 *
 * Hooks and commands have no counterpart here.
 */
class OpencodePlugins(manifests: List<PluginPackage.Manifest>) {
    /** Folders for `skills.paths`. */
    val skillPaths: List<String> = manifests.flatMap { m -> m.skillRoots.map { "${PluginStore.ROOT}/${m.name}/$it" } }

    /** Skill names the `skill` permission allows. */
    val skillNames: List<String> = manifests.flatMap { m -> m.skills.map { it.name } }.distinct()

    /** Server key -> server, first plugin (by name) first when two use one name. */
    private val servers: Map<String, PluginPackage.McpServer> = buildMap {
        for (m in manifests) for (s in m.mcpServers) {
            val url = s.url ?: continue
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            putIfAbsent(serverKey(s.name), s)
        }
    }

    /** Server keys; opencode names their tools `<key>_<tool>`. */
    val mcpServers: List<String> get() = servers.keys.sorted()

    /** The `mcp` config block. */
    fun mcp(): JSONObject = JSONObject().apply {
        for ((key, s) in servers) {
            val def = JSONObject().put("type", "remote").put("url", s.url)
            if (s.headers.isNotEmpty()) def.put("headers", JSONObject(s.headers))
            put(key, def)
        }
    }

    private val agentDefs: List<Pair<String, PluginPackage.Agent>> =
        manifests.flatMap { m -> m.agents.map { "${m.name}:${it.name}" to it } }.distinctBy { it.first }

    /** The `agent` config block: each a subagent the task tool can start. */
    fun agents(): JSONObject = JSONObject().apply {
        for ((name, a) in agentDefs) {
            val def = JSONObject()
                .put("mode", "subagent")
                .put("description", a.description.ifEmpty { name })
                .put("prompt", a.prompt)
            a.tools?.let { listed ->
                val granted = listed.flatMap { CLAUDE_TOOLS[it].orEmpty() }.toSet()
                val denied = CLAUDE_TOOLS.values.flatten().toSet() - granted
                if (denied.isNotEmpty()) def.put("permission", JSONObject().apply { denied.sorted().forEach { put(it, "deny") } })
            }
            put(name, def)
        }
    }

    val isEmpty get() = skillPaths.isEmpty() && servers.isEmpty() && agentDefs.isEmpty()

    companion object {
        /** Claude's tool names in an agent's `tools` list, as the opencode permission keys they cover. */
        val CLAUDE_TOOLS: Map<String, List<String>> = mapOf(
            "Read" to listOf("read"), "Write" to listOf("edit"), "Edit" to listOf("edit"), "MultiEdit" to listOf("edit"),
            "NotebookEdit" to listOf("edit"), "Glob" to listOf("glob"), "Grep" to listOf("grep"), "LS" to listOf("ls"),
            "Bash" to listOf("bash"), "WebFetch" to listOf("webfetch"), "WebSearch" to listOf("websearch", "codesearch"),
            "Task" to listOf("task"), "TodoWrite" to listOf("todowrite"), "TodoRead" to listOf("todoread"), "Skill" to listOf("skill"),
        )

        /** opencode keeps letters, digits, `-` and `_` in tool names. */
        fun serverKey(name: String) = name.replace(Regex("[^A-Za-z0-9_-]"), "-")
    }
}
