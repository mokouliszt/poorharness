package dev.mokouliszt.poordsh.skills

import dev.mokouliszt.poordsh.runtime.Proot
import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Copies a store of folders (one per skill, or one per plugin) into a chat's
 * sandbox at [root], before each turn.
 *
 * The sandbox keeps a manifest of what it holds (folder name to digest), so a
 * sync reads one small file and, when nothing changed, does nothing else. A
 * changed folder is replaced whole; only folders PoorHarness put there are ever
 * removed. The same path works everywhere: opencode on the phone sees the store
 * at [root] (a PRoot bind), so the paths it hands the model are valid in the
 * sandbox.
 */
class FolderSync(val root: String) {
    /** One folder's content: its files (paths relative to the folder), those that run by path, and one digest over all. */
    data class Entry(val digest: String, val files: List<String>, val executables: Set<String> = emptySet())

    /** Everything in a store, by folder name; equal snapshots mean nothing to sync. */
    data class Snapshot(val folders: Map<String, Entry>) {
        val digest: String by lazy {
            sha256(folders.toSortedMap().entries.joinToString("\n") { "${it.key} ${it.value.digest}" }.toByteArray())
        }
    }

    /** Where a sync writes: a Tensorlake sandbox or a device jail. Paths are relative to [root]. */
    interface Target {
        fun readManifest(): String?
        fun removeFolders(names: Collection<String>)
        fun upload(rel: String, file: File)
        fun markExecutable(rels: Collection<String>)
        fun writeManifest(text: String)
    }

    /** Brings [target] up to [snapshot], reading each file through [file] (folder, path in it); true if anything was written. */
    fun sync(snapshot: Snapshot, file: (String, String) -> File, target: Target): Boolean {
        val remote = parse(target.readManifest())
        val local = snapshot.folders.mapValues { it.value.digest }
        if (remote == local) return false
        val stale = remote.keys - local.keys
        val changed = local.filter { (name, digest) -> remote[name] != digest }.keys
        // Changed folders are replaced whole: a file dropped from one must not linger.
        target.removeFolders(stale + changed)
        val executables = mutableListOf<String>()
        for (name in changed) {
            val entry = snapshot.folders.getValue(name)
            for (rel in entry.files) target.upload("$name/$rel", file(name, rel))
            entry.executables.mapTo(executables) { "$name/$it" }
        }
        if (executables.isNotEmpty()) target.markExecutable(executables)
        target.writeManifest(JSONObject().put("folders", JSONObject(local)).toString())
        return true
    }

    private fun parse(text: String?): Map<String, String> {
        val doc = runCatching { JSONObject(text ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        // "skills" is what 0.4–0.7 wrote for skills.
        val o = doc.optJSONObject("folders") ?: doc.optJSONObject("skills") ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    /** A device jail: plain file operations on the jail's own /tmp. */
    inner class PhoneTarget(jail: Proot.Jail) : Target {
        private val dir = File(jail.tmp, root.removePrefix("/tmp/"))

        override fun readManifest(): String? = File(dir, MANIFEST).takeIf { it.isFile }?.readText()

        override fun removeFolders(names: Collection<String>) = names.forEach { File(dir, it).deleteRecursively() }

        override fun upload(rel: String, file: File) {
            val out = File(dir, rel)
            out.parentFile?.mkdirs()
            file.copyTo(out, overwrite = true)
        }

        override fun markExecutable(rels: Collection<String>) = rels.forEach { File(dir, it).setExecutable(true, false) }

        override fun writeManifest(text: String) {
            dir.mkdirs()
            File(dir, MANIFEST).writeText(text)
        }
    }

    /** A Tensorlake sandbox, through its file and process APIs. */
    inner class CloudTarget(private val api: TensorlakeApi, private val sb: TensorlakeApi.Sandbox) : Target {
        override fun readManifest(): String? = api.read(sb, "$root/$MANIFEST")?.use { it.readBytes().decodeToString() }

        override fun removeFolders(names: Collection<String>) {
            if (names.isEmpty()) return
            val r = api.run(sb, "rm", listOf("-rf", "--") + names.map { "$root/$it" })
            if (r.exitCode != 0) throw java.io.IOException("could not clear old folders: ${r.stderr.trim().takeLast(200)}")
        }

        override fun upload(rel: String, file: File) = api.upload(sb, "$root/$rel", file)

        override fun markExecutable(rels: Collection<String>) {
            for (chunk in rels.chunked(200)) {
                val r = api.run(sb, "chmod", listOf("+x", "--") + chunk.map { "$root/$it" })
                if (r.exitCode != 0) throw java.io.IOException("could not mark scripts executable: ${r.stderr.trim().takeLast(200)}")
            }
        }

        override fun writeManifest(text: String) = api.write(sb, "$root/$MANIFEST", text.toByteArray())
    }

    companion object {
        private const val MANIFEST = ".poordsh-manifest.json"

        /** The folders [names] under [dir], hashed for [Snapshot]. */
        fun scan(dir: File, names: List<String>): Snapshot = Snapshot(
            names.associateWith { name ->
                val d = File(dir, name)
                val files = d.walkTopDown().filter { it.isFile }.map { it.relativeTo(d).invariantSeparatorsPath }.sorted().toList()
                val executables = files.filter { File(d, it).canExecute() }.toSet()
                val sums = files.joinToString("\n") { "$it ${sha256(File(d, it))}${if (it in executables) " x" else ""}" }
                Entry(sha256(sums.toByteArray()), files, executables)
            },
        )

        fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return hex(md.digest())
        }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }
}
