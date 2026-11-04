package dev.mokouliszt.poordsh.upstash

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
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
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Upstash Box's HTTP API. Upstash documents only its SDKs, so the calls here
 * follow what `@upstash/box` (0.7.10) sends, checked against the service in
 * November 2026 (docs/adr/box-first.md). Every route but `GET /v2/box/{name}`
 * takes the box's id, not its name. Plain JVM code (OkHttp + org.json), tested
 * on the build host.
 */
class BoxApi(
    private val apiKey: () -> String?,
    private val http: OkHttpClient = defaultClient(),
    private val base: String = BASE_URL,
) {
    class AuthException(message: String) : IOException(message)

    /** The plan's limit on boxes (paused ones count): "Box limit reached. Maximum 10 boxes allowed…". */
    class BoxCountLimit(message: String) : IOException(message)

    /** Box refused work because the account's usage allowance is spent (free CPU hours, say). */
    class UsageLimit(message: String) : IOException(message)

    class ApiException(val code: Int, message: String) : IOException(message)

    data class Box(val id: String, val name: String?, val status: String, val labels: List<String>, val lastActivityAt: Long?)
    data class Exec(val exitCode: Int, val stdout: String, val stderr: String)
    data class Entry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modifiedAt: Long?)
    data class Snapshot(val id: String, val status: String, val sizeBytes: Long?)

    // --- boxes -------------------------------------------------------------------

    fun list(label: String? = null): List<Box> {
        val url = "$base/v2/box" + (label?.let { "?label=${enc(it)}" } ?: "")
        call(request(url).get()).use { res ->
            val text = body(res)
            val items = runCatching { JSONArray(text) }.getOrElse { JSONArray() }
            return (0 until items.length()).mapNotNull { runCatching { parse(items.getJSONObject(it)) }.getOrNull() }
        }
    }

    /** By id or name; null when there is none. */
    fun get(idOrName: String): Box? {
        call(request("$base/v2/box/${enc(idOrName)}").get()).use { res ->
            if (res.code == 404) return null
            return parse(json(res))
        }
    }

    /** Starts creating a box; it is ready when [get] no longer says "creating". */
    fun create(name: String, labels: List<String>): Box {
        val body = JSONObject().put("name", name).put("labels", JSONArray(labels))
        call(request("$base/v2/box").post(body.toString().toRequestBody(JSON))).use { return parse(json(it)) }
    }

    fun fromSnapshot(snapshotId: String, name: String, labels: List<String>): Box {
        val body = JSONObject().put("snapshot_id", snapshotId).put("name", name).put("labels", JSONArray(labels))
        call(request("$base/v2/box/from-snapshot").post(body.toString().toRequestBody(JSON))).use { return parse(json(it)) }
    }

    fun delete(id: String) {
        call(request("$base/v2/box/${enc(id)}").delete()).use { res -> if (res.code != 404) json(res) }
    }

    /** Releases compute; files stay, processes end. Any later call resumes it. */
    fun pause(id: String) {
        call(request("$base/v2/box/${enc(id)}/pause").post(EMPTY)).use { json(it) }
    }

    // --- processes -----------------------------------------------------------------

    /**
     * Runs [argv] to completion. The gateway closes a call after 300 s, so
     * [BoxRunner] keeps each call shorter; [readSecs] bounds the wait here.
     */
    fun exec(id: String, argv: List<String>, readSecs: Int = 120): Exec {
        val client = http.newBuilder().readTimeout((readSecs + 30).toLong(), TimeUnit.SECONDS).build()
        val body = JSONObject().put("command", JSONArray(argv))
        val req = request("$base/v2/box/${enc(id)}/exec").post(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { res ->
            val o = json(res)
            return Exec(o.optInt("exit_code", -1), o.optString("output"), o.optString("error"))
        }
    }

    // --- files ---------------------------------------------------------------------

    /** The file's bytes as a stream; the caller closes it. Null when it does not exist. */
    fun read(id: String, path: String): InputStream? {
        val res = call(request("$base/v2/box/${enc(id)}/files/download?folder=${enc(path)}").get())
        if (res.code == 404) {
            res.close()
            return null
        }
        if (!res.isSuccessful) res.use { throw failure(it) }
        return res.body!!.byteStream()
    }

    /** Writes [bytes] to [path]; missing folders are created. */
    fun write(id: String, path: String, bytes: ByteArray) {
        val body = JSONObject().put("path", path).put("content", Base64.getEncoder().encodeToString(bytes)).put("encoding", "base64")
        call(request("$base/v2/box/${enc(id)}/files/write").post(body.toString().toRequestBody(JSON))).use { json(it) }
    }

    /** Streams a local file to [path] (missing folders are created). [progress] gets the bytes sent so far. */
    fun upload(id: String, path: String, file: File, progress: ((Long) -> Unit)? = null) {
        val content = object : RequestBody() {
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
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("paths", path)
            .addFormDataPart("files", path.substringAfterLast('/'), content)
            .build()
        val client = http.newBuilder().writeTimeout(10, TimeUnit.MINUTES).build()
        client.newCall(request("$base/v2/box/${enc(id)}/files/upload").post(form).build()).execute().use { json(it) }
    }

    /** The folder's entries; empty when it does not exist (Box answers `"files": null`). */
    fun listDir(id: String, path: String): List<Entry> {
        call(request("$base/v2/box/${enc(id)}/files/list?folder=${enc(path)}").get()).use { res ->
            if (res.code == 404) return emptyList()
            val files = json(res).optJSONArray("files") ?: return emptyList()
            return (0 until files.length()).map {
                val f = files.getJSONObject(it)
                Entry(
                    f.getString("name"), f.optString("path"), f.optBoolean("is_dir"), f.optLong("size"),
                    runCatching { java.time.Instant.parse(f.getString("mod_time")).toEpochMilli() }.getOrNull(),
                )
            }
        }
    }

    // --- snapshots -----------------------------------------------------------------

    /** Starts a snapshot of the whole filesystem; it is ready when [snapshots] says so. */
    fun snapshot(id: String, name: String): Snapshot {
        val body = JSONObject().put("name", name)
        call(request("$base/v2/box/${enc(id)}/snapshots").post(body.toString().toRequestBody(JSON))).use { return parseSnapshot(json(it)) }
    }

    fun snapshots(id: String): List<Snapshot> {
        call(request("$base/v2/box/${enc(id)}/snapshots").get()).use { res ->
            val a = json(res).optJSONArray("snapshots") ?: return emptyList()
            return (0 until a.length()).map { parseSnapshot(a.getJSONObject(it)) }
        }
    }

    /** Works after the box itself is gone. */
    fun deleteSnapshot(boxId: String, snapshotId: String) {
        call(request("$base/v2/box/${enc(boxId)}/snapshots/${enc(snapshotId)}").delete()).use { res -> if (res.code != 404) json(res) }
    }

    // --- account -------------------------------------------------------------------

    /**
     * CPU time used by the account's boxes in [month] ("2026-11"), deleted boxes
     * included, in nanoseconds. Box updates it when a box pauses and from time to time.
     */
    fun cpuNs(month: String): Long {
        call(request("$base/v2/box/billing?month=${enc(month)}").get()).use { res ->
            val boxes = json(res).optJSONArray("box_billing") ?: return 0
            return (0 until boxes.length()).sumOf { boxes.getJSONObject(it).optLong("cpu_ns") }
        }
    }

    // --- plumbing ------------------------------------------------------------------

    private fun request(url: String): Request.Builder =
        Request.Builder().url(url).header("X-Box-Api-Key", apiKey() ?: throw AuthException("no Upstash Box API key"))

    private fun call(builder: Request.Builder): Response = http.newCall(builder.build()).execute()

    private fun body(res: Response): String {
        if (!res.isSuccessful) throw failure(res)
        return res.body?.string().orEmpty()
    }

    private fun json(res: Response): JSONObject {
        val text = body(res)
        return if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() }
    }

    private fun failure(res: Response): IOException {
        val text = runCatching { res.body?.string() }.getOrNull().orEmpty().take(500)
        val message = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: text
        return classify(res.code, message)
    }

    private fun parse(o: JSONObject) = Box(
        id = o.getString("id"),
        name = o.optString("name").takeIf { it.isNotBlank() && it != "null" },
        status = o.optString("status"),
        labels = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        lastActivityAt = if (o.has("last_activity_at") && !o.isNull("last_activity_at")) o.optLong("last_activity_at") else null,
    )

    private fun parseSnapshot(o: JSONObject) = Snapshot(
        o.getString("id"), o.optString("status"), if (o.has("size_bytes")) o.optLong("size_bytes") else null,
    )

    companion object {
        /** Boxes run in AWS us-east-1 only (November 2026); the SDKs default to this host. */
        const val BASE_URL = "https://us-east-1.box.upstash.com"
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()

        /**
         * Box's refusals as exceptions. How Box words a spent CPU allowance is not
         * documented and was not observed (the test account's allowance was kept),
         * so any 402 or 429, or a refusal that talks about usage, counts as one.
         */
        fun classify(code: Int, message: String): IOException {
            val m = message.lowercase()
            return when {
                code == 401 -> AuthException("Upstash Box $code: $message")
                code == 409 && ("box limit" in m || "boxes allowed" in m) -> BoxCountLimit(message)
                code == 402 || code == 429 -> UsageLimit(message)
                code in 400..403 && USAGE_WORDS.any { it in m } -> UsageLimit(message)
                else -> ApiException(code, "Upstash Box $code: $message")
            }
        }

        private val USAGE_WORDS = listOf("cpu", "usage", "quota", "credit", "exceeded", "limit", "upgrade", "payment")

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
