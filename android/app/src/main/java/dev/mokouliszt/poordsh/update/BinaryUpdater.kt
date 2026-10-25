package dev.mokouliszt.poordsh.update

import android.system.Os
import android.util.Base64
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.bridge.obj
import dev.mokouliszt.poordsh.runtime.Logs
import dev.mokouliszt.poordsh.runtime.RuntimePaths
import dev.mokouliszt.poordsh.runtime.SafeFs
import dev.mokouliszt.poordsh.runtime.TarExtractor
import dev.mokouliszt.poordsh.runtime.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Updates opencode on the phone from the npm registry, where the project
 * publishes its official builds. Downloads are checked against the registry's
 * sha512 integrity before anything is installed, and a new version only becomes
 * active once fully written (see [Tools]). It stays on the major version the app
 * was built with (the Tensorlake plugin targets opencode 1.x).
 */
class BinaryUpdater(
    private val paths: RuntimePaths,
    private val tools: Tools,
) : EngineUpdates.Updater {

    data class Candidate(val version: String, val tarball: String, val integrity: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun check(): JSONObject = withContext(Dispatchers.IO) {
        val out = JSONObject()
        for (tool in Tools.Tool.entries) {
            val current = tools.locate(tool)
            val entry = jsonOf("current" to current.version, "bundled" to current.bundled)
            try {
                val latest = latest(tool, current.version)
                entry.put("latest", latest?.version)
                entry.put("updateAvailable", latest != null && Tools.compareVersions(latest.version, current.version) > 0)
            } catch (e: Exception) {
                entry.put("error", e.message)
            }
            out.put(tool.id, entry)
        }
        out
    }

    /** Installs the newest version of [tool]; returns it, or null if already current. */
    override suspend fun apply(tool: Tools.Tool, onProgress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val current = tools.locate(tool)
        val target = latest(tool, current.version) ?: return@withContext null
        if (Tools.compareVersions(target.version, current.version) <= 0) return@withContext null

        val root = File(paths.tools, tool.id)
        val partial = File(root, "${target.version}.partial")
        val final = File(root, target.version)
        SafeFs.deleteTree(partial)
        partial.mkdirs()
        val binary = File(partial, tool.binary)

        Logs.app.add("updating ${tool.id} ${current.version} -> ${target.version}")
        val req = Request.Builder().url(target.tarball).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("download failed: ${res.code}")
            val body = res.body ?: throw IOException("empty download")
            val length = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-512")
            val counting = Counting(body.byteStream(), digest) { read ->
                if (length > 0) onProgress((read.toFloat() / length).coerceIn(0f, 0.99f))
            }
            val found = TarExtractor(GZIPInputStream(counting, 1 shl 16)).extractOne({ it.name == entryName(tool) }, binary)
            if (!found) throw IOException("${entryName(tool)} not found in package")
            val sink = ByteArray(1 shl 16)
            while (counting.read(sink) >= 0) { /* finish hashing */ }
            val actual = "sha512-" + Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
            if (actual != target.integrity) {
                SafeFs.deleteTree(partial)
                throw IOException("integrity check failed for ${tool.id} ${target.version}")
            }
        }
        Os.chmod(binary.path, 0b111_101_101)
        File(partial, ".complete").writeText(target.integrity)
        SafeFs.deleteTree(final)
        if (!partial.renameTo(final)) throw IOException("could not activate ${tool.id} ${target.version}")
        pruneOld(root, keep = target.version)
        onProgress(1f)
        target.version
    }

    private fun latest(tool: Tools.Tool, current: String): Candidate? = when (tool) {
        Tools.Tool.OPENCODE -> {
            val major = current.substringBefore('.')
            val latest = getJson("$REGISTRY/opencode-linux-arm64/latest")
            val latestVersion = latest.getString("version")
            if (latestVersion.substringBefore('.') == major) {
                latest.toCandidate(latestVersion)
            } else {
                // Newer major published: pick the newest release of our major instead.
                val doc = getJson("$REGISTRY/opencode-linux-arm64", abbreviated = true)
                val versions = doc.getJSONObject("versions")
                val best = versions.keys().asSequence()
                    .filter { it.substringBefore('.') == major && !it.contains('-') }
                    .maxWithOrNull { a, b -> Tools.compareVersions(a, b) }
                best?.let { versions.getJSONObject(it).toCandidate(it) }
            }
        }
    }

    private fun JSONObject.toCandidate(version: String): Candidate {
        val dist = obj("dist") ?: throw IOException("no dist for $version")
        return Candidate(version, dist.getString("tarball"), dist.getString("integrity"))
    }

    private fun getJson(url: String, abbreviated: Boolean = false): JSONObject {
        val req = Request.Builder().url(url).apply {
            if (abbreviated) header("Accept", "application/vnd.npm.install-v1+json")
        }.build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("$url: ${res.code}")
            return JSONObject(res.body?.string() ?: "{}")
        }
    }

    private fun pruneOld(root: File, keep: String) {
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.endsWith(".partial") }.orEmpty()
            .sortedWith { a, b -> Tools.compareVersions(b.name, a.name) }
        // Keep the new version and the one before it (manual rollback path).
        dirs.drop(2).filter { it.name != keep }.forEach { SafeFs.deleteTree(it) }
    }

    private class Counting(input: InputStream, private val digest: MessageDigest, private val onRead: (Long) -> Unit) :
        FilterInputStream(input) {
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

    companion object {
        private const val REGISTRY = "https://registry.npmjs.org"

        /** Path of the binary inside the project's linux-arm64 npm package. */
        fun entryName(tool: Tools.Tool) = when (tool) {
            Tools.Tool.OPENCODE -> "package/bin/opencode"
        }
    }
}
