package dev.mokouliszt.poordsh.skills

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * What Settings shows of an installed skill or plugin folder: every file in it,
 * and one file's content to look at, read-only. Only paths inside the folder
 * are read. Plain JVM code, tested on the build host.
 */
object InstalledFiles {
    /** One file: [path] is relative to the folder, with `/`. */
    data class Entry(val path: String, val size: Long)

    /** What [read] found: text (maybe cut at [MAX_TEXT]), an image as base64, or neither. */
    sealed class Content {
        abstract val size: Long
        data class Text(val text: String, val truncated: Boolean, override val size: Long) : Content()
        data class Image(val mime: String, val base64: String, override val size: Long) : Content()
        data class Binary(override val size: Long) : Content()
    }

    /** Enough for any skill's instructions and scripts; longer text is cut. */
    const val MAX_TEXT = 256 * 1024
    const val MAX_IMAGE = 2L * 1024 * 1024
    /** What the WebView shows as an image (an SVG is shown as its source). */
    val IMAGE_TYPES = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp")

    fun list(root: File): List<Entry> = root.walkTopDown().filter { it.isFile }
        .map { Entry(it.relativeTo(root).invariantSeparatorsPath, it.length()) }
        .sortedBy { it.path }
        .toList()

    /** The file at [rel] in [root]; [IllegalArgumentException] for anything outside it or not a file. */
    fun read(root: File, rel: String): Content {
        require(rel.isNotBlank() && !rel.startsWith("/") && rel.split('/').none { it == ".." || it.isEmpty() }) { "bad path" }
        val base = root.canonicalFile
        val file = File(base, rel).canonicalFile
        require(file.path.startsWith(base.path + File.separator) && file.isFile) { "no such file" }
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
        val out = ByteArrayOutputStream()
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
}
