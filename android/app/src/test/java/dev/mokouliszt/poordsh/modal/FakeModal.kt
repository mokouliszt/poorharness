package dev.mokouliszt.poordsh.modal

import dev.mokouliszt.poordsh.modal.ModalAgent.ModalException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Modal as `poordsh-modal.py` answers, kept in memory: sandboxes by name with
 * tags, snapshots as image ids, the month's metered cost and the rates. Calls
 * are recorded, and [refuse] makes the next calls of an op fail as Modal would.
 */
class FakeModal(private val io: File) : ModalClient {
    class Box(val id: String, val name: String, val tags: JSONObject, val args: JSONObject, var running: Boolean = true)

    val sandboxes = mutableListOf<Box>()
    val images = mutableSetOf<String>()
    val deletedImages = mutableListOf<String>()
    val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    var meteredUsd = 0.0
    var rates = JSONObject()
        .put("cpu_hour_cost_sandbox", "0.1419").put("mem_gib_hour_cost_sandbox", "0.024")
        .put("gpu_hour_cost_t4", "0.59").put("gpu_hour_cost_a100_80gb", "2.50").put("gpu_hour_cost_rtx6000", "3.03")
        .put("endpoints_cpu", "9")
    /** op -> the kind its calls fail with. */
    val refuse = mutableMapOf<String, String>()
    /** What `bounds` answers (Modal's refusals, parsed); the least CPU a start is accepted with. */
    var bounds = JSONObject().put("cpuMin", 0.125).put("cpuMax", 64.0).put("memMin", 128).put("memMax", 344064)
    var acceptCpuFrom = 0.125
    /** What `touch` reads in each sandbox (CPU ns so far, memory bytes in use), by id. */
    val cpuNs = mutableMapOf<String, Long>()
    val memBytes = mutableMapOf<String, Long>()
    private var next = 0

    override var keepUp: () -> Boolean = { false }

    override fun ioFile(name: String): Pair<File, String> = File(io, name) to "/io/$name"

    fun ops(op: String) = calls.filter { it.first == op }.map { it.second }

    fun byName(name: String) = sandboxes.firstOrNull { it.name == name && it.running }

    override suspend fun call(op: String, args: JSONObject, timeoutMs: Long): JSONObject = answer(op, args)

    @Synchronized
    private fun answer(op: String, args: JSONObject): JSONObject {
        calls += op to JSONObject(args.toString())
        refuse[op]?.let { throw ModalException(it, "refused: $it") }
        fun box() = sandboxes.firstOrNull { it.id == args.getString("sandbox") } ?: throw ModalException("notfound", "no sandbox")
        return when (op) {
            "usage" -> JSONObject().put("meteredUsd", meteredUsd.toString()).put("billedUsd", meteredUsd.toString()).put("rates", rates)
            "find" -> byName(args.getString("name"))?.let { JSONObject().put("sandbox", it.id).put("tags", it.tags) }
                ?: JSONObject().put("sandbox", JSONObject.NULL)
            "start" -> {
                val name = args.getString("name")
                if (byName(name) != null) throw ModalException("exists", "already running")
                args.optString("image").takeIf { it.isNotEmpty() }?.let { if (it !in images) throw ModalException("notfound", "no image $it") }
                val cpu = args.optJSONArray("cpu")?.optDouble(0) ?: 0.125
                if (cpu < acceptCpuFrom) {
                    throw ModalException("invalid", "InvalidError: CPU request must be >= ${(acceptCpuFrom * 1000).toInt()} mCPU (got ${(cpu * 1000).toInt()})")
                }
                val b = Box("sb-${++next}", name, args.getJSONObject("tags"), args)
                sandboxes += b
                JSONObject().put("sandbox", b.id)
            }
            "running" -> JSONObject().put("running", box().running)
            "list" -> JSONObject().put("sandboxes", JSONArray(sandboxes.filter { it.running }.map { JSONObject().put("sandbox", it.id).put("tags", it.tags) }))
            "snapshot" -> {
                box()
                val id = "im-${++next}"
                images += id
                JSONObject().put("image", id)
            }
            "terminate" -> { box().running = false; JSONObject().put("ok", true) }
            "deleteImage" -> { images -= args.getString("image"); deletedImages += args.getString("image"); JSONObject().put("ok", true) }
            "touch" -> {
                val b = box()
                if (!b.running) throw ModalException("notfound", "already shut down")
                JSONObject().put("ok", true).put("cpuNs", cpuNs[b.id] ?: 0L).put("memBytes", memBytes[b.id] ?: 0L)
            }
            "bounds" -> JSONObject(bounds.toString())
            "read" -> { box(); JSONObject().put("missing", true) }
            "run" -> { box(); JSONObject().put("exitCode", 0).put("stdout", "").put("stderr", "") }
            "ls" -> { box(); JSONObject().put("entries", JSONArray()) }
            else -> throw ModalException("invalid", "unknown op $op")
        }
    }
}
