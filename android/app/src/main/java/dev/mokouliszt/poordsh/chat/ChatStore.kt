package dev.mokouliszt.poordsh.chat

import dev.mokouliszt.poordsh.backend.Place
import dev.mokouliszt.poordsh.bridge.arr
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.bridge.objects
import dev.mokouliszt.poordsh.bridge.str
import dev.mokouliszt.poordsh.bridge.strings
import dev.mokouliszt.poordsh.runtime.Logs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Conversations owned by the app, one JSON file per chat under files/chats/.
 *
 * A chat is a run of segments; each segment is one engine session (an opencode
 * session, or a Codex thread from before 0.11). A chat that needs a new session
 * starts a new segment. After every turn the segment's messages are copied here,
 * so the list and the history open without starting an engine.
 */
class ChatStore(private val dir: File) {

    data class Segment(val engine: String, val sessionId: String, val model: String?)

    private val chats = HashMap<String, JSONObject>()
    private val bySession = HashMap<String, String>()

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("c_") && f.name.endsWith(".json") }?.forEach { f ->
            runCatching { JSONObject(f.readText()) }
                .onSuccess { c -> chats[c.getString("id")] = c; segments(c).forEach { bySession[it.sessionId] = c.getString("id") } }
                .onFailure { Logs.app.add("unreadable chat ${f.name}: ${it.message}") }
        }
    }

    @Synchronized
    fun list(): List<JSONObject> = chats.values.map { meta(it) }.sortedByDescending { it.optLong("updatedAt") }

    @Synchronized
    fun meta(id: String): JSONObject? = chats[id]?.let { meta(it) }

    @Synchronized
    fun exists(id: String) = id in chats

    @Synchronized
    fun create(title: String = "", createdAt: Long = now(), updatedAt: Long = createdAt): String {
        val id = "c_" + UUID.randomUUID().toString().replace("-", "").take(16)
        chats[id] = jsonOf(
            "id" to id, "title" to title, "createdAt" to createdAt, "updatedAt" to updatedAt,
            "segments" to JSONArray(), "messages" to JSONArray(),
        )
        save(id)
        return id
    }

    @Synchronized
    fun segments(id: String): List<Segment> = chats[id]?.let { segments(it) }.orEmpty()

    @Synchronized
    fun currentSegment(id: String): Segment? = segments(id).lastOrNull()

    @Synchronized
    fun chatOfSession(sessionId: String): String? = bySession[sessionId]

    @Synchronized
    fun segmentOf(sessionId: String): Segment? =
        chats[bySession[sessionId] ?: return null]?.let { c -> segments(c).firstOrNull { it.sessionId == sessionId } }

    @Synchronized
    fun isFirstSegment(sessionId: String): Boolean {
        val c = chats[bySession[sessionId] ?: return false] ?: return false
        return segments(c).firstOrNull()?.sessionId == sessionId
    }

    @Synchronized
    fun addSegment(id: String, seg: Segment) {
        val c = chats[id] ?: return
        c.getJSONArray("segments").put(jsonOf("engine" to seg.engine, "sessionId" to seg.sessionId, "model" to seg.model))
        bySession[seg.sessionId] = id
        save(id)
    }

    /** A copy of the chat's messages, in segment order. */
    @Synchronized
    fun messages(id: String): JSONArray = JSONArray(chats[id]?.optJSONArray("messages")?.toString() ?: "[]")

    /** Replaces one segment's messages with the engine's current copy. */
    @Synchronized
    fun replaceSegmentMessages(id: String, sessionId: String, incoming: JSONArray, touch: Boolean = true) {
        val c = chats[id] ?: return
        val segs = segments(c)
        if (segs.none { it.sessionId == sessionId }) return
        val existing = c.arr("messages").objects()
        val out = JSONArray()
        for (s in segs) {
            if (s.sessionId == sessionId) {
                incoming.objects().forEach { m ->
                    out.put(m.put("segment", s.sessionId).put("engine", s.engine).put("segmentModel", s.model))
                }
            } else {
                existing.filter { it.optString("segment") == s.sessionId }.forEach { out.put(it) }
            }
        }
        c.put("messages", out)
        if (touch) c.put("updatedAt", now())
        save(id)
    }

    /** The model (UI id, "<engine>:<model>") and effort of the last message sent in the chat. */
    @Synchronized
    fun setModel(id: String, model: String, effort: String?) {
        val c = chats[id] ?: return
        c.put("model", model).put("effort", effort ?: JSONObject.NULL)
        save(id)
    }

    /** Where the chat runs, fixed on its first message; chats from before 0.3 are cloud. */
    @Synchronized
    fun place(id: String): Place = Place.of(chats[id]?.optString("place"))

    @Synchronized
    fun setPlace(id: String, place: Place) {
        val c = chats[id] ?: return
        if (c.has("place")) return // fixed for the chat's life
        c.put("place", place.id)
        save(id)
    }

    @Synchronized
    fun title(id: String): String = chats[id]?.optString("title").orEmpty()

    @Synchronized
    fun setTitle(id: String, title: String) {
        chats[id]?.put("title", title.take(120)) ?: return
        save(id)
    }

    @Synchronized
    fun touch(id: String) {
        chats[id]?.put("updatedAt", now()) ?: return
        save(id)
    }

    /** Removes the chat and returns its segments so the engine copies can be deleted too. */
    @Synchronized
    fun delete(id: String): List<Segment> {
        val c = chats.remove(id) ?: return emptyList()
        File(dir, "$id.json").delete()
        val segs = segments(c)
        segs.forEach { bySession.remove(it.sessionId) }
        return segs
    }

    // --- outputs (docs/adr/outputs.md) -----------------------------------------

    /** What the chat has handed over from its outputs folder: name to signature. */
    @Synchronized
    fun outputSignatures(id: String): Map<String, String> {
        val o = chats[id]?.optJSONObject("outputs") ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    @Synchronized
    fun recordOutput(id: String, name: String, signature: String) {
        val c = chats[id] ?: return
        (c.optJSONObject("outputs") ?: JSONObject().also { c.put("outputs", it) }).put(name, signature)
        save(id)
    }

    /**
     * Files handed over at the end of the turn that ended with [messageId]: they show
     * as one `outputs` part of that message (a later delivery of the same name in the
     * same message replaces the earlier one). Returns the message as the UI gets it,
     * or null when the chat has no such message.
     */
    @Synchronized
    fun addDelivery(id: String, messageId: String, files: List<JSONObject>): JSONObject? {
        val c = chats[id] ?: return null
        val all = c.optJSONArray("deliveries") ?: JSONArray().also { c.put("deliveries", it) }
        val entry = all.objects().firstOrNull { it.optString("messageId") == messageId }
            ?: JSONObject().put("messageId", messageId).put("files", JSONArray()).also { all.put(it) }
        val names = files.map { it.optString("name") }.toSet()
        val kept = entry.arr("files").objects().filter { it.optString("name") !in names }
        entry.put("files", JSONArray(kept + files))
        save(id)
        return c.arr("messages").objects().firstOrNull { it.optString("id") == messageId }?.let { withOutputs(id, JSONObject(it.toString())) }
    }

    /** The chat's messages with their handed-over files, as the UI shows them. */
    @Synchronized
    fun messagesWithOutputs(id: String): JSONArray {
        val out = messages(id)
        val c = chats[id] ?: return out
        val list = out.objects()
        for (d in c.arr("deliveries").objects()) {
            val m = list.firstOrNull { it.optString("id") == d.optString("messageId") } ?: list.lastOrNull() ?: continue
            appendOutputs(m, d.arr("files"))
        }
        return out
    }

    /** [message] (one of the chat's, as an engine just sent it) with its handed-over files. */
    @Synchronized
    fun withOutputs(id: String, message: JSONObject): JSONObject {
        val d = chats[id]?.arr("deliveries")?.objects()?.firstOrNull { it.optString("messageId") == message.optString("id") } ?: return message
        appendOutputs(message, d.arr("files"))
        return message
    }

    private fun appendOutputs(message: JSONObject, files: JSONArray) {
        if (files.length() == 0) return
        val parts = message.optJSONArray("parts") ?: JSONArray().also { message.put("parts", it) }
        val existing = parts.objects().firstOrNull { it.optString("kind") == "outputs" }
        if (existing != null) {
            val names = files.objects().map { it.optString("name") }.toSet()
            existing.put("files", JSONArray(existing.arr("files").objects().filter { it.optString("name") !in names } + files.objects()))
        } else {
            parts.put(jsonOf("id" to "outputs:" + message.optString("id"), "kind" to "outputs", "files" to JSONArray(files.toString())))
        }
    }

    // --- engine-side bookkeeping ----------------------------------------------

    /** Engine sessions to delete the next time that engine runs. */
    @Synchronized
    fun addPendingDelete(engine: String, sessionId: String) {
        val all = pending()
        all.put(engine, (all.optJSONArray(engine) ?: JSONArray()).put(sessionId))
        File(dir, PENDING).writeText(all.toString())
    }

    @Synchronized
    fun takePendingDeletes(engine: String): List<String> {
        val all = pending()
        val ids = all.optJSONArray(engine)?.strings().orEmpty()
        if (ids.isNotEmpty()) {
            all.remove(engine)
            File(dir, PENDING).writeText(all.toString())
        }
        return ids
    }

    /** True the first time it is called for [engine] (for importing chats made before 0.1.2). */
    @Synchronized
    fun markImported(engine: String): Boolean {
        val marker = File(dir, ".imported-$engine")
        if (marker.exists()) return false
        marker.writeText(now().toString())
        return true
    }

    private fun pending(): JSONObject = File(dir, PENDING).takeIf { it.isFile }
        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() } ?: JSONObject()

    private fun segments(c: JSONObject): List<Segment> = c.arr("segments").objects().map {
        Segment(it.getString("engine"), it.getString("sessionId"), it.str("model"))
    }

    private fun meta(c: JSONObject): JSONObject = jsonOf(
        "id" to c.getString("id"),
        "title" to c.optString("title"),
        "createdAt" to c.optLong("createdAt"),
        "updatedAt" to c.optLong("updatedAt"),
        "engines" to segments(c).map { it.engine }.distinct(),
        "engine" to segments(c).lastOrNull()?.engine,
        "model" to c.str("model"),
        "effort" to c.str("effort"),
        "place" to c.optString("place").takeIf { it.isNotBlank() },
    )

    private fun save(id: String) {
        val c = chats[id] ?: return
        val file = File(dir, "$id.json")
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(c.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val PENDING = "pending-deletes.json"
    }
}
