package dev.mokouliszt.poordsh.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The store and the per-turn sync into a sandbox: only what changed moves (docs/adr/skills.md). */
class SkillSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A sandbox in memory: path relative to SkillSync.ROOT -> content. */
    private class Fake : FolderSync.Target {
        val files = mutableMapOf<String, String>()
        var manifest: String? = null
        val uploads = mutableListOf<String>()
        override fun readManifest() = manifest
        override fun markExecutable(rels: Collection<String>) {}
        override fun removeFolders(names: Collection<String>) {
            files.keys.removeAll { k -> names.any { k.startsWith("$it/") } }
        }
        override fun upload(rel: String, file: File) {
            uploads += rel
            files[rel] = file.readText()
        }
        override fun writeManifest(text: String) {
            manifest = text
        }
    }

    private fun pack(name: String, vararg extra: Pair<String, String>, body: String = "body"): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val all = listOf("$name/SKILL.md" to "---\nname: $name\ndescription: about $name\n---\n$body\n") +
                extra.map { "$name/${it.first}" to it.second }
            for ((path, text) in all) {
                z.putNextEntry(ZipEntry(path))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun syncsOnlyWhatChanged() {
        val store = SkillStore(tmp.newFolder("skills"))
        var changes = 0
        store.onChange = { changes++ }
        store.install(pack("alpha", "scripts/a.py" to "print(1)"))
        store.install(pack("beta"))
        assertEquals(2, changes)
        assertEquals(listOf("alpha", "beta"), store.names())

        val box = Fake()
        assertTrue(SkillSync.sync(store, store.snapshot(), box))
        assertEquals(setOf("alpha/SKILL.md", "alpha/scripts/a.py", "beta/SKILL.md"), box.files.keys)

        // Nothing changed: one manifest read, no writes.
        box.uploads.clear()
        assertFalse(SkillSync.sync(store, store.snapshot(), box))
        assertTrue(box.uploads.isEmpty())

        // Replacing a skill swaps it whole (a dropped file goes); the other is left alone.
        val before = store.snapshot().digest
        store.install(pack("alpha", "scripts/b.py" to "print(2)", body = "new body"))
        assertNotEquals(before, store.snapshot().digest)
        assertTrue(SkillSync.sync(store, store.snapshot(), box))
        assertEquals(listOf("alpha/SKILL.md", "alpha/scripts/b.py"), box.uploads.sorted())
        assertEquals(setOf("alpha/SKILL.md", "alpha/scripts/b.py", "beta/SKILL.md"), box.files.keys)

        // Deleting one removes it from the sandbox at the next sync.
        store.delete("beta")
        assertTrue(SkillSync.sync(store, store.snapshot(), box))
        assertEquals(setOf("alpha/SKILL.md", "alpha/scripts/b.py"), box.files.keys)
    }

    @Test
    fun aNewSandboxGetsEverythingAndOthersAreNotTouched() {
        val store = SkillStore(tmp.newFolder("skills"))
        store.install(pack("alpha"))
        val box = Fake()
        box.files["agent-made/SKILL.md"] = "the agent's own skill"
        SkillSync.sync(store, store.snapshot(), box)
        store.delete("alpha")
        SkillSync.sync(store, store.snapshot(), box)
        // Only skills PoorHarness put there are removed.
        assertEquals(setOf("agent-made/SKILL.md"), box.files.keys)
    }

    @Test
    fun phoneTargetWritesIntoTheJailsTmp() {
        val store = SkillStore(tmp.newFolder("skills"))
        store.install(pack("alpha", "scripts/a.py" to "print(1)"))
        val jail = dev.mokouliszt.poordsh.runtime.Proot.Jail(tmp.newFolder("home"), tmp.newFolder("tmp"), tmp.newFolder("ws"))
        val target = SkillSync.folders.PhoneTarget(jail)
        assertTrue(SkillSync.sync(store, store.snapshot(), target))
        assertEquals("print(1)", File(jail.tmp, "poordsh/skills/alpha/scripts/a.py").readText())
        assertFalse(SkillSync.sync(store, store.snapshot(), target))
        store.delete("alpha")
        assertTrue(SkillSync.sync(store, store.snapshot(), target))
        assertFalse(File(jail.tmp, "poordsh/skills/alpha").exists())
    }

    @Test
    fun readsTheManifestEarlierVersionsWrote() {
        val store = SkillStore(tmp.newFolder("skills"))
        store.install(pack("alpha"))
        val box = Fake()
        SkillSync.sync(store, store.snapshot(), box)
        // 0.4–0.7 kept the digests under "skills": an unchanged sandbox is not copied again.
        box.manifest = box.manifest!!.replace("\"folders\"", "\"skills\"")
        box.uploads.clear()
        assertFalse(SkillSync.sync(store, store.snapshot(), box))
        assertTrue(box.uploads.isEmpty())
    }

    @Test
    fun aFailedInstallLeavesTheOldSkill() {
        val store = SkillStore(tmp.newFolder("skills"))
        store.install(pack("alpha", body = "v1"))
        runCatching { store.install(ByteArrayInputStream("broken".toByteArray())) }
        assertEquals(listOf("alpha"), store.names())
        assertTrue(store.file("alpha", "SKILL.md").readText().contains("v1"))
        assertTrue(store.dir.listFiles()!!.none { it.name.startsWith(".") })
    }
}
