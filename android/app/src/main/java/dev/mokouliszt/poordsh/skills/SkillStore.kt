package dev.mokouliszt.poordsh.skills

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/**
 * The skills the user added, one folder per skill under [dir] (`files/skills`).
 * opencode on the phone reads them in place ([SkillSync.ROOT] is bound to [dir]);
 * each chat's sandbox gets a copy before every turn ([SkillSync]).
 * Plain JVM code, tested on the build host.
 */
class SkillStore(val dir: File) {
    data class Info(val name: String, val description: String, val files: Int, val bytes: Long)

    /** One file of an installed skill: [path] is relative to the skill's folder, with `/`. */
    data class Entry(val path: String, val size: Long)

    /** What [read] found: text (maybe cut at [MAX_TEXT]), an image as base64, or neither. */
    sealed class Content {
        abstract val size: Long
        data class Text(val text: String, val truncated: Boolean, override val size: Long) : Content()
        data class Image(val mime: String, val base64: String, override val size: Long) : Content()
        data class Binary(override val size: Long) : Content()
    }

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

    /** Every file of skill [name], by path; null when it is not installed. */
    @Synchronized
    fun files(name: String): List<Entry>? {
        val root = skillDir(name) ?: return null
        return root.walkTopDown().filter { it.isFile }
            .map { Entry(it.relativeTo(root).invariantSeparatorsPath, it.length()) }
            .sortedBy { it.path }
            .toList()
    }

    /**
     * The file at [rel] in skill [name], for looking at it in Settings. Only paths
     * inside the skill's folder are read; anything else is refused.
     */
    fun read(name: String, rel: String): Content {
        val root = skillDir(name) ?: throw IllegalArgumentException("no such skill")
        require(rel.isNotBlank() && !rel.startsWith("/") && rel.split('/').none { it == ".." || it.isEmpty() }) { "bad path" }
        val file = File(root, rel).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile) { "no such file" }
        val size = file.length()
        IMAGE_TYPES[file.extension.lowercase()]?.let { mime ->
            if (size <= MAX_IMAGE) return Content.Image(mime, Base64.getEncoder().encodeToString(file.readBytes()), size)
            return Content.Binary(size)
        }
        val head = file.inputStream().use { readUpTo(it, MAX_TEXT + 1) }
        val truncated = head.size > MAX_TEXT
        val bytes = if (truncated) head.copyOf(MAX_TEXT) else head
        if (bytes.any { it == 0.toByte() }) return Content.Binary(size)
        // A cut can split a character: drop up to three trailing bytes before giving up on UTF-8.
        for (drop in 0..(if (truncated) 3 else 0)) {
            text(bytes, bytes.size - drop)?.let { return Content.Text(it, truncated, size) }
        }
        return Content.Binary(size)
    }

    /** InputStream.readNBytes needs API 33; the app runs from 26. */
    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun text(bytes: ByteArray, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun skillDir(name: String): File? {
        if (!Regex("^[a-z0-9-]+$").matches(name)) return null
        return File(dir, name).takeIf { File(it, "SKILL.md").isFile }
    }

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

    companion object {
        /** Enough for any skill's instructions and scripts; longer text is cut. */
        const val MAX_TEXT = 256 * 1024
        const val MAX_IMAGE = 2L * 1024 * 1024
        /** What the WebView shows as an image (an SVG is shown as its source). */
        val IMAGE_TYPES = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp")
    }

    private fun skillDirs(): List<File> = dir.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith(".") && File(it, "SKILL.md").isFile }
        .sortedBy { it.name }

    private fun changed() {
        cached = null
        onChange()
    }
}
