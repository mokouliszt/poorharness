package dev.mokouliszt.poordsh.runtime

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Unpacks the Ubuntu rootfs shipped in the APK (assets/runtime/rootfs.tar.xz).
 *
 * Extraction goes to a staging directory and is swapped in only when complete,
 * so an interrupted install simply starts over next time. The archive's sha256
 * is checked while streaming. User data lives in [RuntimePaths.home], outside
 * the image, so replacing the image on an app update keeps it.
 */
class RootfsInstaller(private val context: Context, private val paths: RuntimePaths) {

    data class Manifest(
        val id: String,
        val archive: String,
        val sha256: String,
        val packedBytes: Long,
        val unpackedBytes: Long,
        val opencode: String,
        val tensorlakePlugin: String,
    )

    fun manifest(): Manifest {
        val text = context.assets.open("runtime/rootfs.json").bufferedReader().use { it.readText() }
        val j = JSONObject(text)
        return Manifest(
            id = j.getString("id"),
            archive = j.getString("archive"),
            sha256 = j.getString("sha256"),
            packedBytes = j.getLong("packedBytes"),
            unpackedBytes = j.getLong("unpackedBytes"),
            opencode = j.getString("opencode"),
            tensorlakePlugin = j.optString("tensorlakePlugin"),
        )
    }

    fun installedId(): String? = paths.marker.takeIf { it.isFile }?.readText()?.trim()

    fun isInstalled(m: Manifest): Boolean =
        installedId() == m.id && File(paths.rootfs, "usr/bin/env").exists()

    /** [onProgress] receives a 0..1 fraction. */
    suspend fun install(m: Manifest, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        paths.ensureDirs()
        val needed = m.unpackedBytes + 300L * MB
        val free = SafeFs.freeBytes(paths.base)
        if (free < needed) {
            throw IOException("Not enough storage: need ${needed / MB} MB, ${free / MB} MB free")
        }
        Logs.installer.add("unpacking ${m.archive} (${m.packedBytes / MB} MB packed, ${m.unpackedBytes / MB} MB unpacked)")
        SafeFs.deleteTree(paths.staging)
        paths.staging.mkdirs()

        val digest = MessageDigest.getInstance("SHA-256")
        var lastReport = 0L
        var entries = 0
        context.assets.open("runtime/${m.archive}", AssetManager.ACCESS_STREAMING).use { raw ->
            val counting = CountingStream(raw, digest) { read ->
                if (read - lastReport > 2L * MB) {
                    lastReport = read
                    onProgress((read.toFloat() / m.packedBytes).coerceIn(0f, 0.99f))
                }
            }
            val xz = XZInputStream(BufferedInputStream(counting, 256 * 1024))
            TarExtractor(xz).extractAll(paths.staging) { entries++ }
            // Drain anything after the tar end marker so the digest covers the whole file.
            val sink = ByteArray(64 * 1024)
            while (counting.read(sink) >= 0) { /* drain */ }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(m.sha256, ignoreCase = true)) {
            SafeFs.deleteTree(paths.staging)
            throw IOException("rootfs archive checksum mismatch")
        }
        Logs.installer.add("unpacked $entries entries")
        coroutineContext.ensureActive()

        // Swap in atomically (rename within the same directory).
        if (paths.rootfs.exists()) {
            SafeFs.deleteTree(paths.retired)
            if (!paths.rootfs.renameTo(paths.retired)) throw IOException("could not retire old rootfs")
        }
        if (!paths.staging.renameTo(paths.rootfs)) throw IOException("could not activate rootfs")
        paths.marker.writeText(m.id)
        SafeFs.deleteTree(paths.retired)
        seedHome()
        onProgress(1f)
        Logs.installer.add("rootfs ${m.id} ready")
    }

    /** Copies the image's /root skeleton into the persistent home the first time. */
    private fun seedHome() {
        val skel = File(paths.rootfs, "root")
        listOf(".bashrc", ".profile").forEach { name ->
            val src = File(skel, name)
            val dst = File(paths.home, name)
            if (src.isFile && !dst.exists()) src.copyTo(dst)
        }
        paths.emptyHost.mkdirs()
    }

    private class CountingStream(
        input: InputStream,
        private val digest: MessageDigest,
        private val onRead: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) { digest.update(b.toByte()); total++; onRead(total) }
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { digest.update(b, off, n); total += n; onRead(total) }
            return n
        }
    }

    companion object { private const val MB = 1024L * 1024 }
}
