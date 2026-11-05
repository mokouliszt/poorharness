package dev.mokouliszt.poordsh.upstash

import dev.mokouliszt.poordsh.runtime.LogBuffer
import dev.mokouliszt.poordsh.sandbox.CloudSandboxes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/**
 * One Upstash box per chat (docs/adr/box-first.md): named [nameFor], labelled
 * [LABEL], created on first use, paused [idleMs] after its last use (files stay,
 * processes end; any later call wakes it), deleted with the chat.
 *
 * The free plan allows ten boxes, paused ones included. When Box refuses a new
 * one, this app's least recently used idle box is snapshotted and deleted
 * ("archived"); that chat's box comes back from its snapshot on its next use.
 * Boxes this app did not make are never touched.
 *
 * The free plan also gives five CPU hours a month (CPU actually used, so a box
 * waiting for a model costs nothing). [allowsNewChats] says whether a new chat
 * should start here; [markSpent] records that Box refused work for usage.
 */
class BoxSandboxes(
    private val api: BoxApi,
    private val stateFile: File,
    private val log: LogBuffer,
    private val idleMs: Long = IDLE_PAUSE_MS,
    private val heartbeatMs: Long = HEARTBEAT_MS,
) : CloudSandboxes {
    /** Ten boxes exist and none of this app's is idle to archive. */
    class Full : IOException("box_full")

    data class Usage(val month: String, val usedHours: Double, val freeHours: Double, val spent: Boolean)

    private data class Archived(val box: String, val snapshot: String)

    /** Told when a step is slow enough to show (making room: "room"); null when it is over. */
    @Volatile var onStage: ((chatId: String, stage: String?) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val tokens = ConcurrentHashMap<String, MutableSet<String>>()
    private val idleJobs = ConcurrentHashMap<String, Job>()
    /** Box name -> box id, as last seen. */
    private val ids = ConcurrentHashMap<String, String>()
    /** Box name -> when this process last used it. */
    private val used = ConcurrentHashMap<String, Long>()
    /** Boxes checked awake by [ensure] since they last paused: [sandboxFor] uses them without asking Box again. */
    private val awake = ConcurrentHashMap.newKeySet<String>()
    private val heartbeats = ConcurrentHashMap<String, Job>()
    @Volatile private var usageCache: Pair<Long, Usage>? = null

    private fun isBusy(name: String) = tokens[name]?.isNotEmpty() == true

    override fun acquire(chatId: String, token: String) {
        val name = nameFor(chatId)
        tokens.getOrPut(name) { ConcurrentHashMap.newKeySet() }.add(token)
        idleJobs.remove(name)?.cancel()
        used[name] = System.currentTimeMillis()
        heartbeats.computeIfAbsent(name) { scope.launch { heartbeat(name) } }
    }

    /**
     * While a chat holds its box, tells the box's watchdog now and then that the app
     * is still here, so a turn that thinks for a long time keeps its background
     * processes. Stops once the box is let go.
     */
    private suspend fun heartbeat(name: String) {
        try {
            while (true) {
                delay(heartbeatMs)
                if (!isBusy(name)) break
                val id = ids[name] ?: continue
                runCatching { BoxRunner.alive(api, id) }
            }
        } finally {
            heartbeats.remove(name)
        }
    }

    /**
     * After the app was killed, its boxes may still be awake (Box pauses them only
     * after an hour). Pauses this app's boxes that nothing has used for a few
     * minutes; one another phone is working in stays awake.
     */
    fun pauseStrays() {
        scope.launch {
            val now = System.currentTimeMillis() / 1000
            runCatching { api.list(LABEL) }.getOrNull().orEmpty()
                .filter { it.name.isOurs() && it.status != "paused" && it.status != "creating" && !isBusy(it.name!!) }
                .filter { now - (it.lastActivityAt ?: 0L) > STRAY_SECS }
                .forEach { b ->
                    runCatching { api.pause(b.id) }.onSuccess { log.add("${b.name}: left awake; paused") }
                }
        }
    }

    override fun release(chatId: String, token: String) {
        val name = nameFor(chatId)
        val set = tokens[name] ?: return
        if (!set.remove(token) || set.isNotEmpty()) return
        used[name] = System.currentTimeMillis()
        idleJobs.remove(name)?.cancel()
        idleJobs[name] = scope.launch {
            delay(idleMs)
            if (isBusy(name)) return@launch
            runCatching { pause(name) }.onFailure { log.add("$name: pause failed: ${it.message}") }
        }
    }

    override suspend fun ensure(chatId: String): CloudSandboxes.Ready = withContext(Dispatchers.IO) {
        val name = nameFor(chatId)
        locks.getOrPut(name) { Mutex() }.withLock {
            try {
                ensureLocked(chatId, name)
            } catch (e: BoxApi.UsageLimit) {
                markSpent()
                throw e
            }
        }
    }

    /**
     * The chat's box for one tool call: the one [ensure] checked during this hold,
     * without another round trip, otherwise [ensure]'s. A call Box then refuses is
     * reported with [forget], so the next [ensure] checks again.
     */
    suspend fun sandboxFor(chatId: String): BoxSandbox {
        val name = nameFor(chatId)
        if (name in awake) ids[name]?.let { return BoxSandbox(api, it) }
        return ensure(chatId).sandbox as BoxSandbox
    }

    /** The chat's box must be checked again before it is used without asking ([sandboxFor]). */
    fun forget(chatId: String) {
        awake.remove(nameFor(chatId))
    }

    private suspend fun ensureLocked(chatId: String, name: String): CloudSandboxes.Ready {
        awake.remove(name)
        var box = api.get(name)
        if (box != null && box.status == "creating") box = awaitReady(box)
        if (box != null && box.status == "error") {
            log.add("$name: in error; replacing it")
            runCatching { api.delete(box.id) }
            box = null
        }
        var fresh = false
        if (box == null) {
            val archived = archivedOf(name)
            box = awaitReady(
                withRoom(chatId, name) {
                    if (archived != null) api.fromSnapshot(archived.snapshot, name, listOf(LABEL)) else api.create(name, listOf(LABEL))
                },
            )
            if (archived != null) {
                log.add("$name: restored from its snapshot")
                runCatching { api.deleteSnapshot(archived.box, archived.snapshot) }
                forgetArchived(name)
            } else {
                fresh = true
                log.add("$name: created")
            }
        }
        ids[name] = box.id
        used[name] = System.currentTimeMillis()
        // Any call wakes a paused box. This one also makes the workspace and starts the box's
        // watchdog (runner.sh), and is where a spent allowance shows.
        val r = BoxRunner.alive(api, box.id, folders = listOf(WORKSPACE))
        if (r.exitCode != 0) throw IOException("Upstash Box: could not prepare $name: ${r.stderr.trim().take(200)}")
        awake.add(name)
        return CloudSandboxes.Ready(BoxSandbox(api, box.id), fresh)
    }

    override suspend fun exists(chatId: String): Boolean = withContext(Dispatchers.IO) {
        val name = nameFor(chatId)
        archivedOf(name) != null || api.get(name)?.let { it.status != "error" } == true
    }

    override suspend fun delete(chatId: String) = withContext(Dispatchers.IO) {
        val name = nameFor(chatId)
        idleJobs.remove(name)?.cancel()
        tokens.remove(name)
        awake.remove(name)
        var deleted = false
        api.get(name)?.let {
            api.delete(it.id)
            deleted = true
        }
        archivedOf(name)?.let {
            api.deleteSnapshot(it.box, it.snapshot)
            forgetArchived(name)
            deleted = true
        }
        ids.remove(name)
        used.remove(name)
        if (deleted) log.add("$name: deleted")
    }

    override fun suspendAllNow() {
        idleJobs.values.forEach { it.cancel() }
        idleJobs.clear()
        tokens.clear()
        awake.clear()
        scope.launch {
            runCatching { api.list(LABEL) }.getOrNull().orEmpty()
                .filter { it.name.isOurs() && it.status != "paused" && it.status != "creating" }
                .forEach { b -> runCatching { api.pause(b.id) } }
        }
    }

    // --- room for one more ---------------------------------------------------------

    private suspend fun withRoom(chatId: String, name: String, make: () -> BoxApi.Box): BoxApi.Box {
        try {
            return make()
        } catch (e: BoxApi.BoxCountLimit) {
            if (!archiveOne(chatId, except = name)) throw Full()
        } catch (e: BoxApi.ApiException) {
            // Made a moment ago by another call for the same chat.
            if (e.code == 409) return api.get(name) ?: throw e
            throw e
        }
        return try {
            make()
        } catch (e: BoxApi.BoxCountLimit) {
            throw Full()
        }
    }

    /** Snapshots and deletes this app's least recently used idle box. True if one was archived. */
    private suspend fun archiveOne(forChat: String, except: String): Boolean {
        val candidates = api.list(LABEL)
            .filter { b -> b.name.isOurs() && b.name != except && !isBusy(b.name!!) && b.status in ARCHIVABLE }
            .sortedBy { used[it.name] ?: ((it.lastActivityAt ?: 0L) * 1000) }
        for (victim in candidates) {
            val vname = victim.name!!
            val lock = locks.getOrPut(vname) { Mutex() }
            // A box whose own chat is starting is not taken.
            if (!lock.tryLock()) continue
            try {
                if (isBusy(vname)) continue
                onStage?.invoke(forChat, "room")
                log.add("$vname: archiving to make room for $except")
                val started = api.snapshot(victim.id, vname)
                val snapshot = awaitSnapshot(victim.id, started.id)
                recordArchived(vname, Archived(victim.id, snapshot.id))
                awake.remove(vname)
                api.delete(victim.id)
                ids.remove(vname)
                log.add("$vname: archived (${snapshot.sizeBytes ?: "?"} bytes)")
                return true
            } finally {
                lock.unlock()
                onStage?.invoke(forChat, null)
            }
        }
        return false
    }

    private suspend fun awaitReady(box: BoxApi.Box): BoxApi.Box {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        var b = box
        while (b.status == "creating") {
            if (System.currentTimeMillis() > deadline) throw IOException("Upstash Box: ${box.name} is still being made")
            delay(1_000)
            b = api.get(box.id) ?: throw IOException("sandbox_gone")
        }
        if (b.status == "error") throw IOException("Upstash Box could not make ${box.name}")
        return b
    }

    private suspend fun awaitSnapshot(boxId: String, snapshotId: String): BoxApi.Snapshot {
        val deadline = System.currentTimeMillis() + SNAPSHOT_TIMEOUT_MS
        while (true) {
            val s = api.snapshots(boxId).firstOrNull { it.id == snapshotId }
            when (s?.status) {
                "ready" -> return s
                "error" -> throw IOException("Upstash Box could not snapshot $boxId")
            }
            if (System.currentTimeMillis() > deadline) throw IOException("Upstash Box: snapshot of $boxId is taking too long")
            delay(2_000)
        }
    }

    private fun pause(name: String) {
        awake.remove(name)
        val id = ids[name] ?: api.get(name)?.id ?: return
        api.pause(id)
        log.add("$name idle; paused")
    }

    // --- the month's CPU -------------------------------------------------------------

    /** This month's CPU use (UTC months, as Box bills), read from Box at most every few minutes. */
    suspend fun usage(refresh: Boolean = false): Usage = withContext(Dispatchers.IO) {
        val month = month()
        val now = System.currentTimeMillis()
        usageCache?.takeIf { !refresh && it.second.month == month && now - it.first < USAGE_TTL_MS }?.let { return@withContext it.second }
        val hours = api.cpuNs(month) / 3.6e12
        Usage(month, hours, FREE_CPU_HOURS, spentIn() == month).also { usageCache = now to it }
    }

    /**
     * Whether a new chat should start in Box: this month's free CPU is not nearly
     * used up and Box has not refused work for usage. From what is known now; an
     * unknown month counts as yes, so a failed read never moves chats away.
     */
    fun allowsNewChats(): Boolean {
        val month = month()
        if (spentIn() == month) return false
        val u = usageCache?.second?.takeIf { it.month == month } ?: return true
        return u.usedHours < u.freeHours * NEW_CHAT_SHARE
    }

    /** Box refused work for usage: new chats go elsewhere until the month changes. */
    fun markSpent() {
        val month = month()
        if (spentIn() == month) return
        editState { it.put("spent", month) }
        usageCache = null
        log.add("Upstash Box refused work for usage; new chats go elsewhere until ${month.next()}")
    }

    // --- moving a chat out -----------------------------------------------------------

    /**
     * Copies the chat's workspace out of its box into [into] with the file API only
     * (no command runs, so it can work while Box refuses commands). Dependency
     * folders are left out. False when the box is gone or archived, when there are
     * more than [maxFiles] files or [maxBytes] bytes, or when a read fails.
     */
    suspend fun downloadWorkspace(chatId: String, into: File, maxFiles: Int = 2_000, maxBytes: Long = 300L shl 20): Boolean =
        withContext(Dispatchers.IO) {
            val box = runCatching { api.get(nameFor(chatId)) }.getOrNull() ?: return@withContext false
            var files = 0
            var bytes = 0L
            val queue = ArrayDeque(listOf(WORKSPACE))
            try {
                while (queue.isNotEmpty()) {
                    val dir = queue.removeFirst()
                    for (e in api.listDir(box.id, dir)) {
                        val path = "$dir/${e.name}"
                        if (e.isDir) {
                            if (e.name !in SKIPPED) queue.addLast(path)
                            continue
                        }
                        if (++files > maxFiles) return@withContext false
                        bytes += e.size
                        if (bytes > maxBytes) return@withContext false
                        val out = File(into, path.removePrefix("$WORKSPACE/"))
                        if (!out.canonicalPath.startsWith(into.canonicalPath + File.separator)) continue
                        out.parentFile?.mkdirs()
                        val input = api.read(box.id, path) ?: continue
                        input.use { src -> out.outputStream().use { src.copyTo(it) } }
                    }
                }
                true
            } catch (e: IOException) {
                log.add("${nameFor(chatId)}: workspace not copied out (${e.message})")
                false
            }
        }

    // --- state on the phone ----------------------------------------------------------

    private fun archivedOf(name: String): Archived? =
        state().optJSONObject("archived")?.optJSONObject(name)?.let { Archived(it.getString("box"), it.getString("snapshot")) }

    private fun recordArchived(name: String, a: Archived) = editState {
        val all = it.optJSONObject("archived") ?: JSONObject().also { o -> it.put("archived", o) }
        all.put(name, JSONObject().put("box", a.box).put("snapshot", a.snapshot))
    }

    private fun forgetArchived(name: String) = editState { it.optJSONObject("archived")?.remove(name) }

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

    private fun String?.isOurs() = this != null && startsWith(PREFIX)

    private fun String.next() = YearMonth.parse(this).plusMonths(1).toString()

    companion object {
        const val PREFIX = "poordsh-"
        /** Every box this app makes carries it, so it lists only its own. */
        const val LABEL = "poordsh"
        const val WORKSPACE = "/tmp/workspace"
        const val IDLE_PAUSE_MS = 60_000L
        /** How often a held box hears from the app (well inside [BoxRunner.WATCHDOG_SECS]). */
        const val HEARTBEAT_MS = 120_000L
        /** A box of this app nothing has used for this long is paused when the app starts. */
        private const val STRAY_SECS = 180L
        /** The free plan's monthly allowance of active CPU hours (November 2026). */
        const val FREE_CPU_HOURS = 5.0
        /** New chats stop starting in Box at this share of the allowance, leaving the rest to chats already there. */
        const val NEW_CHAT_SHARE = 0.95
        private const val USAGE_TTL_MS = 5 * 60_000L
        private const val START_TIMEOUT_MS = 300_000L
        private const val SNAPSHOT_TIMEOUT_MS = 600_000L
        private val ARCHIVABLE = setOf("idle", "paused", "running")
        private val SKIPPED = setOf("node_modules", ".venv", "venv", "__pycache__")

        fun month(): String = YearMonth.now(ZoneOffset.UTC).toString()

        /** Box name for a chat: lowercase letters, digits and dashes, under 63 characters. */
        fun nameFor(chatId: String): String = (PREFIX + chatId.lowercase().replace(Regex("[^a-z0-9-]"), "-")).take(63)
    }
}
