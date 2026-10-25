package dev.mokouliszt.poordsh.runtime

import android.system.Os
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal streaming tar reader (ustar, GNU long names, pax headers).
 *
 * The rootfs archive is produced by runtime/rootfs/build.sh with hard links
 * dereferenced, so only directories, regular files and symlinks need handling.
 * Entry paths are confined to the destination: absolute paths and ".." are
 * rejected, and files are never written through a symlink that leaves it.
 */
class TarExtractor(private val input: InputStream) {

    data class Entry(val name: String, val type: Char, val mode: Int, val size: Long, val linkName: String)

    private val header = ByteArray(BLOCK)
    private val buffer = ByteArray(64 * 1024)
    private var pendingLongName: String? = null
    private var pendingLongLink: String? = null
    private var paxPath: String? = null
    private var paxLink: String? = null
    private var paxSize: Long? = null

    /** Extracts every entry under [dest]. [onEntry] is called after each entry. */
    fun extractAll(dest: File, onEntry: (Entry) -> Unit = {}) {
        val root = dest.canonicalFile
        while (true) {
            val entry = next() ?: break
            write(root, entry)
            onEntry(entry)
        }
    }

    /** Streams entries; for the entry matching [predicate], copies its bytes to [out]. */
    fun extractOne(predicate: (Entry) -> Boolean, out: File): Boolean {
        while (true) {
            val entry = next() ?: return false
            if (entry.type == REGULAR && predicate(entry)) {
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { copyBody(entry.size, it) }
                skipPadding(entry.size)
                return true
            }
            skipBody(entry.size)
        }
    }

    private fun next(): Entry? {
        while (true) {
            if (!readBlock(header)) return null
            // A zero block marks the end of the archive.
            if (header.all { it == 0.toByte() }) return null
            val type = header[156].toInt().toChar()
            val size = paxSize ?: parseOctal(header, 124, 12)
            when (type) {
                GNU_LONGNAME -> { pendingLongName = readString(size); continue }
                GNU_LONGLINK -> { pendingLongLink = readString(size); continue }
                PAX_HEADER -> { readPax(size); continue }
                PAX_GLOBAL -> { skipBody(size); continue }
            }
            val name = paxPath ?: pendingLongName ?: ustarName()
            val link = paxLink ?: pendingLongLink ?: cString(header, 157, 100)
            val mode = parseOctal(header, 100, 8).toInt()
            pendingLongName = null; pendingLongLink = null
            paxPath = null; paxLink = null; paxSize = null
            return Entry(name, if (type == '\u0000') REGULAR else type, mode, size, link)
        }
    }

    private fun write(root: File, e: Entry) {
        val rel = sanitize(e.name) ?: run { skipBody(e.size); return }
        val target = File(root, rel)
        when (e.type) {
            DIRECTORY -> {
                if (!target.isDirectory && !target.mkdirs()) throw IOException("mkdir failed: $rel")
                chmod(target, e.mode or 0b111_000_000) // owner must keep rwx to fill it
                skipBody(e.size)
            }
            REGULAR, CONTIGUOUS -> {
                val parent = target.parentFile!!
                if (!parent.isDirectory) parent.mkdirs()
                requireInside(root, parent)
                if (SafeFs.isSymlink(target)) target.delete()
                FileOutputStream(target).use { copyBody(e.size, it) }
                skipPadding(e.size)
                chmod(target, e.mode or 0b110_000_000)
            }
            SYMLINK -> {
                val parent = target.parentFile!!
                if (!parent.isDirectory) parent.mkdirs()
                requireInside(root, parent)
                if (target.exists() || SafeFs.isSymlink(target)) target.delete()
                Os.symlink(e.linkName, target.path)
                skipBody(e.size)
            }
            HARDLINK -> {
                // Not produced by our build; copy the earlier file if it shows up anyway.
                val src = sanitize(e.linkName)?.let { File(root, it) }
                if (src != null && src.isFile) {
                    requireInside(root, target.parentFile!!)
                    src.copyTo(target, overwrite = true)
                    chmod(target, e.mode or 0b110_000_000)
                }
                skipBody(e.size)
            }
            else -> skipBody(e.size) // devices, fifos: not needed in the guest
        }
    }

    // --- low level -------------------------------------------------------------

    private fun ustarName(): String {
        val name = cString(header, 0, 100)
        val magic = cString(header, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(header, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun readPax(size: Long) {
        val text = readString(size)
        var i = 0
        while (i < text.length) {
            val space = text.indexOf(' ', i)
            if (space < 0) break
            val len = text.substring(i, space).toIntOrNull() ?: break
            val record = text.substring(space + 1, (i + len - 1).coerceAtMost(text.length))
            val eq = record.indexOf('=')
            if (eq > 0) {
                val value = record.substring(eq + 1)
                when (record.substring(0, eq)) {
                    "path" -> paxPath = value
                    "linkpath" -> paxLink = value
                    "size" -> paxSize = value.toLongOrNull()
                }
            }
            i += len
        }
    }

    private fun readString(size: Long): String {
        val bytes = ByteArray(size.toInt())
        readFully(bytes, bytes.size)
        skipPadding(size)
        return String(bytes, StandardCharsets.UTF_8).trimEnd('\u0000')
    }

    private fun copyBody(size: Long, out: FileOutputStream) {
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated archive")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun skipBody(size: Long) = discard(size + padding(size))

    private fun skipPadding(size: Long) = discard(padding(size))

    private fun discard(count: Long) {
        var left = count
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated archive")
            left -= n
        }
    }

    private fun readBlock(dest: ByteArray): Boolean {
        var off = 0
        while (off < BLOCK) {
            val n = input.read(dest, off, BLOCK - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("truncated header")
            }
            off += n
        }
        return true
    }

    private fun readFully(dest: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(dest, off, len - off)
            if (n < 0) throw EOFException("truncated archive")
            off += n
        }
    }

    companion object {
        private const val BLOCK = 512
        const val REGULAR = '0'
        const val HARDLINK = '1'
        const val SYMLINK = '2'
        const val DIRECTORY = '5'
        const val CONTIGUOUS = '7'
        const val GNU_LONGNAME = 'L'
        const val GNU_LONGLINK = 'K'
        const val PAX_HEADER = 'x'
        const val PAX_GLOBAL = 'g'

        private fun padding(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

        private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
            // GNU base-256 encoding for large values.
            if (b[off].toInt() and 0x80 != 0) {
                var v = 0L
                for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
                return v
            }
            var v = 0L
            for (i in off until off + len) {
                val c = b[i].toInt()
                if (c == 0 || c == ' '.code) { if (v != 0L) break else continue }
                if (c !in '0'.code..'7'.code) break
                v = v * 8 + (c - '0'.code)
            }
            return v
        }

        private fun cString(b: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && b[end] != 0.toByte()) end++
            return String(b, off, end - off, StandardCharsets.UTF_8)
        }

        /** Returns a safe relative path, or null for entries that must be skipped. */
        fun sanitize(name: String): String? {
            var n = name
            while (n.startsWith("./")) n = n.substring(2)
            n = n.trimEnd('/')
            if (n.isEmpty() || n == ".") return null
            if (n.startsWith("/")) return null
            if (n.split('/').any { it == ".." }) return null
            return n
        }

        private fun requireInside(root: File, dir: File) {
            val canonical = dir.canonicalFile
            if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
                throw IOException("archive entry escapes destination: ${dir.path}")
            }
        }

        private fun chmod(f: File, mode: Int) {
            try { Os.chmod(f.path, mode and 0b111_111_111) } catch (_: Exception) { }
        }
    }
}
