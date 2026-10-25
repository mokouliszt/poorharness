package dev.mokouliszt.poordsh.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks a picked archive (a skill or a plugin) into an empty folder, refusing
 * paths that leave it and archives that unpack to far more than any real one.
 * Plain JVM code, tested on the build host.
 */
object SafeUnzip {
    /** [reason] is a stable id for the UI; the message says what is wrong, in English. */
    class Refused(val reason: String, message: String) : IOException(message)

    /** Guards against archive bombs. */
    const val MAX_BYTES = 1L shl 30
    const val MAX_ENTRIES = 50_000

    /** Junk archivers add; never part of what was packed. */
    val JUNK_DIRS = setOf("__MACOSX")
    val JUNK_FILES = setOf(".DS_Store")

    /**
     * Writes the entries of [input] under [into]. Folders in [skipDirs] and files
     * for which [skipFile] is true are left out. Zip archives rarely keep Unix
     * modes, so a file that starts like a script (`#!`) or a Linux binary is made
     * executable: a plugin's hooks and MCP servers run such files by path.
     */
    fun extract(input: InputStream, into: File, skipDirs: Set<String> = emptySet(), skipFile: (String) -> Boolean = { false }) {
        into.mkdirs()
        val base = into.canonicalFile
        val skip = JUNK_DIRS + skipDirs
        var total = 0L
        var count = 0
        var sawEntry = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry
                } catch (e: Exception) {
                    throw Refused("not_zip", "The file is not a readable .zip archive (${e.message}).")
                } ?: break
                sawEntry = true
                if (++count > MAX_ENTRIES) throw Refused("too_large", "The archive has too many files.")
                val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.isEmpty()) continue
                if (entry.name.startsWith("/") || parts.any { it == ".." }) {
                    throw Refused("bad_path", "The archive contains an unsafe path: ${entry.name}")
                }
                if (parts.dropLast(1).any { it in skip } || parts.first() in skip) continue
                if (entry.isDirectory) {
                    if (parts.last() !in skip) File(base, parts.joinToString("/")).mkdirs()
                    continue
                }
                val name = parts.last()
                if (name in JUNK_FILES || skipFile(name)) continue
                val out = File(base, parts.joinToString("/")).canonicalFile
                if (!out.path.startsWith(base.path + File.separator)) {
                    throw Refused("bad_path", "The archive contains an unsafe path: ${entry.name}")
                }
                out.parentFile?.mkdirs()
                val head = ByteArray(4)
                var headLen = 0
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        if (headLen < head.size) {
                            val take = minOf(head.size - headLen, n)
                            System.arraycopy(buf, 0, head, headLen, take)
                            headLen += take
                        }
                        total += n
                        if (total > MAX_BYTES) throw Refused("too_large", "The archive unpacks to more than 1 GB.")
                        sink.write(buf, 0, n)
                    }
                }
                if (runsByPath(head, headLen)) out.setExecutable(true, false)
            }
        }
        if (!sawEntry) throw Refused("not_zip", "The file is not a .zip archive, or it is empty.")
    }

    private fun runsByPath(head: ByteArray, len: Int): Boolean =
        (len >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) ||
            (len >= 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() && head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte())

    /** The folder the archive packed: [base] itself when [isRoot] holds there, else its single top folder when it does. */
    fun packedRoot(base: File, isRoot: (File) -> Boolean): File? {
        if (isRoot(base)) return base
        val top = base.listFiles().orEmpty().filter { it.name !in JUNK_DIRS && it.name !in JUNK_FILES }
        return top.singleOrNull()?.takeIf { it.isDirectory && isRoot(it) }
    }
}
