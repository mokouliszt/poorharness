package dev.mokouliszt.poordsh.plugins

import dev.mokouliszt.poordsh.skills.FolderSync
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * The plugins the user added, one folder per plugin under [dir] (`files/plugins`).
 * opencode on the phone reads them in place ([ROOT] is bound to [dir]); each
 * chat's sandbox gets a copy at [ROOT] before every turn ([FolderSync]), so the
 * paths opencode reports work there.
 * Plain JVM code, tested on the build host.
 */
class PluginStore(val dir: File) {
    data class Info(val manifest: PluginPackage.Manifest, val files: Int, val bytes: Long)

    /** One plugin and its folder's digest. */
    data class Installed(val manifest: PluginPackage.Manifest, val digest: String)

    /** Everything installed. [snapshot]'s digest changes with any plugin. */
    data class State(val snapshot: FolderSync.Snapshot, val plugins: List<Installed>) {
        val isEmpty get() = plugins.isEmpty()
    }

    @Volatile private var cached: State? = null

    /** Called after a plugin is added or removed. */
    var onChange: () -> Unit = {}

    fun list(): List<Info> = state().plugins.map { p ->
        val files = File(dir, p.manifest.name).walkTopDown().filter { it.isFile }.toList()
        Info(p.manifest, files.size, files.sumOf { it.length() })
    }

    /** Reads and installs a packed plugin, replacing one of the same name. */
    @Synchronized
    fun install(input: InputStream): Info {
        dir.mkdirs()
        val incoming = File(dir, ".incoming-" + UUID.randomUUID())
        try {
            val (manifest, root) = PluginPackage.extract(input, incoming)
            val target = File(dir, manifest.name)
            val old = File(dir, ".old-" + UUID.randomUUID())
            if (target.exists() && !target.renameTo(old)) throw IOException("could not replace ${manifest.name}")
            if (!root.renameTo(target)) {
                old.renameTo(target)
                throw IOException("could not install ${manifest.name}")
            }
            old.deleteRecursively()
            changed()
            return list().first { it.manifest.name == manifest.name }
        } finally {
            incoming.deleteRecursively()
        }
    }

    @Synchronized
    fun delete(name: String) {
        val target = File(dir, checkName(name))
        if (target.isDirectory) {
            target.deleteRecursively()
            changed()
        }
    }

    /** File of plugin [name] at [rel], as listed in its [FolderSync.Entry]. */
    fun file(name: String, rel: String): File = File(File(dir, name), rel)

    /** Read once, then again after each change. */
    @Synchronized
    fun state(): State = cached ?: load().also { cached = it }

    /** Leftovers of an install that was cut short. */
    fun cleanUp() {
        dir.listFiles().orEmpty().filter { it.name.startsWith(".incoming-") || it.name.startsWith(".old-") }
            .forEach { it.deleteRecursively() }
    }

    private fun load(): State {
        val manifests = dir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name }
            .mapNotNull { d -> runCatching { PluginPackage.read(d) }.getOrNull()?.takeIf { it.name == d.name } }
        // Codex's marketplace and hook approvals (0.8 to 0.10) are of no use now.
        File(dir, ".agents").takeIf { it.exists() }?.deleteRecursively()
        File(dir, ".poordsh-plugins.json").delete()
        val snapshot = FolderSync.scan(dir, manifests.map { it.name })
        return State(snapshot, manifests.map { Installed(it, snapshot.folders.getValue(it.name).digest) })
    }

    private fun changed() {
        cached = null
        onChange()
    }

    private fun checkName(name: String): String {
        require(Regex("^[a-z0-9][a-z0-9.-]*$").matches(name) && ".." !in name) { "bad plugin name" }
        return name
    }

    companion object {
        /** Where the plugins are for opencode on the phone and in every chat's sandbox. */
        const val ROOT = "/tmp/poordsh/plugins"

        val sync = FolderSync(ROOT)
    }
}
