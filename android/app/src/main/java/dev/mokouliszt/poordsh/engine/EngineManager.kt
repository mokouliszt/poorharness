package dev.mokouliszt.poordsh.engine

import dev.mokouliszt.poordsh.backend.AgentBackend
import dev.mokouliszt.poordsh.backend.BackendState.Phase
import dev.mokouliszt.poordsh.backend.EventBus
import dev.mokouliszt.poordsh.backend.Ids
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.runtime.Logs
import dev.mokouliszt.poordsh.runtime.RuntimeManager
import dev.mokouliszt.poordsh.service.Keepalive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts engines when something needs them and stops them when nothing has for
 * [IDLE_STOP_MS]. "Something" is an activity token: a running turn (from
 * chat.status events) or a one-off call wrapped in [use].
 *
 * The model list is cached on disk after every start, so the picker works
 * while the engine is stopped.
 */
class EngineManager(
    private val runtime: RuntimeManager,
    backendList: List<AgentBackend>,
    private val cacheDir: File,
    private val keepalive: Keepalive,
) {
    val backends: Map<String, AgentBackend> = backendList.associateBy { it.id }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = backends.keys.associateWith { Mutex() }
    private val activity = ConcurrentHashMap<String, MutableSet<String>>()
    private val idleJobs = ConcurrentHashMap<String, Job>()
    private val applyPending = ConcurrentHashMap.newKeySet<String>()
    private val discovering = ConcurrentHashMap.newKeySet<String>()
    private val discoveryErrors = ConcurrentHashMap<String, String>()
    private val catalogJobs = ConcurrentHashMap<String, Job>()

    /** Runs after an engine has started (pending deletions, one-time import). */
    var onStarted: suspend (AgentBackend) -> Unit = {}

    init {
        cacheDir.mkdirs()
        for (b in backendList) {
            b.setRecoveryPolicy { hasActivity(b.id) }
            scope.launch {
                b.state.collect { st ->
                    // The service (and its notification) lives exactly as long as an engine does.
                    if (st.phase == Phase.READY || st.phase == Phase.STARTING) keepalive.acquire("engine:${b.id}")
                    else keepalive.release("engine:${b.id}")
                    EventBus.emit("backend.state", jsonOf("backend" to b.id, "state" to st.toJson()))
                }
            }
        }
        scope.launch {
            EventBus.events.collect { e ->
                when (e.optString("type")) {
                    "chat.status" -> {
                        val sid = e.optString("sessionId")
                        val engine = Ids.split(sid).first
                        if (engine !in backends) return@collect
                        if (e.optString("status") == "busy") acquire(engine, "turn:$sid") else release(engine, "turn:$sid")
                    }
                }
            }
        }
    }

    fun backend(id: String): AgentBackend = backends[id] ?: throw IllegalArgumentException("unknown engine $id")

    fun runningBackend(id: String): AgentBackend? = backends[id]?.takeIf { it.state.value.phase == Phase.READY }

    fun hasActivity(id: String) = activity[id]?.isNotEmpty() == true

    fun anyActivity() = activity.values.any { it.isNotEmpty() }

    fun acquire(id: String, token: String) {
        activity.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(token)
        idleJobs.remove(id)?.cancel()
    }

    fun release(id: String, token: String) {
        val set = activity[id] ?: return
        if (!set.remove(token) || set.isNotEmpty()) return
        if (applyPending.remove(id)) scope.launch { stop(id) } else scheduleIdleStop(id)
    }

    /** For a session that is gone (its chat was deleted mid-turn): its turn no longer keeps the engine up. */
    fun forgetTurn(id: String, sessionId: String) = release(id, "turn:$sessionId")

    /** Runs [block] with the engine started; [token] keeps it running until the block returns. */
    suspend fun <T> use(id: String, token: String, block: suspend (AgentBackend) -> T): T {
        acquire(id, token)
        try {
            return block(ensure(id))
        } finally {
            release(id, token)
        }
    }

    /** Starts the engine if it is not running. Prefer [use], which also keeps it alive. */
    suspend fun ensure(id: String): AgentBackend {
        val b = backend(id)
        runtime.awaitReady()
        locks.getValue(id).withLock {
            if (b.state.value.phase != Phase.READY) {
                b.start()
                runCatching { onStarted(b) }.onFailure { Logs.app.add("$id after start: ${it.message}") }
                // Off the caller's path: a prompt should not wait for the model list.
                catalogJobs[id] = scope.launch {
                    runCatching { refreshCatalog(b) }.onFailure { Logs.app.add("$id catalog: ${it.message}") }
                }
            }
        }
        return b
    }

    /** Settings changed: stop now if idle, otherwise as soon as the current work ends. */
    fun applyWhenIdle(id: String) {
        if (backend(id).state.value.phase == Phase.STOPPED) return
        if (hasActivity(id)) applyPending.add(id) else scope.launch { stop(id) }
    }

    suspend fun stop(id: String) {
        idleJobs.remove(id)?.cancel()
        locks.getValue(id).withLock { backend(id).stop() }
    }

    suspend fun stopAll() = backends.keys.forEach { stop(it) }

    /** For the notification's Stop: never waits on a lock that a slow start may hold. */
    fun stopAllNow() {
        idleJobs.values.forEach { it.cancel() }
        idleJobs.clear()
        activity.clear()
        applyPending.clear()
        backends.values.forEach { b -> scope.launch { runCatching { b.stop() } } }
    }

    private fun scheduleIdleStop(id: String) {
        idleJobs.remove(id)?.cancel()
        idleJobs[id] = scope.launch {
            delay(IDLE_STOP_MS)
            locks.getValue(id).withLock {
                val b = backend(id)
                if (!hasActivity(id) && b.state.value.phase != Phase.STOPPED) {
                    Logs.app.add("$id idle for ${IDLE_STOP_MS / 1000} s; stopping")
                    b.stop()
                }
            }
        }
    }

    // --- catalog cache ---------------------------------------------------------

    private fun cacheFile(id: String, kind: String) = File(cacheDir, "$kind-$id.json")

    suspend fun refreshCatalog(b: AgentBackend, discover: Boolean = false) {
        writeAtomically(cacheFile(b.id, "models"), b.models(discover).toString())
        discoveryErrors.remove(b.id)
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    suspend fun refresh(id: String) = use(id, "refresh") { refreshCatalog(it) }

    fun cachedModels(): JSONObject {
        val items = JSONArray()
        val pending = JSONArray()
        val errors = JSONObject()
        for (id in backends.keys) {
            val f = cacheFile(id, "models")
            if (f.isFile) {
                runCatching { JSONArray(f.readText()) }.getOrNull()?.let { a ->
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.put(it) }
                }
            } else {
                discoveryErrors[id]?.let { errors.put(id, it) }
            }
            if (id in discovering) pending.put(id)
        }
        return jsonOf("items" to items, "errors" to errors, "pending" to pending)
    }

    /** What model [modelId] ("<engine>:<model>") takes besides text, from the cached catalog. */
    fun modelInputs(modelId: String): Set<String> {
        val f = cacheFile(modelId.substringBefore(':'), "models")
        val list = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return emptySet()
        for (i in 0 until list.length()) {
            val m = list.optJSONObject(i) ?: continue
            if (m.optString("id") != modelId) continue
            val inputs = m.optJSONArray("inputs") ?: return emptySet()
            return (0 until inputs.length()).map { inputs.optString(it) }.toSet()
        }
        return emptySet()
    }

    /** First run (or a cleared cache): start each engine once to learn its models. */
    fun discoverMissing() {
        for (id in backends.keys) {
            if (cacheFile(id, "models").isFile || !discovering.add(id)) continue
            EventBus.emit("models.changed", jsonOf("backend" to id))
            scope.launch {
                try {
                    use(id, "discover") { b ->
                        catalogJobs[id]?.join()
                        if (!cacheFile(id, "models").isFile) refreshCatalog(b, discover = true)
                    }
                } catch (e: Exception) {
                    Logs.app.add("$id discovery failed: ${e.message}")
                    discoveryErrors[id] = e.message ?: e.javaClass.simpleName
                } finally {
                    discovering.remove(id)
                    EventBus.emit("models.changed", jsonOf("backend" to id))
                }
            }
        }
    }

    companion object {
        /** How long an engine stays up after its last turn, so a quick follow-up does not wait for a start. */
        const val IDLE_STOP_MS = 60_000L
    }
}
