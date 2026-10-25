package dev.mokouliszt.poordsh.plugins

import dev.mokouliszt.poordsh.runtime.SafeUnzip
import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Reads a plugin packed as a `.zip` (or `.plugin`) file: a folder with one of the
 * manifests below (docs/adr/plugins.md), at the top of the archive or in its
 * single top folder. What it holds is read to show it and to hand opencode the
 * parts it can use.
 * Plain JVM code, tested on the build host.
 */
object PluginPackage {
    /** [reason] is a stable id for the UI; [message] says what is wrong, in English. */
    class Invalid(val reason: String, message: String) : IOException(message)

    /** Manifest locations, in the order a plugin's name and version are taken from. */
    enum class Format(val id: String, val manifest: String) {
        /** The open Agent Plugins format (agent-plugins.org): `plugin.json`, `skills/`, `mcp.json`. */
        AGENT_PLUGINS("agent-plugins", "plugin.json"),
        CODEX("codex", ".codex-plugin/plugin.json"),
        CLAUDE("claude", ".claude-plugin/plugin.json"),
        CURSOR("cursor", ".cursor-plugin/plugin.json"),
    }

    data class Skill(val name: String, val description: String, /** Its folder, relative to the plugin. */ val path: String)

    /** [url] is null for a server started as a command ([command]), which runs where the engine does. */
    data class McpServer(val name: String, val url: String?, val transport: String, val headers: Map<String, String>, val command: String?)

    data class Hook(val event: String, val command: String)

    /** A subagent as Claude plugins define one: Markdown with `name`, `description`, `tools` frontmatter. */
    data class Agent(val name: String, val description: String, val tools: List<String>?, val prompt: String)

    data class Manifest(
        val name: String,
        val version: String?,
        val description: String,
        val format: Format,
        /** Folders holding skill folders, relative to the plugin. */
        val skillRoots: List<String>,
        val skills: List<Skill>,
        val mcpServers: List<McpServer>,
        val hooks: List<Hook>,
        val agents: List<Agent>,
        val commands: Int,
        val apps: Int,
    )

    private const val AGENT_PLUGINS_SCHEMA = "https://agent-plugins.org/schemas/"
    private const val MAX_NAME = 64
    /** Agent Plugins' name rule; Codex, Claude and Cursor names (kebab-case) all fit it. */
    private val NAME = Regex("^[a-z0-9](?:[a-z0-9.-]{0,62}[a-z0-9])?$")
    /** Never part of what a plugin needs. */
    private val SKIP_DIRS = setOf(".git", "__pycache__")

    /**
     * Unpacks [input] into the empty folder [into] and reads its manifest. The
     * plugin's own folder is the second value, somewhere under [into].
     */
    fun extract(input: InputStream, into: File): Pair<Manifest, File> {
        try {
            SafeUnzip.extract(input, into, SKIP_DIRS) { it.endsWith(".pyc") }
        } catch (e: SafeUnzip.Refused) {
            throw Invalid(e.reason, e.message ?: e.reason)
        }
        val base = into.canonicalFile
        val root = SafeUnzip.packedRoot(base) { manifestsIn(it).isNotEmpty() } ?: throw notAPlugin(base)
        return read(root) to root
    }

    /** The manifest of the plugin in [root]; throws [Invalid] when there is none or it is not usable. */
    fun read(root: File): Manifest {
        val found = manifestsIn(root)
        if (found.isEmpty()) throw notAPlugin(root)
        val names = found.map { (_, json) -> json.optString("name").trim() }.distinct()
        if (names.size > 1) throw Invalid("bad_manifest", "The plugin's manifests give different names: ${names.joinToString(", ")}.")
        val (format, json) = found.first()
        val name = names.single()
        if (name.isEmpty()) throw Invalid("no_name", "The manifest (${format.manifest}) has no name.")
        if (name.length > MAX_NAME || !NAME.matches(name) || "--" in name || ".." in name) {
            throw Invalid("bad_name", "Plugin name '$name' should be lowercase letters, digits, hyphens and dots, at most $MAX_NAME characters.")
        }
        // Any manifest may point at its parts; the first one that does wins.
        fun field(key: String): Any? = found.firstNotNullOfOrNull { (f, j) -> if (f == Format.AGENT_PLUGINS) null else j.opt(key) }
        val skillRoots = if (format == Format.AGENT_PLUGINS) listOf("skills") else paths(field("skills")).ifEmpty { listOf("skills") }
        return Manifest(
            name = name,
            version = json.optString("version").trim().ifEmpty { null },
            description = json.optString("description").trim(),
            format = format,
            skillRoots = skillRoots.filter { File(root, it).isDirectory },
            skills = skillRoots.flatMap { skillsIn(root, it) }.distinctBy { it.name },
            mcpServers = mcpServers(root, format, field("mcpServers")),
            hooks = hooks(root, field("hooks")),
            agents = paths(field("agents")).ifEmpty { listOf("agents") }.flatMap { agentsIn(root, it) }.distinctBy { it.name },
            commands = paths(field("commands")).ifEmpty { listOf("commands") }.sumOf { rel ->
                File(root, rel).listFiles().orEmpty().count { it.isFile && it.name.endsWith(".md") }
            },
            apps = if (field("apps") != null || File(root, ".app.json").isFile) 1 else 0,
        )
    }

    private fun manifestsIn(dir: File): List<Pair<Format, JSONObject>> = Format.entries.mapNotNull { f ->
        val file = File(dir, f.manifest).takeIf { it.isFile } ?: return@mapNotNull null
        val json = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            throw Invalid("bad_manifest", "${f.manifest} is not valid JSON (${e.message?.lineSequence()?.firstOrNull()}).")
        }
        // A plain plugin.json belongs to some other tool unless it names the Agent Plugins schema.
        if (f == Format.AGENT_PLUGINS && !json.optString("\$schema").startsWith(AGENT_PLUGINS_SCHEMA)) null else f to json
    }

    private fun notAPlugin(dir: File): Invalid {
        fun has(rel: String) = SafeUnzip.packedRoot(dir) { File(it, rel).isFile } != null
        return when {
            has("SKILL.md") -> Invalid("is_skill", "This is a skill, not a plugin.")
            has(".claude-plugin/marketplace.json") || has(".agents/plugins/marketplace.json") ->
                Invalid("is_marketplace", "This is a marketplace of several plugins. Pack one plugin's own folder.")
            else -> Invalid("no_manifest", "No plugin manifest found (plugin.json, .codex-plugin/plugin.json or .claude-plugin/plugin.json).")
        }
    }

    /** A manifest's pointer to its parts: one path or a list of them, kept only when they stay inside the plugin. */
    private fun paths(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }
        else -> emptyList()
    }.mapNotNull(::contained)

    private fun contained(path: String): String? {
        val parts = path.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (path.trim().startsWith("/") || parts.any { it == ".." }) return null
        return parts.joinToString("/").ifEmpty { null }
    }

    private fun skillsIn(root: File, rel: String): List<Skill> {
        val dir = File(root, rel)
        val folders = if (File(dir, "SKILL.md").isFile) listOf(dir) else dir.listFiles().orEmpty().filter { File(it, "SKILL.md").isFile }.sortedBy { it.name }
        return folders.mapNotNull { d ->
            val fm = frontmatter(File(d, "SKILL.md").readText()) ?: return@mapNotNull null
            val name = (fm.first["name"] as? String)?.trim()?.ifEmpty { null } ?: d.name
            Skill(name, (fm.first["description"] as? String)?.trim().orEmpty(), d.relativeTo(root).invariantSeparatorsPath)
        }
    }

    private fun agentsIn(root: File, rel: String): List<Agent> {
        val dir = File(root, rel)
        val files = if (dir.isFile) listOf(dir) else dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".md") }.sortedBy { it.name }
        return files.mapNotNull { f ->
            val (fm, body) = frontmatter(f.readText()) ?: return@mapNotNull null
            val name = (fm["name"] as? String)?.trim()?.ifEmpty { null } ?: f.nameWithoutExtension
            val tools = when (val t = fm["tools"]) {
                is String -> t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                is List<*> -> t.mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }
                else -> null
            }
            Agent(name, (fm["description"] as? String)?.trim().orEmpty(), tools, body.trim())
        }
    }

    private fun mcpServers(root: File, format: Format, pointer: Any?): List<McpServer> {
        val docs: List<JSONObject> = when {
            format == Format.AGENT_PLUGINS -> listOfNotNull(jsonFile(File(root, "mcp.json")))
            pointer is JSONObject -> listOf(pointer)
            pointer != null -> paths(pointer).mapNotNull { jsonFile(File(root, it)) }
            else -> listOfNotNull(jsonFile(File(root, ".mcp.json")) ?: jsonFile(File(root, "mcp.json")))
        }
        return docs.flatMap { doc ->
            val servers = doc.optJSONObject("mcpServers") ?: doc
            servers.keys().asSequence().sorted().mapNotNull { name ->
                val s = servers.optJSONObject(name) ?: return@mapNotNull null
                val url = s.optString("url").trim().ifEmpty { null }
                val command = s.optString("command").trim().ifEmpty { null }
                when {
                    url != null -> McpServer(name, url, if (s.optString("type") == "sse") "sse" else "http", strings(s.optJSONObject("headers")), null)
                    command != null -> McpServer(name, null, "stdio", emptyMap(), command)
                    else -> null
                }
            }.toList()
        }.distinctBy { it.name }
    }

    private fun hooks(root: File, pointer: Any?): List<Hook> {
        val docs: List<JSONObject> = when (pointer) {
            is JSONObject -> listOf(pointer)
            null -> listOfNotNull(jsonFile(File(root, "hooks/hooks.json")))
            else -> paths(pointer).mapNotNull { jsonFile(File(root, it)) }
        }
        return docs.flatMap { doc ->
            val events = doc.optJSONObject("hooks") ?: doc
            events.keys().asSequence().sorted().flatMap { event ->
                val groups = events.optJSONArray(event) ?: JSONArray()
                (0 until groups.length()).asSequence().flatMap { i ->
                    val handlers = groups.optJSONObject(i)?.optJSONArray("hooks") ?: JSONArray()
                    (0 until handlers.length()).asSequence().mapNotNull { j ->
                        val h = handlers.optJSONObject(j) ?: return@mapNotNull null
                        val what = h.optString("command").trim().ifEmpty { h.optString("prompt").trim() }.ifEmpty { h.optString("type") }
                        Hook(event, what)
                    }
                }
            }.toList()
        }
    }

    private fun jsonFile(f: File): JSONObject? = if (!f.isFile) null else runCatching { JSONObject(f.readText()) }.getOrNull()

    private fun strings(o: JSONObject?): Map<String, String> =
        o?.keys()?.asSequence()?.associateWith { o.optString(it) }.orEmpty()

    /** YAML frontmatter and the text after it; null when the file has none that parses. */
    private fun frontmatter(text: String): Pair<Map<*, *>, String>? {
        val content = text.replace("\r\n", "\n").removePrefix("﻿")
        val m = Regex("^---\n(.*?)\n---\n?(.*)$", RegexOption.DOT_MATCHES_ALL).find(content) ?: return null
        val yaml = runCatching { Load(LoadSettings.builder().setAllowDuplicateKeys(true).build()).loadFromString(m.groupValues[1]) }.getOrNull()
        return (yaml as? Map<*, *> ?: return null) to m.groupValues[2]
    }
}
