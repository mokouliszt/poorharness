package dev.mokouliszt.poordsh.plugins

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reading plugin archives and keeping the store (docs/adr/plugins.md). */
class PluginStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((path, text) in entries) {
                z.putNextEntry(ZipEntry(path))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun claudePlugin(name: String = "review-kit", version: String = "1.2.0", hook: String = "echo hi") = zip(
        "$name/.claude-plugin/plugin.json" to """{"name":"$name","version":"$version","description":"Review helpers"}""",
        "$name/skills/checklist/SKILL.md" to "---\nname: checklist\ndescription: A review checklist.\nallowed-tools: Read\n---\nCheck it.\n",
        "$name/skills/notes/README.md" to "not a skill",
        "$name/agents/reviewer.md" to "---\nname: reviewer\ndescription: Reviews code\ntools: Read, Grep, Bash\n---\nYou review code.\n",
        "$name/agents/helper.md" to "---\ndescription: Helps\n---\nYou help.\n",
        "$name/commands/review.md" to "---\ndescription: Review\n---\nReview \$ARGUMENTS\n",
        "$name/hooks/hooks.json" to """{"hooks":{"PostToolUse":[{"matcher":"Edit","hooks":[{"type":"command","command":"$hook"}]}]}}""",
        "$name/.mcp.json" to """{"mcpServers":{"local":{"command":"node","args":["server.js"]},"docs":{"type":"http","url":"https://example.com/mcp","headers":{"X-Team":"a"}}}}""",
        "$name/scripts/run.sh" to "#!/bin/sh\necho run\n",
        "$name/scripts/data.txt" to "plain",
    )

    @Test
    fun readsAClaudePlugin() {
        val store = PluginStore(tmp.newFolder("plugins"))
        val info = store.install(claudePlugin())
        val m = info.manifest
        assertEquals("review-kit", m.name)
        assertEquals("1.2.0", m.version)
        assertEquals(PluginPackage.Format.CLAUDE, m.format)
        assertEquals(listOf("checklist"), m.skills.map { it.name })
        assertEquals(listOf("skills"), m.skillRoots)
        assertEquals(listOf("helper", "reviewer"), m.agents.map { it.name })
        assertEquals(listOf("Read", "Grep", "Bash"), m.agents.first { it.name == "reviewer" }.tools)
        assertNull(m.agents.first { it.name == "helper" }.tools)
        assertEquals("You review code.", m.agents.first { it.name == "reviewer" }.prompt)
        assertEquals(1, m.commands)
        assertEquals(listOf(PluginPackage.Hook("PostToolUse", "echo hi")), m.hooks)
        val docs = m.mcpServers.first { it.name == "docs" }
        assertEquals("https://example.com/mcp", docs.url)
        assertEquals(mapOf("X-Team" to "a"), docs.headers)
        assertEquals("stdio", m.mcpServers.first { it.name == "local" }.transport)
        // Scripts keep working by path even though zip lost their mode.
        assertTrue(store.file("review-kit", "scripts/run.sh").canExecute())
        assertFalse(store.file("review-kit", "scripts/data.txt").canExecute())
        assertEquals(setOf("scripts/run.sh"), store.state().snapshot.folders.getValue("review-kit").executables)
    }

    @Test
    fun readsAnAgentPluginsPlugin() {
        val store = PluginStore(tmp.newFolder("plugins"))
        val m = store.install(
            zip(
                "plugin.json" to """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/plugin.schema.json","name":"sum.tools","version":"0.1.0"}""",
                "skills/summarize/SKILL.md" to "---\nname: summarize\ndescription: Summaries.\n---\nSum.\n",
                "mcp.json" to """{"${'$'}schema":"https://agent-plugins.org/schemas/1.0.0/mcp.schema.json","mcpServers":{"web":{"type":"streamable-http","url":"https://example.org/mcp"},"old":{"type":"sse","url":"https://example.org/sse"}}}""",
                // Agent Plugins has no pointer fields: a Claude-style pointer in plugin.json is not followed.
                "other/SKILL.md" to "---\nname: other\ndescription: x\n---\n",
            ),
        ).manifest
        assertEquals("sum.tools", m.name)
        assertEquals(PluginPackage.Format.AGENT_PLUGINS, m.format)
        assertEquals(listOf("summarize"), m.skills.map { it.name })
        assertEquals(listOf("old" to "sse", "web" to "http"), m.mcpServers.map { it.name to it.transport })
    }

    @Test
    fun followsManifestPointers() {
        val store = PluginStore(tmp.newFolder("plugins"))
        val m = store.install(
            zip(
                "p/.codex-plugin/plugin.json" to """{"name":"pointer","skills":"./tools/skills/","mcpServers":{"mcpServers":{"x":{"url":"https://x.example/mcp"}}},"apps":"./.app.json"}""",
                "p/tools/skills/one/SKILL.md" to "---\nname: one\ndescription: d\n---\n",
                "p/skills/ignored/SKILL.md" to "---\nname: ignored\ndescription: d\n---\n",
                "p/.app.json" to "{}",
            ),
        ).manifest
        assertEquals(listOf("one"), m.skills.map { it.name })
        assertEquals(listOf("tools/skills"), m.skillRoots)
        assertEquals(listOf("x"), m.mcpServers.map { it.name })
        assertEquals(1, m.apps)
    }

    @Test
    fun refusesWhatIsNotAPlugin() {
        fun reason(input: ByteArrayInputStream) = try {
            PluginStore(tmp.newFolder()).install(input); "ok"
        } catch (e: PluginPackage.Invalid) {
            e.reason
        }
        assertEquals("is_skill", reason(zip("s/SKILL.md" to "---\nname: s\ndescription: d\n---\n")))
        assertEquals("is_marketplace", reason(zip("m/.claude-plugin/marketplace.json" to "{}")))
        assertEquals("no_manifest", reason(zip("x/README.md" to "hi")))
        // A plain plugin.json is some other tool's file.
        assertEquals("no_manifest", reason(zip("plugin.json" to """{"name":"x"}""")))
        assertEquals("bad_name", reason(zip(".claude-plugin/plugin.json" to """{"name":"Bad Name"}""")))
        assertEquals("no_name", reason(zip(".claude-plugin/plugin.json" to """{"version":"1"}""")))
        assertEquals("bad_manifest", reason(zip(".claude-plugin/plugin.json" to "{nope")))
        assertEquals(
            "bad_manifest",
            reason(zip(".claude-plugin/plugin.json" to """{"name":"a"}""", ".codex-plugin/plugin.json" to """{"name":"b"}""")),
        )
        assertEquals("bad_path", reason(zip(".claude-plugin/plugin.json" to """{"name":"a"}""", "../evil" to "x")))
        assertEquals("not_zip", reason(ByteArrayInputStream("not a zip".toByteArray())))
    }

    @Test
    fun listsWhatIsInstalled() {
        val dir = tmp.newFolder("plugins")
        // What 0.8 to 0.10 kept for Codex goes.
        File(dir, ".agents/plugins").mkdirs()
        File(dir, ".agents/plugins/marketplace.json").writeText("{}")
        File(dir, ".poordsh-plugins.json").writeText("{}")
        val store = PluginStore(dir)
        var changes = 0
        store.onChange = { changes++ }
        store.install(claudePlugin("alpha"))
        store.install(claudePlugin("beta"))
        assertEquals(2, changes)
        assertEquals(setOf("alpha", "beta"), store.state().snapshot.folders.keys)
        assertFalse(File(dir, ".agents").exists())
        assertFalse(File(dir, ".poordsh-plugins.json").exists())

        store.delete("alpha")
        store.delete("beta")
        assertTrue(store.state().snapshot.folders.isEmpty())
        assertTrue(store.state().isEmpty)
    }

    @Test
    fun anInstalledPluginsFilesCanBeLookedAt() {
        val store = PluginStore(tmp.newFolder("plugins"))
        store.install(claudePlugin())
        val files = store.files("review-kit")!!.map { it.path }
        assertTrue(".claude-plugin/plugin.json" in files)
        assertTrue("skills/checklist/SKILL.md" in files)
        val text = store.read("review-kit", "scripts/run.sh") as dev.mokouliszt.poordsh.skills.InstalledFiles.Content.Text
        assertEquals("#!/bin/sh\necho run\n", text.text)
        assertNull(store.files("missing"))
        for ((name, path) in listOf("review-kit" to "../x", "missing" to "x", "../plugins" to "x")) {
            try {
                store.read(name, path)
                fail("$name/$path")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun skillsListedOneByOneInNestedFolders() {
        // As Google's android-skills plugin does: each skill's own folder, at any depth.
        val store = PluginStore(tmp.newFolder("plugins"))
        val info = store.install(zip(
            ".codex-plugin/plugin.json" to """{"name":"android-kit","version":"1.0.0","skills":["./camera/camerax","./device-ai/litert/litert-lm"]}""",
            ".claude-plugin/plugin.json" to """{"name":"android-kit","skills":["./camera/camerax","./device-ai/litert/litert-lm"]}""",
            ".claude-plugin/marketplace.json" to """{"name":"android-kit","plugins":[{"name":"android-kit","source":"./"}]}""",
            "camera/camerax/SKILL.md" to "---\nname: camerax\ndescription: CameraX.\n---\nUse CameraX.\n",
            "device-ai/litert/litert-lm/SKILL.md" to "---\nname: litert-lm\ndescription: LiteRT-LM.\n---\nRun it.\n",
            "device-ai/litert/litert-lm/references/api.md" to "api",
        ))
        assertEquals(listOf("camerax", "litert-lm"), info.manifest.skills.map { it.name })
        assertEquals(listOf("camera/camerax", "device-ai/litert/litert-lm"), info.manifest.skills.map { it.path })
        assertEquals(listOf("camera/camerax", "device-ai/litert/litert-lm"), info.manifest.skillRoots)
    }

    @Test
    fun syncCopiesPluginsAndTheirModes() {
        val store = PluginStore(tmp.newFolder("plugins"))
        store.install(claudePlugin())
        val jail = dev.mokouliszt.poordsh.runtime.Proot.Jail(tmp.newFolder("home"), tmp.newFolder("tmp"), tmp.newFolder("ws"))
        val target = PluginStore.sync.PhoneTarget(jail)
        val snapshot = store.state().snapshot
        assertTrue(PluginStore.sync.sync(snapshot, store::file, target))
        val root = File(jail.tmp, "poordsh/plugins")
        assertTrue(File(root, "review-kit/scripts/run.sh").canExecute())
        assertFalse(PluginStore.sync.sync(snapshot, store::file, target))
        store.delete("review-kit")
        assertTrue(PluginStore.sync.sync(store.state().snapshot, store::file, target))
        assertFalse(File(root, "review-kit").exists())
    }

    @Test
    fun aFailedInstallLeavesTheOldPlugin() {
        val store = PluginStore(tmp.newFolder("plugins"))
        store.install(claudePlugin(version = "1.0.0"))
        runCatching { store.install(ByteArrayInputStream("broken".toByteArray())) }
        assertEquals("1.0.0", store.list().single().manifest.version)
        assertTrue(store.dir.listFiles()!!.none { it.name.startsWith(".incoming-") || it.name.startsWith(".old-") })
    }
}
