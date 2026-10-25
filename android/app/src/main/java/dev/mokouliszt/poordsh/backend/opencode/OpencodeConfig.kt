package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.backend.EngineOptions
import dev.mokouliszt.poordsh.backend.ModelRef
import dev.mokouliszt.poordsh.backend.SystemPrompt
import dev.mokouliszt.poordsh.git.GitRepos
import dev.mokouliszt.poordsh.outputs.Outputs
import dev.mokouliszt.poordsh.runtime.RuntimePaths
import dev.mokouliszt.poordsh.skills.SkillSync
import dev.mokouliszt.poordsh.settings.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The app's opencode settings, written to a file of its own and passed via
 * OPENCODE_CONFIG, so ~/.config/opencode/opencode.json stays the user's.
 * No model is pinned here: the UI picks one per prompt from the live catalog.
 */
object OpencodeConfig {
    const val GUEST_PATH = "/root/.config/poordsh/opencode.json"
    /**
     * Instructions every session gets ([SystemPrompt.compose]: the app's, then the
     * user's system prompt), read by opencode on the phone at every prompt.
     */
    const val INSTRUCTIONS_PATH = "/root/.config/poordsh/instructions.md"

    /**
     * Deny by default, so a tool opencode adds later is not offered until PoorHarness
     * knows where it runs. File and shell tools are the sandbox ones (see
     * assets/opencode/poordsh-sandboxes.js); the rest touch neither phone nor sandbox.
     * The question tool asks the user through the app in every approval mode
     * (docs/adr/questions.md); subagents do not get it ([agents]).
     */
    fun permission(
        approval: Settings.Approval,
        options: Set<String>,
        skills: List<String> = emptyList(),
        /** Remote MCP servers from plugins ([OpencodePlugins.mcpServers]); their tools are asked for like commands. */
        mcpServers: List<String> = emptyList(),
        /** The repo tool, offered while a bucket is connected; it asks like an edit does. */
        git: Boolean = false,
    ): JSONObject {
        val auto = approval == Settings.Approval.FULL_AUTO
        val always = approval == Settings.Approval.ALWAYS
        return JSONObject()
            .put("*", "deny")
            .put("read", "allow").put("glob", "allow").put("grep", "allow").put("ls", "allow")
            .put("todowrite", "allow").put("todoread", "allow").put("invalid", "allow")
            .put(QUESTION_TOOL, "allow")
            .put("bash", if (auto) "allow" else "ask")
            .put("edit", if (always) "ask" else "allow")
            .put("webfetch", if (always) "ask" else "allow")
            .put("websearch", if (always) "ask" else "allow")
            .put("codesearch", if (always) "ask" else "allow")
            .put("external_directory", if (auto) "allow" else "ask")
            .put("doom_loop", if (auto) "allow" else "ask")
            // Subagents (the task tool) unless switched off; theirs is the chat's sandbox too.
            .apply { if (EngineOptions.SUBAGENTS in options) put("task", "allow") }
            // The batch tool only orchestrates other tool calls, each of which is
            // still asked for; it is offered only when its option is on.
            .apply { if (EngineOptions.BATCH_TOOL in options) put("batch", "allow") }
            // Only the user's own skills; opencode's built-in one edits opencode's config.
            .apply { if (skills.isNotEmpty()) put("skill", JSONObject().put("*", "deny").apply { skills.forEach { put(it, "allow") } }) }
            // A plugin's MCP tools act on another service: asked for unless everything runs on its own.
            .apply { mcpServers.forEach { put("${it}_*", if (auto) "allow" else "ask") } }
            .apply { if (git) put(OpencodeBackend.REPO_TOOL, if (always) "ask" else "allow") }
    }

    /**
     * Providers whose API key the app stores in opencode's auth store: OpenCode
     * Zen ("opencode"), NVIDIA NIM ("nvidia"), Venice ("venice"), and the gateways whose free models
     * the app lists ([FreeGateway.ALL]: OrcaRouter, Infron).
     */
    val KEY_PROVIDERS: Set<String> by lazy { setOf("opencode", "nvidia", Venice.PROVIDER) + FreeGateway.ALL.map { it.id } }

    fun write(
        paths: RuntimePaths,
        approval: Settings.Approval,
        plugins: List<String>,
        options: Set<String>,
        skills: List<String> = emptyList(),
        /** `provider.<id>` blocks, such as OrcaRouter's free-model whitelist. */
        providers: Map<String, JSONObject> = emptyMap(),
        /** What the user's plugins add: skills, remote MCP servers, subagents. */
        fromPlugins: OpencodePlugins = OpencodePlugins(emptyList()),
        systemPrompt: String = "",
        /** The model subagents run on; null keeps them on the model of the turn that starts them. */
        subagentModel: ModelRef? = null,
        /** A bucket is connected: the repo tool and what it is for (docs/adr/git-repos.md). */
        git: Boolean = false,
        /** A model that cannot call tools is on offer: define the agent that runs it ([CHAT_AGENT]). */
        chatAgent: Boolean = false,
    ) {
        val skillNames = (skills + fromPlugins.skillNames).distinct()
        val config = JSONObject()
            .put("\$schema", "https://opencode.ai/config.json")
            // Updates are handled by the app (BinaryUpdater) so versions stay coherent.
            .put("autoupdate", false)
            .put("share", "disabled")
            .put("permission", permission(approval, options, skillNames, fromPlugins.mcpServers, git))
            // The user's skills, bound here from the app's store (SkillSync.ROOT), and those of
            // their plugins (PluginStore.ROOT); the same paths hold a copy in every chat's
            // sandbox, so the paths opencode reports work there.
            .put("skills", JSONObject().put("paths", JSONArray(listOf(SkillSync.ROOT) + fromPlugins.skillPaths)))
        if (plugins.isNotEmpty()) config.put("plugin", JSONArray(plugins))
        // How to hand files to the user (docs/adr/outputs.md), and the user's system prompt.
        writeInstructions(paths, systemPrompt, git)
        config.put("instructions", JSONArray().put(INSTRUCTIONS_PATH))
        if (providers.isNotEmpty()) config.put("provider", JSONObject().apply { providers.forEach { (id, block) -> put(id, block) } })
        fromPlugins.mcp().takeIf { it.length() > 0 }?.let { config.put("mcp", it) }
        val agent = if (EngineOptions.SUBAGENTS in options) agents(fromPlugins, subagentModel) else JSONObject()
        if (chatAgent) agent.put(CHAT_AGENT, chatAgent())
        if (agent.length() > 0) config.put("agent", agent)
        val experimental = JSONObject()
        if (EngineOptions.BATCH_TOOL in options) experimental.put("batch_tool", true)
        if (experimental.length() > 0) config.put("experimental", experimental)
        // Prune old tool outputs during compaction (off by default) to stretch a small context.
        if (EngineOptions.PRUNE in options) config.put("compaction", JSONObject().put("prune", true))

        val file = paths.hostPathOf(GUEST_PATH)
        file.parentFile?.mkdirs()
        file.writeText(config.toString(2))
    }

    /** opencode's own subagents; their prompts and tools stay as opencode defines them. */
    val BUILT_IN_SUBAGENTS = listOf("general", "explore")

    /**
     * The agent for models that cannot call tools: opencode sends every tool it has to
     * any model, and Venice rejects a request that carries `tools` for such a model. A
     * tool its permissions deny is not sent (checked with 1.19.0), so denying all of
     * them gives a plain chat (docs/adr/venice.md).
     */
    const val CHAT_AGENT = "chat"

    fun chatAgent(): JSONObject = JSONObject()
        .put("mode", "primary")
        .put("description", "Plain chat, for models that cannot call tools")
        .put("permission", JSONObject().put("*", "deny"))

    /** opencode's tool that puts multiple-choice questions to the user. */
    const val QUESTION_TOOL = "question"

    /**
     * The `agent` block: opencode's subagents and the plugins'. None of them may ask
     * the user (opencode denies its own subagents the question tool; the allow in
     * [permission] would undo that), and with [model] set, all run on that model
     * (`model` and `variant` replace only those fields of an agent). Without it a
     * subagent runs on the model of the turn that starts it.
     */
    fun agents(fromPlugins: OpencodePlugins, model: ModelRef?): JSONObject {
        val block = fromPlugins.agents()
        for (name in BUILT_IN_SUBAGENTS) block.put(name, JSONObject())
        for (name in block.keys().asSequence().toList()) {
            val agent = block.getJSONObject(name)
            val permission = agent.optJSONObject("permission") ?: JSONObject().also { agent.put("permission", it) }
            permission.put(QUESTION_TOOL, "deny")
            if (model != null) agent.put("model", model.model).apply { model.effort?.let { put("variant", it) } }
        }
        return block
    }

    /** opencode reads the file again at each prompt, so a new system prompt needs no restart. */
    fun writeInstructions(paths: RuntimePaths, systemPrompt: String, git: Boolean = false) {
        val app = listOf(Outputs.INSTRUCTIONS) + listOfNotNull(GitRepos.INSTRUCTIONS.takeIf { git })
        paths.hostPathOf(INSTRUCTIONS_PATH).apply { parentFile?.mkdirs(); writeText(SystemPrompt.compose(systemPrompt, app) + "\n") }
    }

    /** Provider ids that have stored credentials in opencode's auth store. */
    fun storedCredentials(paths: RuntimePaths): Set<String> {
        val auth = File(paths.home, ".local/share/opencode/auth.json")
        if (!auth.isFile) return emptySet()
        return try {
            JSONObject(auth.readText()).keys().asSequence().toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
