package dev.mokouliszt.poordsh.modal

import dev.mokouliszt.poordsh.modal.ModalAgent.ModalException
import dev.mokouliszt.poordsh.runtime.LogBuffer
import dev.mokouliszt.poordsh.sandbox.CloudSandboxes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * One Modal sandbox per chat (docs/adr/modal-first.md). Modal sandboxes cannot
 * pause, and they are billed for as long as they run, so between uses a chat's
 * sandbox is a filesystem snapshot: taken when the chat lets go of it, and
 * [idleMs] later the sandbox stops; the next use starts one from the snapshot
 * (well under a second, measured November 2026). Modal itself stops a sandbox
 * left without commands for [CPU.idleSecs] or [GPU.idleSecs] and any after its
 * lifetime, so a phone that dies cannot leave one running.
 *
 * A chat with [gpuFor] on gets a GPU of [gpuType]; switching takes a snapshot
 * and starts the sandbox again with or without it. New chats start here while
 * this month's cost is under [NEW_CHAT_SHARE] of [budgetUsd]; past [LEAVE_SHARE]
 * chats move on, and a GPU is not started without [GPU_MIN_HOURS] of it left.
 */
class ModalSandboxes(
    private val agent: ModalClient,
    private val stateFile: File,
    private val log: LogBuffer,
    private val budgetUsd: () -> Double,
    private val gpuType: () -> String,
    private val gpuFor: (chatId: String) -> Boolean,
    private val idleMs: Long = IDLE_MS,
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val gpuHoldIdleMs: Long = GPU_HOLD_IDLE_MS,
) : CloudSandboxes {
    /** Too little of the month's budget is left to start this GPU. */
    class GpuBudget(val gpu: String) : IOException("modal_gpu_budget")

    data class Gpu(val id: String, val usdPerHour: Double)
    data class Usage(val month: String, val spentUsd: Double, val budgetUsd: Double, val spent: Boolean, val gpus: List<Gpu>)

    /** What a sandbox asks Modal for: CPU cores and MiB as (request, limit); billed at the larger of request and use. */
    data class Shape(val cpu: Pair<Double, Double>, val memoryMiB: Pair<Int, Int>, val lifetimeSecs: Int, val idleSecs: Int)

    private data class Up(val id: String, val gpu: String, val since: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val tokens = ConcurrentHashMap<String, MutableSet<String>>()
    private val idleJobs = ConcurrentHashMap<String, Job>()
    /** Sandbox name -> its running sandbox. */
    private val up = ConcurrentHashMap<String, Up>()
    /** Sandboxes [ensure] checked during the current hold, which tool calls use without asking again. */
    private val checked = ConcurrentHashMap.newKeySet<String>()
    private val heartbeats = ConcurrentHashMap<String, Job>()
    /** When each sandbox was last used for a tool call or a check. */
    private val used = ConcurrentHashMap<String, Long>()
    @Volatile private var usageCache: Pair<Long, Usage>? = null
    @Volatile private var rates: Map<String, Double> = emptyMap()

    init {
        agent.keepUp = { up.isNotEmpty() }
    }

    private fun isBusy(name: String) = tokens[name]?.isNotEmpty() == true

    private fun lock(name: String) = locks.getOrPut(name) { Mutex() }

    override fun acquire(chatId: String, token: String) {
        val name = nameFor(chatId)
        tokens.getOrPut(name) { ConcurrentHashMap.newKeySet() }.add(token)
        idleJobs.remove(name)?.cancel()
        used[name] = System.currentTimeMillis()
        heartbeats.computeIfAbsent(name) { scope.launch { heartbeat(name) } }
    }

    /**
     * While a chat holds its sandbox, runs an empty command now and then, so
     * Modal's idle stop ([Shape.idleSecs]) does not end it while the model thinks
     * or the user is asked something. A GPU left unused for [gpuHoldIdleMs] is
     * saved and stopped instead, as it costs by the minute; the next tool call
     * starts it again from the snapshot. Stops once the sandbox is let go.
     */
    private suspend fun heartbeat(name: String) {
        try {
            while (true) {
                delay(heartbeatMs)
                if (!isBusy(name)) break
                val u = up[name] ?: continue
                if (u.gpu.isNotEmpty() && System.currentTimeMillis() - (used[name] ?: 0L) > gpuHoldIdleMs) {
                    runCatching { lock(name).withLock { if (up[name] == u) stopLocked(name, snapshot = true) } }
                        .onSuccess { log.add("$name: GPU unused for a while; saved and stopped") }
                    continue
                }
                runCatching { agent.call("touch", JSONObject().put("sandbox", u.id), TOUCH_TIMEOUT_MS) }
            }
        } finally {
            heartbeats.remove(name)
        }
    }

    override fun release(chatId: String, token: String) {
        val name = nameFor(chatId)
        val set = tokens[name] ?: return
        if (!set.remove(token) || set.isNotEmpty()) return
        checked.remove(name)
        idleJobs.remove(name)?.cancel()
        idleJobs[name] = scope.launch {
            // A snapshot now, so a phone that dies before the stop keeps this turn's files.
            val saved = withContext(NonCancellable) {
                runCatching { checkpoint(name) }.onFailure { log.add("$name: snapshot failed: ${it.message}") }.isSuccess
            }
            delay(idleMs)
            if (isBusy(name)) return@launch
            // Without that snapshot, one more try before the stop.
            lock(name).withLock { if (!isBusy(name)) stopLocked(name, snapshot = !saved) }
        }
    }

    override suspend fun ensure(chatId: String): CloudSandboxes.Ready = withContext(Dispatchers.IO) {
        val name = nameFor(chatId)
        lock(name).withLock {
            try {
                ensureLocked(chatId, name)
            } catch (e: ModalException) {
                if (e.kind == "usage") markSpent()
                throw e
            }
        }
    }

    private suspend fun ensureLocked(chatId: String, name: String): CloudSandboxes.Ready {
        val want = if (gpuFor(chatId)) gpuType() else ""
        var u = up[name]?.takeIf { runCatching { agent.call("running", JSONObject().put("sandbox", it.id)).optBoolean("running") }.getOrDefault(false) }
        if (u == null) {
            up.remove(name)?.let { addCost(it) }
            u = find(name)
        }
        if (u != null && u.gpu != want) {
            log.add("$name: ${if (want.isEmpty()) "without a GPU" else "with $want"} from now on")
            stopLocked(name, snapshot = true)
            u = null
        }
        var fresh = false
        if (u == null) {
            if (want.isNotEmpty()) checkGpuBudget(want)
            val image = imageOf(name)
            val id = try {
                start(chatId, name, image, want)
            } catch (e: ModalException) {
                // A snapshot past its 30 days, or deleted: a new, empty sandbox.
                if (image == null || e.kind != "notfound") throw e
                log.add("$name: its snapshot is gone; starting empty")
                forgetImage(name)
                start(chatId, name, null, want)
            }
            fresh = image == null || imageOf(name) == null
            u = Up(id, want, System.currentTimeMillis())
            up[name] = u
            log.add("$name: ${if (fresh) "started" else "restored"}${if (want.isNotEmpty()) " with $want" else ""}")
        }
        checked.add(name)
        used[name] = System.currentTimeMillis()
        return CloudSandboxes.Ready(ModalSandbox(agent, u.id), fresh)
    }

    /** The chat's sandbox for one tool call: the one [ensure] checked during this hold, else [ensure]'s. */
    suspend fun sandboxFor(chatId: String): ModalSandbox {
        val name = nameFor(chatId)
        used[name] = System.currentTimeMillis()
        if (name in checked) up[name]?.let { return ModalSandbox(agent, it.id) }
        return ensure(chatId).sandbox as ModalSandbox
    }

    /** The chat's sandbox must be checked again before a tool call uses it. */
    fun forget(chatId: String) {
        checked.remove(nameFor(chatId))
    }

    private suspend fun find(name: String): Up? {
        val r = agent.call("find", JSONObject().put("name", name))
        val id = r.optString("sandbox").takeIf { !r.isNull("sandbox") && it.isNotBlank() } ?: return null
        return Up(id, r.optJSONObject("tags")?.optString("gpu").orEmpty(), System.currentTimeMillis())
    }

    private suspend fun start(chatId: String, name: String, image: String?, gpu: String): String {
        val shape = if (gpu.isEmpty()) CPU else GPU
        val args = JSONObject()
            .put("name", name)
            .put("tags", JSONObject().put(TAG, "1").put("chat", chatId).put("gpu", gpu))
            .put("timeout", shape.lifetimeSecs)
            .put("idleTimeout", shape.idleSecs)
            .put("workdir", WORKSPACE)
            .put("cpu", org.json.JSONArray(listOf(shape.cpu.first, shape.cpu.second)))
            .put("memory", org.json.JSONArray(listOf(shape.memoryMiB.first, shape.memoryMiB.second)))
        image?.let { args.put("image", it) }
        if (gpu.isNotEmpty()) args.put("gpu", gpu)
        return try {
            agent.call("start", args).getString("sandbox")
        } catch (e: ModalException) {
            // Started a moment ago by another call for the same chat.
            if (e.kind == "exists") find(name)?.id ?: throw e else throw e
        }
    }

    /** Snapshots the running sandbox and keeps the snapshot as the chat's; the one before goes. */
    private suspend fun checkpoint(name: String) = lock(name).withLock {
        val u = up[name] ?: return@withLock
        recordImage(name, agent.call("snapshot", JSONObject().put("sandbox", u.id).put("ttl", SNAPSHOT_TTL_SECS)).getString("image"))
    }

    private suspend fun stopLocked(name: String, snapshot: Boolean) {
        val u = up.remove(name) ?: return
        checked.remove(name)
        try {
            if (snapshot) recordImage(name, agent.call("snapshot", JSONObject().put("sandbox", u.id).put("ttl", SNAPSHOT_TTL_SECS)).getString("image"))
        } finally {
            runCatching { agent.call("terminate", JSONObject().put("sandbox", u.id)) }
                .onSuccess { log.add("$name idle; stopped") }
                .onFailure { log.add("$name: stop failed: ${it.message}") }
            addCost(u)
        }
    }

    override suspend fun exists(chatId: String): Boolean = withContext(Dispatchers.IO) {
        val name = nameFor(chatId)
        imageOf(name) != null || up[name] != null || find(name) != null
    }

    override suspend fun delete(chatId: String) = withContext(Dispatchers.IO) {
        val name = nameFor(chatId)
        idleJobs.remove(name)?.cancel()
        tokens.remove(name)
        lock(name).withLock {
            val u = up.remove(name) ?: find(name)
            u?.let {
                agent.call("terminate", JSONObject().put("sandbox", it.id))
                addCost(it)
            }
            imageOf(name)?.let { agent.call("deleteImage", JSONObject().put("image", it)) }
            forgetImage(name)
            checked.remove(name)
            if (u != null) log.add("$name: deleted")
        }
    }

    override fun suspendAllNow() {
        idleJobs.values.forEach { it.cancel() }
        idleJobs.clear()
        tokens.clear()
        for (name in up.keys.toList()) scope.launch { runCatching { lock(name).withLock { stopLocked(name, snapshot = true) } } }
    }

    /**
     * Sandboxes a killed app left running: Modal stops them after their idle
     * time, but their files would go with them. Snapshots and stops those of
     * chats on this phone ([ours]); another phone's are left alone.
     */
    fun recoverStrays(ours: (chatId: String) -> Boolean) {
        scope.launch {
            val list = runCatching { agent.call("list", JSONObject().put("tags", JSONObject().put(TAG, "1"))) }.getOrNull() ?: return@launch
            val items = list.optJSONArray("sandboxes") ?: return@launch
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val chat = o.optJSONObject("tags")?.optString("chat").orEmpty().ifBlank { continue }
                if (!ours(chat)) continue
                val name = nameFor(chat)
                if (isBusy(name) || up.containsKey(name)) continue
                runCatching {
                    lock(name).withLock {
                        up[name] = Up(o.getString("sandbox"), o.optJSONObject("tags")?.optString("gpu").orEmpty(), System.currentTimeMillis())
                        stopLocked(name, snapshot = true)
                    }
                }.onSuccess { log.add("$name: left running; saved and stopped") }
            }
        }
    }

    // --- moving a chat out --------------------------------------------------------------

    /**
     * Copies the chat's workspace out of Modal into [into]: zipped in the sandbox
     * (dependency folders left out) and unpacked here. False when it cannot be
     * started or the copy fails.
     */
    suspend fun downloadWorkspace(chatId: String, into: File): Boolean = withContext(Dispatchers.IO) {
        try {
            use(chatId, "move") { ready ->
                val zip = "/tmp/poordsh-move.zip"
                val r = ready.sandbox.run("python3", listOf("-c", ZIP, WORKSPACE, zip), timeoutSecs = 600)
                if (r.exitCode != 0) throw IOException("could not pack the workspace: ${r.stderr.trim().takeLast(200)}")
                val input = ready.sandbox.read(zip) ?: throw IOException("the packed workspace is missing")
                ZipInputStream(input.buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val out = File(into, e.name)
                        if (!out.canonicalPath.startsWith(into.canonicalPath + File.separator)) continue
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { z.copyTo(it) }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            log.add("${nameFor(chatId)}: workspace not copied out (${e.message})")
            false
        }
    }

    // --- the month's cost ------------------------------------------------------------------

    /** This month's cost (Modal's billing summary, or what this app counted when that lags behind), at most every few minutes. */
    suspend fun usage(refresh: Boolean = false): Usage = withContext(Dispatchers.IO) {
        val month = month()
        val now = System.currentTimeMillis()
        usageCache?.takeIf { !refresh && it.second.month == month && now - it.first < USAGE_TTL_MS }?.let { return@withContext it.second }
        val r = agent.call("usage")
        val metered = r.optString("meteredUsd").toDoubleOrNull() ?: 0.0
        val o = r.optJSONObject("rates") ?: JSONObject()
        rates = o.keys().asSequence().mapNotNull { k -> o.optString(k).toDoubleOrNull()?.let { k to it } }.toMap()
        Usage(month, maxOf(metered, estimate()), budgetUsd(), spentIn() == month, gpus()).also { usageCache = now to it }
    }

    /** GPUs Modal prices, cheapest first; the ids are what a sandbox asks for. */
    fun gpus(): List<Gpu> = rates.filterKeys { it.startsWith(GPU_RATE) }
        .map { (k, v) -> Gpu(gpuId(k.removePrefix(GPU_RATE)), v) }
        .sortedBy { it.usdPerHour }

    /**
     * This month's cost as last read from Modal, or the app's own count when that
     * is higher (Modal's lags); null when neither has been read this month.
     */
    private fun spentNow(): Double? {
        val u = usageCache?.second?.takeIf { it.month == month() } ?: return null
        return maxOf(u.spentUsd, estimate())
    }

    /** Whether new chats should start here. From what is known now; unknown counts as yes. */
    fun allowsNewChats(): Boolean {
        if (spentIn() == month()) return false
        val spent = spentNow() ?: return true
        return spent < budgetUsd() * NEW_CHAT_SHARE
    }

    /** Whether a chat here should move on before Modal refuses it, while its files can still be copied. */
    fun shouldLeave(): Boolean {
        if (spentIn() == month()) return true
        val spent = spentNow() ?: return false
        return spent >= budgetUsd() * LEAVE_SHARE
    }

    /** The user changed the budget: a refusal recorded this month no longer holds (they may have raised Modal's limit too). */
    fun budgetChanged() {
        editState { it.remove("spent") }
        usageCache = null
    }

    /** Modal refused work for usage: new chats go elsewhere until the month changes. */
    fun markSpent() {
        if (spentIn() == month()) return
        editState { it.put("spent", month()) }
        usageCache = null
        log.add("Modal refused work for usage; chats go elsewhere this month")
    }

    private fun checkGpuBudget(gpu: String) {
        val spent = spentNow() ?: return
        val rate = gpus().firstOrNull { it.id == gpu }?.usdPerHour ?: return
        if (budgetUsd() * LEAVE_SHARE - spent < rate * GPU_MIN_HOURS) throw GpuBudget(gpu)
    }

    /** What this app's sandboxes have cost this month by its own count: run time at the requested shape. */
    private fun estimate(): Double {
        val s = state()
        val counted = if (s.optString("month") == month()) s.optDouble("estimateUsd", 0.0) else 0.0
        val now = System.currentTimeMillis()
        return counted + up.values.sumOf { costOf(it, now) }
    }

    private fun addCost(u: Up) {
        val cost = costOf(u, System.currentTimeMillis())
        editState {
            val month = month()
            val before = if (it.optString("month") == month) it.optDouble("estimateUsd", 0.0) else 0.0
            it.put("month", month).put("estimateUsd", before + cost)
        }
    }

    private fun costOf(u: Up, now: Long): Double {
        if (rates.isEmpty()) return 0.0
        val shape = if (u.gpu.isEmpty()) CPU else GPU
        val hours = (now - u.since).coerceAtLeast(0) / 3_600_000.0
        val gpu = if (u.gpu.isEmpty()) 0.0 else gpus().firstOrNull { it.id == u.gpu }?.usdPerHour ?: 0.0
        val cpu = (rates[CPU_RATE] ?: 0.0) * shape.cpu.first
        val mem = (rates[MEM_RATE] ?: 0.0) * shape.memoryMiB.first / 1024.0
        return hours * (gpu + cpu + mem)
    }

    // --- state on the phone ----------------------------------------------------------------

    private fun imageOf(name: String): String? = state().optJSONObject("images")?.optString(name)?.takeIf { it.isNotBlank() }

    private fun recordImage(name: String, image: String) {
        val old = imageOf(name)
        editState { s -> (s.optJSONObject("images") ?: JSONObject().also { s.put("images", it) }).put(name, image) }
        if (old != null && old != image) scope.launch { runCatching { agent.call("deleteImage", JSONObject().put("image", old)) } }
    }

    private fun forgetImage(name: String) = editState { it.optJSONObject("images")?.remove(name) }

    private fun spentIn(): String? = state().optString("spent").takeIf { it.isNotBlank() }

    @Synchronized
    private fun state(): JSONObject = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }

    @Synchronized
    private fun editState(change: (JSONObject) -> Unit) {
        val o = state()
        change(o)
        stateFile.parentFile?.mkdirs()
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(stateFile)) {
            stateFile.delete()
            tmp.renameTo(stateFile)
        }
    }

    companion object {
        const val PREFIX = "poordsh-"
        /** Every sandbox this app starts carries it. */
        const val TAG = "poordsh"
        const val WORKSPACE = "/tmp/workspace"
        const val IDLE_MS = 60_000L
        /** Under the GPU shape's idle stop, so held sandboxes stay up. */
        const val HEARTBEAT_MS = 50_000L
        /** A held GPU sandbox no tool call has used for this long is saved and stopped. */
        const val GPU_HOLD_IDLE_MS = 10 * 60_000L
        private const val TOUCH_TIMEOUT_MS = 60_000L
        /** A CPU sandbox: a slice of a core that can burst to 2, up to 4 GiB; 6 h at most, stopped by Modal after 5 idle minutes. */
        val CPU = Shape(0.125 to 2.0, 256 to 4096, lifetimeSecs = 6 * 3600, idleSecs = 300)
        /** A GPU sandbox: more host to feed the GPU; 2 h at most, stopped by Modal after 2 idle minutes. */
        val GPU = Shape(0.5 to 4.0, 2048 to 16384, lifetimeSecs = 2 * 3600, idleSecs = 120)
        /** New chats stop starting here at this share of the month's budget. */
        const val NEW_CHAT_SHARE = 0.8
        /** Chats here move on at this share, while there is still budget to copy their files out. */
        const val LEAVE_SHARE = 0.95
        /** A GPU is started only with at least this much of it left in the budget. */
        const val GPU_MIN_HOURS = 0.5
        /** Modal's default budget: the Starter plan's monthly credits (November 2026). */
        const val DEFAULT_BUDGET_USD = 30.0
        private const val SNAPSHOT_TTL_SECS = 30 * 24 * 3600
        private const val USAGE_TTL_MS = 5 * 60_000L
        private const val GPU_RATE = "gpu_hour_cost_"
        private const val CPU_RATE = "cpu_hour_cost_sandbox"
        private const val MEM_RATE = "mem_gib_hour_cost_sandbox"

        /** Rate names whose GPU id is not the name upper-cased (checked against Modal, November 2026). */
        private val GPU_IDS = mapOf("rtx6000" to "RTX-PRO-6000")

        fun gpuId(rateName: String): String = GPU_IDS[rateName] ?: rateName.uppercase().replace('_', '-')

        fun month(): String = YearMonth.now(ZoneOffset.UTC).toString()

        /** Sandbox name for a chat: lowercase letters, digits and dashes, under 63 characters. */
        fun nameFor(chatId: String): String = (PREFIX + chatId.lowercase().replace(Regex("[^a-z0-9-]"), "-")).take(63)

        /** `<folder> <zip>`: the folder as one zip, without dependency folders (python3, which Modal's image has). */
        private val ZIP = """
            import os, sys, zipfile
            root, out = sys.argv[1], sys.argv[2]
            skip = {"node_modules", ".venv", "venv", "__pycache__"}
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
                for d, dirs, files in os.walk(root):
                    dirs[:] = [x for x in dirs if x not in skip]
                    for f in files:
                        p = os.path.join(d, f)
                        if os.path.isfile(p) and not os.path.islink(p):
                            z.write(p, os.path.relpath(p, root))
        """.trimIndent()
    }
}
