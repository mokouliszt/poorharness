package dev.mokouliszt.poordsh.skills

import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * The skills the user added, one folder per skill under [dir] (`files/skills`).
 * opencode on the phone reads them in place ([SkillSync.ROOT] is bound to [dir]);
 * each chat's sandbox gets a copy before every turn ([SkillSync]).
 * Plain JVM code, tested on the build host.
 */
class SkillStore(val dir: File) {
    data class Info(val name: String, val description: String, val files: Int, val bytes: Long)

    @Volatile private var cached: FolderSync.Snapshot? = null

    /** Called after a skill is added or removed. */
    var onChange: () -> Unit = {}

    @Synchronized
    fun list(): List<Info> = skillDirs().mapNotNull { d ->
        val fm = runCatching { SkillPackage.validate(File(d, "SKILL.md").readText()) }.getOrNull() ?: return@mapNotNull null
        val files = d.walkTopDown().filter { it.isFile }.toList()
        Info(fm.name, fm.description, files.size, files.sumOf { it.length() })
    }

    fun names(): List<String> = snapshot().folders.keys.sorted()

    /** Validates and installs a packed skill, replacing one of the same name. */
    @Synchronized
    fun install(input: InputStream): Info {
        dir.mkdirs()
        val incoming = File(dir, ".incoming-" + UUID.randomUUID())
        try {
            val parsed = SkillPackage.extract(input, incoming)
            val target = File(dir, parsed.name)
            val old = File(dir, ".old-" + UUID.randomUUID())
            if (target.exists() && !target.renameTo(old)) throw java.io.IOException("could not replace ${parsed.name}")
            if (!parsed.root.renameTo(target)) {
                old.renameTo(target)
                throw java.io.IOException("could not install ${parsed.name}")
            }
            old.deleteRecursively()
            changed()
            return list().first { it.name == parsed.name }
        } finally {
            incoming.deleteRecursively()
        }
    }

    @Synchronized
    fun delete(name: String) {
        require(Regex("^[a-z0-9-]+$").matches(name)) { "bad skill name" }
        val target = File(dir, name)
        if (target.isDirectory) {
            target.deleteRecursively()
            changed()
        }
    }

    /** File of skill [name] at [rel] (as listed in its [FolderSync.Entry]). */
    fun file(name: String, rel: String): File = File(File(dir, name), rel)

    /** Everything installed, by skill name ([SkillSync] copies it into sandboxes). */
    @Synchronized
    fun snapshot(): FolderSync.Snapshot = cached ?: FolderSync.scan(dir, skillDirs().map { it.name }).also { cached = it }

    /** Leftovers of an install that was cut short. */
    fun cleanUp() {
        dir.listFiles().orEmpty().filter { it.name.startsWith(".incoming-") || it.name.startsWith(".old-") }
            .forEach { it.deleteRecursively() }
    }

    private fun skillDirs(): List<File> = dir.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") && File(it, "SKILL.md").isFile }
        .sortedBy { it.name }

    private fun changed() {
        cached = null
        onChange()
    }
}
