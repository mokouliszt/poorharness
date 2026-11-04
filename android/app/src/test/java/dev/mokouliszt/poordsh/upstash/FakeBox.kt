package dev.mokouliszt.poordsh.upstash

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Upstash Box as BoxApi sees it, on a MockWebServer, with what was checked against
 * the real service (docs/adr/box-first.md): ids differ from names, a box is
 * "creating" until looked at, at most [limit] boxes (paused ones count), snapshots
 * are "creating" until listed. Commands answer exit 0, or run on the build host
 * when [runLocally] is set. Files live in [files] per box id.
 */
class FakeBox(var limit: Int = 10) : Dispatcher() {
    data class Box(val id: String, val name: String, var status: String, val labels: List<String>, var lastActivity: Long)
    data class Snapshot(val id: String, val boxId: String, var status: String, val files: Map<String, ByteArray>)

    val server = MockWebServer().also { it.dispatcher = this }
    val boxes = ConcurrentHashMap<String, Box>()
    val snapshots = ConcurrentHashMap<String, Snapshot>()
    val files = ConcurrentHashMap<String, MutableMap<String, ByteArray>>()
    val execs = mutableListOf<List<String>>()
    @Volatile var runLocally = false
    /** For [runLocally]: the runner's state folder (POORDSH_STATE), so tests never touch the real /tmp/.poordsh. */
    @Volatile var stateDir: java.io.File? = null
    /** Refuse commands as a spent allowance would (the real answer is not known). */
    @Volatile var refuseUsage = false
    @Volatile var cpuNs = 0L
    private var seq = 0

    fun api(key: String = "box_test") = BoxApi({ key }, base = server.url("/").toString().trimEnd('/'))

    fun byName(name: String) = boxes.values.firstOrNull { it.name == name }

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
        if (request.getHeader("X-Box-Api-Key") != "box_test") return err(401, "Invalid box API key")
        val url = request.requestUrl!!
        val parts = url.pathSegments
        val body = request.body.readUtf8()
        val json = runCatching { JSONObject(body) }.getOrNull()
        return when {
            parts == listOf("v2", "box") && request.method == "GET" -> {
                val label = url.queryParameter("label")
                ok(JSONArray(boxes.values.filter { label == null || label in it.labels }.map { boxJson(it) }))
            }
            parts == listOf("v2", "box") && request.method == "POST" -> create(json!!.getString("name"), labels(json), emptyMap())
            parts == listOf("v2", "box", "from-snapshot") -> {
                val snap = snapshots[json!!.getString("snapshot_id")] ?: return err(404, "Snapshot not found")
                create(json.getString("name"), labels(json), snap.files)
            }
            parts == listOf("v2", "box", "billing") -> ok(JSONObject().put("box_billing", JSONArray().put(JSONObject().put("cpu_ns", cpuNs))))
            parts.size == 3 && request.method == "GET" -> {
                val b = boxes[parts[2]] ?: byName(parts[2]) ?: return err(404, "Box not found")
                // Ready by the first look (the real one took about 3 s).
                if (b.status == "creating") b.status = "idle"
                ok(boxJson(b))
            }
            parts.size == 3 && request.method == "DELETE" -> {
                boxes.remove(parts[2]) ?: return err(404, "Box not found")
                ok(JSONObject().put("message", "box deleted"))
            }
            else -> perBox(request, parts, url, json)
        }
    }

    private fun perBox(request: RecordedRequest, parts: List<String>, url: okhttp3.HttpUrl, json: JSONObject?): MockResponse {
        val id = parts[2]
        // Snapshots stay reachable after their box is deleted.
        if (parts.size == 5 && parts[3] == "snapshots" && request.method == "DELETE") {
            snapshots.remove(parts[4])
            return ok(JSONObject().put("status", "deleted"))
        }
        val b = boxes[id] ?: return err(404, "Box not found")
        val box = files.getOrPut(id) { ConcurrentHashMap() }
        return when (parts.drop(3).joinToString("/")) {
            "pause" -> {
                b.status = "paused"
                ok(JSONObject().put("message", "box paused"))
            }
            "exec" -> {
                if (refuseUsage) return err(402, "Monthly CPU limit reached")
                b.status = "idle"
                b.lastActivity = System.currentTimeMillis() / 1000
                val argv = json!!.getJSONArray("command").let { a -> (0 until a.length()).map { a.getString(it) } }
                execs += argv
                if (!runLocally) return ok(JSONObject().put("exit_code", 0).put("output", "").put("error", ""))
                // Each call in a session of its own, as Box's exec does, so the runner's
                // watchdog can only ever end what these calls started.
                val p = ProcessBuilder(listOf("setsid", "-w") + argv)
                    .apply { stateDir?.let { environment()["POORDSH_STATE"] = it.path } }
                    .start()
                val out = p.inputStream.bufferedReader().readText()
                val errText = p.errorStream.bufferedReader().readText()
                ok(JSONObject().put("exit_code", p.waitFor()).put("output", out).put("error", errText))
            }
            "snapshots" -> if (request.method == "POST") {
                val s = Snapshot("snap-${++seq}", id, "creating", box.toMap())
                snapshots[s.id] = s
                ok(snapJson(s))
            } else {
                val list = snapshots.values.filter { it.boxId == id }
                val out = JSONObject().put("snapshots", JSONArray(list.map { snapJson(it) }))
                list.forEach { it.status = "ready" }
                ok(out)
            }
            "files/write" -> {
                box[json!!.getString("path")] = Base64.getDecoder().decode(json.getString("content"))
                ok(JSONObject().put("success", true))
            }
            "files/download" -> box[url.queryParameter("folder")!!]?.let { MockResponse().setBody(Buffer().write(it)) }
                ?: err(404, "file not found")
            "files/list" -> {
                val folder = url.queryParameter("folder")!!.trimEnd('/') + "/"
                val children = box.keys.filter { it.startsWith(folder) }.map { it.removePrefix(folder).substringBefore('/') }.distinct()
                if (children.isEmpty()) return ok(JSONObject().put("files", JSONObject.NULL))
                ok(
                    JSONObject().put(
                        "files",
                        JSONArray(
                            children.map { c ->
                                val path = folder + c
                                val isDir = path !in box
                                JSONObject().put("name", c).put("path", path).put("is_dir", isDir)
                                    .put("size", box[path]?.size ?: 0).put("mod_time", "2026-11-04T13:19:42Z")
                            },
                        ),
                    ),
                )
            }
            else -> err(404, "no route")
        }
    }

    private fun create(name: String, labels: List<String>, from: Map<String, ByteArray>): MockResponse {
        if (boxes.values.any { it.name == name }) return err(409, "Box name \"$name\" is already in use")
        if (boxes.size >= limit) return err(409, "Box limit reached. Maximum $limit boxes allowed in the Free plan. Please upgrade by adding a payment method")
        val b = Box("box-${++seq}", name, "creating", labels, System.currentTimeMillis() / 1000)
        boxes[b.id] = b
        files[b.id] = ConcurrentHashMap(from)
        return MockResponse().setResponseCode(201).setBody(boxJson(b).toString())
    }

    private fun labels(o: JSONObject) = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    private fun boxJson(b: Box) = JSONObject().put("id", b.id).put("name", b.name).put("status", b.status)
        .put("labels", JSONArray(b.labels)).put("last_activity_at", b.lastActivity)

    private fun snapJson(s: Snapshot) = JSONObject().put("id", s.id).put("box_id", s.boxId).put("status", s.status).put("size_bytes", 10)

    private fun ok(o: Any) = MockResponse().setBody(o.toString())

    private fun err(code: Int, message: String) = MockResponse().setResponseCode(code).setBody(JSONObject().put("error", message).toString())
}
