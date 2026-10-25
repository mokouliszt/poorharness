package dev.mokouliszt.poordsh.tensorlake

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Tensorlake's HTTP APIs, as documented in its OpenAPI spec
 * (docs.tensorlake.ai/api-reference/openapi.yaml): the management API on
 * api.tensorlake.ai and each sandbox's own proxy API (processes, files).
 * Plain JVM code (OkHttp + org.json), so it is tested on the build host.
 */
class TensorlakeApi(
    private val apiKey: () -> String?,
    private val http: OkHttpClient = defaultClient(),
    private val api: String = "https://api.tensorlake.ai",
) {
    /** The plan allows no more running sandboxes ("… reached its quota"). */
    class QuotaException(message: String) : IOException(message)
    class AuthException(message: String) : IOException(message)
    class ApiException(val code: Int, message: String) : IOException(message)

    data class Sandbox(
        val id: String,
        val name: String?,
        val status: String,
        /** Base for the proxy API, e.g. https://<id>.sandbox.tensorlake.ai */
        val url: String,
        val ingress: String,
        val ports: List<Int>,
    ) {
        val running get() = status == "running"
    }

    data class Entry(val name: String, val isDir: Boolean, val size: Long?, val modifiedAt: Long?)
    data class RunResult(val exitCode: Int?, val stdout: String, val stderr: String)

    // --- management -----------------------------------------------------------

    fun get(idOrName: String): Sandbox? {
        call(request("$api/sandboxes/${enc(idOrName)}").get()).use { res ->
            if (res.code == 404) return null
            return parse(json(res))
        }
    }

    fun list(status: String? = null): List<Sandbox> {
        val url = "$api/sandboxes?limit=100" + (status?.let { "&status=${enc(it)}" } ?: "")
        call(request(url).get()).use { res ->
            val items = json(res).optJSONArray("sandboxes") ?: JSONArray()
            return (0 until items.length()).mapNotNull { runCatching { parse(items.getJSONObject(it)) }.getOrNull() }
        }
    }

    fun create(
        name: String,
        cpus: Int = 1,
        memoryMb: Int = 1024,
        diskMb: Int = DISK_MB,
        timeoutSecs: Int = 600,
    ): Sandbox {
        val body = JSONObject()
            .put("name", name)
            .put("resources", JSONObject().put("cpus", cpus).put("memory_mb", memoryMb).put("disk_mb", diskMb))
            .put("timeout_secs", timeoutSecs)
        call(request("$api/sandboxes").post(body.toString().toRequestBody(JSON))).use { res -> json(res) }
        return get(name) ?: throw ApiException(404, "sandbox $name vanished after create")
    }

    fun suspend(idOrName: String) = action(idOrName, "suspend")

    fun resume(idOrName: String) = action(idOrName, "resume")

    fun delete(idOrName: String) {
        call(request("$api/sandboxes/${enc(idOrName)}").delete()).use { res ->
            if (res.code != 404) json(res)
        }
    }

    private fun action(idOrName: String, verb: String) {
        call(request("$api/sandboxes/${enc(idOrName)}/$verb").post(EMPTY)).use { json(it) }
    }

    // --- sandbox proxy ----------------------------------------------------------

    /** Runs a command to completion (`POST /api/v1/processes/run`, an SSE stream). */
    fun run(
        sb: Sandbox,
        command: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
        timeoutSecs: Int = 120,
    ): RunResult {
        val body = JSONObject().put("command", command).put("args", JSONArray(args)).put("timeout", timeoutSecs)
        if (env.isNotEmpty()) body.put("env", JSONObject(env))
        cwd?.let { body.put("working_dir", it) }
        val client = http.newBuilder().readTimeout((timeoutSecs + 30).toLong(), TimeUnit.SECONDS).build()
        val out = StringBuilder()
        val err = StringBuilder()
        var exit: Int? = null
        client.newCall(request("${sb.url}/api/v1/processes/run").post(body.toString().toRequestBody(JSON)).build())
            .execute().use { res ->
                if (!res.isSuccessful) throw failure(res)
                val source = res.body!!.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    val ev = parseEvent(line) ?: continue
                    when {
                        ev.has("line") -> (if (ev.optString("stream") == "stderr") err else out).append(ev.optString("line")).append('\n')
                        ev.has("exit_code") || ev.has("signal") -> exit = if (ev.isNull("exit_code")) null else ev.optInt("exit_code")
                    }
                }
            }
        return RunResult(exit, out.toString(), err.toString())
    }

    /** Starts a long-lived process managed by the sandbox daemon; returns its pid. */
    fun start(
        sb: Sandbox,
        command: String,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
    ): Int {
        val body = JSONObject().put("command", command).put("args", JSONArray(args))
            .put("stdin_mode", "closed").put("stdout_mode", "discard").put("stderr_mode", "capture")
        if (env.isNotEmpty()) body.put("env", JSONObject(env))
        cwd?.let { body.put("working_dir", it) }
        call(request("${sb.url}/api/v1/processes").post(body.toString().toRequestBody(JSON))).use { res ->
            return json(res).getInt("pid")
        }
    }

    fun listDir(sb: Sandbox, path: String): List<Entry> {
        call(request("${sb.url}/api/v1/files/list?path=${enc(path)}").get()).use { res ->
            val entries = json(res).optJSONArray("entries") ?: JSONArray()
            return (0 until entries.length()).map {
                val e = entries.getJSONObject(it)
                Entry(
                    e.getString("name"),
                    e.optBoolean("is_dir"),
                    if (e.isNull("size")) null else e.optLong("size"),
                    if (e.isNull("modified_at")) null else e.optLong("modified_at"),
                )
            }
        }
    }

    /** The file's bytes as a stream; the caller closes it. Null when the file does not exist. */
    fun read(sb: Sandbox, path: String): InputStream? {
        val res = call(request("${sb.url}/api/v1/files?path=${enc(path)}").get())
        if (res.code == 404) {
            res.close()
            return null
        }
        if (!res.isSuccessful) res.use { throw failure(it) }
        return res.body!!.byteStream()
    }

    /**
     * Streams a local file to [path] (missing parent folders are created), so its
     * size is not limited by memory. [progress] gets the bytes sent so far.
     */
    fun upload(sb: Sandbox, path: String, file: File, progress: ((Long) -> Unit)? = null) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                file.source().use { src ->
                    var sent = 0L
                    while (true) {
                        val n = src.read(sink.buffer, 256 * 1024L)
                        if (n < 0) break
                        sink.emit()
                        sent += n
                        progress?.invoke(sent)
                    }
                }
            }
        }
        call(request("${sb.url}/api/v1/files?path=${enc(path)}").put(body)).use { res ->
            if (!res.isSuccessful) throw failure(res)
        }
    }

    /**
     * Free bytes on the filesystem that holds (or will hold) [path], from `df` on
     * its nearest existing folder; null if it cannot be told.
     */
    fun freeBytes(sb: Sandbox, path: String): Long? {
        val script = "p=\"\$1\"; while [ ! -e \"\$p\" ]; do p=\$(dirname \"\$p\"); done; df -B1 --output=avail \"\$p\""
        val r = run(sb, "sh", listOf("-c", script, "df", path), timeoutSecs = 30)
        return r.stdout.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.toLongOrNull()
    }

    fun write(sb: Sandbox, path: String, bytes: ByteArray) {
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        call(request("${sb.url}/api/v1/files?path=${enc(path)}").put(body)).use { res ->
            if (!res.isSuccessful) throw failure(res)
        }
    }

    // --- plumbing --------------------------------------------------------------

    private fun authorize(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", "Bearer ${apiKey() ?: throw AuthException("no Tensorlake API key")}")

    private fun request(url: String) = authorize(Request.Builder().url(url))

    private fun call(builder: Request.Builder): Response = http.newCall(builder.build()).execute()

    private fun json(res: Response): JSONObject {
        if (!res.isSuccessful) throw failure(res)
        val text = res.body?.string().orEmpty()
        return if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() }
    }

    private fun failure(res: Response): IOException {
        val text = runCatching { res.body?.string() }.getOrNull().orEmpty().take(500)
        val message = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: text
        return when {
            res.code == 401 || res.code == 403 -> AuthException("Tensorlake ${res.code}: $message")
            isQuota(res.code, message) -> QuotaException(message)
            else -> ApiException(res.code, "Tensorlake ${res.code}: $message")
        }
    }

    private fun parse(o: JSONObject): Sandbox {
        val id = o.getString("id")
        val ingress = o.optString("ingress_endpoint").takeIf { it.isNotBlank() } ?: DEFAULT_INGRESS
        val ports = o.optJSONArray("exposed_ports")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty()
        return Sandbox(
            id = id,
            name = o.optString("name").takeIf { it.isNotBlank() && it != "null" },
            status = o.optString("status"),
            url = o.optString("sandbox_url").takeIf { it.isNotBlank() && it != "null" } ?: "https://$id.${URI(ingress).host}",
            ingress = ingress,
            ports = ports,
        )
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)
        const val DEFAULT_INGRESS = "https://sandbox.tensorlake.ai"
        /** Disk of a chat sandbox (the free plan's limit). */
        const val DISK_MB = 10240

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        /** The free plan answers "1 sandboxes are running and the project has reached its quota." */
        fun isQuota(code: Int, message: String): Boolean =
            (code == 400 || code == 429) && message.contains("quota", ignoreCase = true)

        /** One `data:` line of the run stream, or null for anything else. */
        fun parseEvent(line: String): JSONObject? {
            if (!line.startsWith("data:")) return null
            return runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull()
        }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
