package dev.mokouliszt.poordsh.phone

import dev.mokouliszt.poordsh.runtime.GuestProcess
import dev.mokouliszt.poordsh.runtime.LogBuffer
import dev.mokouliszt.poordsh.runtime.Proot
import dev.mokouliszt.poordsh.runtime.RuntimeManager
import dev.mokouliszt.poordsh.runtime.RuntimePaths
import dev.mokouliszt.poordsh.runtime.SafeFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What [PhoneSandboxServer] needs from the jails (a seam for tests). */
interface PhoneOps {
    fun acquire(name: String, token: String)
    fun release(name: String, token: String)

    /** Runs one [PhoneAgent] operation in the chat's jail and returns its reply. */
    suspend fun op(name: String, request: JSONObject): JSONObject
}

/**
 * On-device sandboxes used when there is no Tensorlake key: one PRoot jail per
 * chat, created on first use, stopped when idle, deleted with the chat. It is the
 * device counterpart of [dev.mokouliszt.poordsh.tensorlake.SandboxManager]; its
 * files live in `workspace`, so switching model mid-chat keeps the work.
 *
 * opencode's sandbox tools reach a chat's jail through [op] (the running
 * [PhoneAgent], over its stdin/stdout). Unlike a Tensorlake sandbox, the files
 * are on disk and never expire; stopping a jail only ends its processes.
 */
class PhoneSandboxManager(
    private val paths: RuntimePaths,
    private val proot: Proot,
    private val runtime: RuntimeManager,
    private val log: LogBuffer,
    private val idleMs: Long = IDLE_MS,
) : PhoneOps {
    private class Agent(val proc: GuestProcess, val channel: AgentChannel)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val agents = ConcurrentHashMap<String, Agent>()
    private val tokens = ConcurrentHashMap<String, MutableSet<String>>()
    private val idleJobs = ConcurrentHashMap<String, Job>()

    /** A chat's jail folders, created if missing. */
    fun dirs(name: String): Proot.Jail {
        val base = File(paths.sandboxes, safe(name))
        val jail = Proot.Jail(home = File(base, "home"), tmp = File(base, "tmp"), workspace = File(base, "workspace"))
        listOf(jail.home, jail.tmp, jail.workspace, File(jail.home, ".poordsh")).forEach { it.mkdirs() }
        return jail
    }

    override fun acquire(name: String, token: String) {
        tokens.getOrPut(name) { ConcurrentHashMap.newKeySet() }.add(token)
        idleJobs.remove(name)?.cancel()
    }

    override fun release(name: String, token: String) {
        val set = tokens[name] ?: return
        if (!set.remove(token) || set.isNotEmpty()) return
        idleJobs.remove(name)?.cancel()
        idleJobs[name] = scope.launch {
            delay(idleMs)
            if (tokens[name]?.isNotEmpty() == true) return@launch
            stopAgent(name, "idle")
        }
    }

    override suspend fun op(name: String, request: JSONObject): JSONObject =
        // The agent bounds commands itself; this only guards against a wedged pipe.
        ensureAgent(name).channel.call(request, OP_TIMEOUT_MS)

    private suspend fun ensureAgent(name: String): Agent = withContext(Dispatchers.IO) {
        agents[name]?.takeIf { it.proc.isAlive }?.let { return@withContext it }
        locks.getOrPut(name) { Mutex() }.withLock {
            agents[name]?.takeIf { it.proc.isAlive }?.let { return@withLock it }
            runtime.awaitReady()
            val jail = dirs(name)
            File(jail.home, ".poordsh/agent.py").writeText(PhoneAgent.SOURCE)
            val spec = Proot.Spec(
                command = listOf("python3", "-I", "/root/.poordsh/agent.py"),
                workdir = WORKSPACE,
                env = mapOf("PYTHONUNBUFFERED" to "1"),
                jail = jail,
            )
            val proc = GuestProcess.start("phone-$name", proot.processBuilder(spec), log, captureStdout = false)
            val channel = AgentChannel(name, proc.stdout, proc.stdin)
            val agent = Agent(proc, channel)
            agents[name] = agent
            channel.start()
            try {
                withTimeout(START_TIMEOUT_MS) { channel.ready.await() }
            } catch (e: Exception) {
                agents.remove(name, agent)
                proc.stop()
                throw IOException("phone sandbox did not start", e)
            }
            log.add("$name: jail ready")
            agent
        }
    }

    private fun stopAgent(name: String, why: String) {
        agents.remove(name)?.let {
            it.channel.fail(IOException("phone sandbox stopped"))
            it.proc.stop()
            log.add("$name: jail stopped ($why)")
        }
    }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        idleJobs.remove(name)?.cancel()
        tokens.remove(name)
        stopAgent(name, "deleted")
        SafeFs.deleteTree(File(paths.sandboxes, safe(name)))
        log.add("$name: jail deleted")
    }

    /** The notification's Stop: end every jail's agent now. */
    fun stopAllNow() {
        idleJobs.values.forEach { it.cancel() }
        idleJobs.clear()
        tokens.clear()
        agents.keys.toList().forEach { stopAgent(it, "stop") }
    }

    companion object {
        const val WORKSPACE = "/tmp/workspace"
        private const val IDLE_MS = 60_000L
        private const val START_TIMEOUT_MS = 60_000L
        private const val OP_TIMEOUT_MS = 20 * 60_000L

        /** A chat id reduced to a safe folder name. */
        fun safe(name: String): String = name.lowercase().replace(Regex("[^a-z0-9_-]"), "-").take(80)
    }
}
