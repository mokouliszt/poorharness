package dev.mokouliszt.poordsh.tensorlake

import dev.mokouliszt.poordsh.runtime.LogBuffer
import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi.QuotaException
import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi.Sandbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * One named Tensorlake sandbox per chat, created on first use, suspended when
 * nothing has used it for [idleMs], and deleted with the chat. opencode's tools
 * work in it, in [WORKSPACE].
 *
 * The free plan runs one sandbox at a time. When the plan refuses a start, idle
 * sandboxes of this app are suspended to make room; a busy one is never touched
 * and the start fails with [Busy]. Suspending keeps disk, memory and processes.
 */
class SandboxManager(
    private val api: TensorlakeApi,
    private val log: LogBuffer,
    private val idleMs: Long = IDLE_SUSPEND_MS,
) {
    /** Another chat is working and the plan allows no second sandbox. */
    class Busy : IOException("quota_busy")

    data class Ready(val sandbox: Sandbox, val fresh: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val tokens = ConcurrentHashMap<String, MutableSet<String>>()
    private val idleJobs = ConcurrentHashMap<String, Job>()

    fun isBusy(name: String) = tokens[name]?.isNotEmpty() == true

    /** Whether the sandbox exists (without creating or waking it). */
    suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) {
        api.get(name)?.status.let { it != null && it != "terminated" }
    }

    fun acquire(name: String, token: String) {
        tokens.getOrPut(name) { ConcurrentHashMap.newKeySet() }.add(token)
        idleJobs.remove(name)?.cancel()
    }

    fun release(name: String, token: String) {
        val set = tokens[name] ?: return
        if (!set.remove(token) || set.isNotEmpty()) return
        idleJobs.remove(name)?.cancel()
        idleJobs[name] = scope.launch {
            delay(idleMs)
            if (isBusy(name)) return@launch
            runCatching { api.suspend(name) }
                .onSuccess { log.add("$name idle; suspended") }
                .onFailure { log.add("$name: suspend failed: ${it.message}") }
        }
    }

    /** Runs [block] with the sandbox running; [token] keeps it from being suspended meanwhile. */
    suspend fun <T> use(name: String, token: String, block: suspend (Ready) -> T): T {
        acquire(name, token)
        try {
            return block(ensure(name))
        } finally {
            release(name, token)
        }
    }

    /** Creates or resumes the sandbox and waits until it runs. Prefer [use]. */
    suspend fun ensure(name: String): Ready = withContext(Dispatchers.IO) {
        locks.getOrPut(name) { Mutex() }.withLock { ensureLocked(name) }
    }

    private suspend fun ensureLocked(name: String): Ready {
        var fresh = false
        var attempt = 0
        while (true) {
            try {
                val sb = api.get(name)
                if (sb == null || sb.status == "terminated") {
                    if (sb != null) runCatching { api.delete(name) }
                    log.add("$name: creating")
                    createOrAttach(name)
                    fresh = true
                } else if (sb.status == "suspended" || sb.status == "suspending") {
                    log.add("$name: resuming")
                    api.resume(name)
                }
                return Ready(awaitRunning(name), fresh)
            } catch (e: QuotaException) {
                if (attempt++ > 0 || !makeRoom(name)) throw Busy()
            }
        }
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        idleJobs.remove(name)?.cancel()
        tokens.remove(name)
        api.delete(name)
        log.add("$name: deleted")
    }

    /** The notification's Stop: let every sandbox of this app pause, without waiting. */
    fun suspendAllNow() {
        idleJobs.values.forEach { it.cancel() }
        idleJobs.clear()
        tokens.clear()
        scope.launch {
            runCatching { api.list("running") }.getOrNull().orEmpty()
                .filter { it.name.isOurs() }
                .forEach { sb -> runCatching { api.suspend(sb.id) } }
        }
    }

    private fun createOrAttach(name: String): Sandbox = try {
        api.create(name)
    } catch (e: TensorlakeApi.ApiException) {
        // Created a moment ago elsewhere (the opencode plugin binds by the same name).
        if (e.code == 409) api.get(name) ?: throw e else throw e
    }

    private suspend fun awaitRunning(name: String): Sandbox {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (true) {
            val sb = api.get(name) ?: throw IOException("sandbox_gone")
            when (sb.status) {
                "running" -> return sb
                "terminated" -> throw IOException("sandbox_gone")
            }
            if (System.currentTimeMillis() > deadline) throw IOException("Tensorlake: $name is still ${sb.status}")
            delay(400)
        }
    }

    /** Suspends idle sandboxes of this app that hold the plan's slot. True if any was suspended. */
    private suspend fun makeRoom(except: String): Boolean {
        val idle = api.list("running").filter { it.name != except && it.name.isOurs() && !isBusy(it.name!!) }
        for (sb in idle) {
            log.add("${sb.name}: suspending to make room for $except")
            runCatching { api.suspend(sb.id) }
        }
        if (idle.isEmpty()) return false
        // The slot frees once the suspend has gone through.
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (api.list("running").none { r -> idle.any { it.id == r.id } }) break
            delay(400)
        }
        return true
    }

    /** Only chat sandboxes ("poordsh-*"): "opencode-*" ones may belong to the user's own opencode elsewhere. */
    private fun String?.isOurs() = this != null && startsWith(PREFIX)

    companion object {
        const val PREFIX = "poordsh-"
        const val WORKSPACE = "/tmp/workspace"
        const val IDLE_SUSPEND_MS = 60_000L
        private const val START_TIMEOUT_MS = 180_000L

        /** Sandbox name for a chat: lowercase letters, digits and dashes, under 63 characters. */
        fun nameFor(chatId: String): String =
            (PREFIX + chatId.lowercase().replace(Regex("[^a-z0-9-]"), "-")).take(63)

        /** The name tensorlake-opencode 0.3 gives an opencode session's sandbox. */
        fun pluginName(sessionId: String): String =
            "opencode-$sessionId".lowercase().replace(Regex("[^a-z0-9-]"), "-").take(63)
    }
}
