package dev.mokouliszt.poordsh.modal

import dev.mokouliszt.poordsh.phone.AgentChannel
import dev.mokouliszt.poordsh.runtime.GuestProcess
import dev.mokouliszt.poordsh.runtime.LogBuffer
import dev.mokouliszt.poordsh.runtime.Proot
import dev.mokouliszt.poordsh.runtime.RuntimeManager
import dev.mokouliszt.poordsh.runtime.RuntimePaths
import dev.mokouliszt.poordsh.sandbox.SandboxRunner
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

/**
 * Modal, as the app reaches it (docs/adr/modal-first.md): the official Modal SDK
 * in a venv of its own in the app's Ubuntu, and `assets/modal/poordsh-modal.py`
 * running there, one JSON request per line. Modal's API is gRPC with its own
 * client checks, so the app leaves speaking it to Modal's SDK, which pip keeps
 * current, instead of carrying a client of its own.
 *
 * The SDK is installed on first use (tens of MB from PyPI) and upgraded when it
 * is a week old or Modal says it is too old. The helper starts on the first call
 * and stops [idleMs] after the last one, unless [keepUp] says sandboxes are up.
 */
class ModalAgent(
    private val paths: RuntimePaths,
    private val proot: Proot,
    private val runtime: RuntimeManager,
    private val script: () -> ByteArray,
    private val token: () -> Token?,
    private val log: LogBuffer,
    private val idleMs: Long = IDLE_MS,
) : ModalClient {
    /** A Modal token: "ak-…" and "as-…". */
    data class Token(val id: String, val secret: String) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("secret", secret)

        companion object {
            private val ID = Regex("""\bak-[A-Za-z0-9]+""")
            private val SECRET = Regex("""\bas-[A-Za-z0-9]+""")

            /** From anything holding both, such as the `modal token set --token-id … --token-secret …` line Modal shows. */
            fun parse(text: String): Token? {
                val id = ID.find(text)?.value ?: return null
                val secret = SECRET.find(text)?.value ?: return null
                return Token(id, secret)
            }

            fun fromJson(text: String?): Token? = runCatching {
                JSONObject(text ?: return null).let { Token(it.getString("id"), it.getString("secret")) }
            }.getOrNull()
        }
    }

    /** A call Modal refused; [kind] as the helper says it ("usage", "auth", "notfound", "exists", "invalid", "outdated", "other"). */
    class ModalException(val kind: String, message: String) : IOException(message)

    /** Told while the SDK is being installed or upgraded ("tools"), and null when that is over. */
    @Volatile var onStage: ((String?) -> Unit)? = null

    @Volatile override var keepUp: () -> Boolean = { false }

    private class Running(val proc: GuestProcess, val channel: AgentChannel)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var running: Running? = null
    @Volatile private var idleJob: Job? = null

    val installed: Boolean get() = paths.hostPathOf("$VENV/bin/python").exists() && marker.isFile

    private val marker get() = paths.hostPathOf("$DIR/installed")

    override suspend fun call(op: String, args: JSONObject, timeoutMs: Long): JSONObject {
        val first = callOnce(op, args, timeoutMs)
        if (first.optString("kind") != "outdated") return check(first)
        // Modal no longer takes this SDK: upgrade it and try once more.
        log.add("Modal says its SDK is too old; upgrading")
        stop()
        install(upgrade = true)
        return check(callOnce(op, args, timeoutMs))
    }

    private fun check(r: JSONObject): JSONObject {
        if (r.has("error")) throw ModalException(r.optString("kind", "other"), r.optString("error"))
        return r
    }

    private suspend fun callOnce(op: String, args: JSONObject, timeoutMs: Long): JSONObject {
        val r = ensure()
        idleJob?.cancel()
        try {
            return r.channel.call(JSONObject(args.toString()).put("op", op), timeoutMs)
        } finally {
            scheduleStop()
        }
    }

    /** Installs the SDK, or upgrades it to Modal's latest release. */
    suspend fun install(upgrade: Boolean) = withContext(Dispatchers.IO) {
        runtime.awaitReady()
        onStage?.invoke("tools")
        try {
            val sh = """
                set -e
                mkdir -p $DIR
                [ -x $VENV/bin/python ] || python3 -m venv $VENV
                $VENV/bin/pip install --disable-pip-version-check --no-input --quiet ${if (upgrade) "--upgrade " else ""}modal
                $VENV/bin/python -c 'import modal; print("modal", modal.__version__)'
            """.trimIndent()
            val proc = GuestProcess.start("modal-install", proot.processBuilder(Proot.Spec(listOf("sh", "-c", sh))), log, captureStdout = true)
            val code = try {
                withTimeout(INSTALL_TIMEOUT_MS) { while (proc.isAlive) delay(500); proc.exitCode() }
            } finally {
                if (proc.isAlive) proc.stop()
            }
            if (code != 0) throw ModalException("tools", "Modal's tools could not be installed (exit $code)")
            marker.parentFile?.mkdirs()
            marker.writeText(System.currentTimeMillis().toString())
        } finally {
            onStage?.invoke(null)
        }
    }

    private suspend fun ensure(): Running = withContext(Dispatchers.IO) {
        running?.takeIf { it.proc.isAlive }?.let { return@withContext it }
        lock.withLock {
            running?.takeIf { it.proc.isAlive }?.let { return@withLock it }
            val t = token() ?: throw ModalException("auth", "modal_key_missing")
            runtime.awaitReady()
            if (!installed) install(upgrade = false)
            else if (System.currentTimeMillis() - (marker.readText().trim().toLongOrNull() ?: 0) > UPGRADE_AFTER_MS) {
                runCatching { install(upgrade = true) }.onFailure { log.add("Modal SDK not upgraded: ${it.message}") }
            }
            writeScript()
            val r = try {
                startHelper(t)
            } catch (e: ModalException) {
                throw e
            } catch (e: Exception) {
                // A venv a new Ubuntu image no longer runs (another Python) or a broken
                // install: made again from scratch, once.
                log.add("Modal helper did not start (${e.message}); installing its tools again")
                paths.hostPathOf(VENV).deleteRecursively()
                marker.delete()
                install(upgrade = false)
                startHelper(t)
            }
            running = r
            r
        }
    }

    private fun writeScript() {
        val file = paths.hostPathOf(SCRIPT)
        file.parentFile?.mkdirs()
        file.writeBytes(script())
        paths.hostPathOf(IO).mkdirs()
    }

    /**
     * Checks [t] with Modal before it is kept, installing the SDK first if this
     * is the first token (told by [onInstall]); returns the workspace's name. A
     * wrong token is a [ModalException] of kind "auth". The helper of the token
     * in use, if any, is stopped: the next call starts one with the kept token.
     */
    suspend fun check(t: Token, onInstall: () -> Unit = {}): String = withContext(Dispatchers.IO) {
        stop()
        lock.withLock {
            runtime.awaitReady()
            if (!installed) {
                onInstall()
                install(upgrade = false)
            }
            writeScript()
            var reply = probe(t)
            if (reply.optString("kind") == "outdated") {
                onInstall()
                install(upgrade = true)
                reply = probe(t)
            }
            check(reply).optString("workspace")
        }
    }

    private suspend fun probe(t: Token): JSONObject {
        val r = startHelper(t)
        try {
            return r.channel.call(JSONObject().put("op", "check"), START_TIMEOUT_MS)
        } finally {
            r.channel.fail(IOException("checked"))
            r.proc.stop()
        }
    }

    private suspend fun startHelper(t: Token): Running {
        val spec = Proot.Spec(listOf("$VENV/bin/python", "-I", SCRIPT), env = mapOf("PYTHONUNBUFFERED" to "1"))
        val proc = GuestProcess.start("modal", proot.processBuilder(spec), log, captureStdout = false)
        val channel = AgentChannel("modal", proc.stdout, proc.stdin)
        channel.start()
        try {
            withTimeout(START_TIMEOUT_MS) { channel.ready.await() }
            // The token goes over the pipe, never through the environment.
            val init = channel.call(
                JSONObject().put("op", "init").put("tokenId", t.id).put("tokenSecret", t.secret)
                    .put("app", APP).put("runner", SandboxRunner.SCRIPT).put("packages", org.json.JSONArray(IMAGE_PACKAGES)),
                START_TIMEOUT_MS,
            )
            if (init.has("error")) throw ModalException(init.optString("kind", "other"), init.optString("error"))
            log.add("Modal helper ready (SDK ${init.optString("version")})")
        } catch (e: Exception) {
            proc.stop()
            throw e
        }
        return Running(proc, channel)
    }

    private fun scheduleStop() {
        idleJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(idleMs)
                if (!keepUp()) break
            }
            stop()
        }
    }

    fun stop() {
        idleJob?.cancel()
        running?.let {
            running = null
            it.channel.fail(IOException("Modal helper stopped"))
            it.proc.stop()
        }
    }

    override fun ioFile(name: String): Pair<File, String> = paths.hostPathOf("$IO/$name") to "$IO/$name"

    companion object {
        /** The Modal App every sandbox of this app belongs to. */
        const val APP = "poorharness"
        private const val DIR = "/root/.poordsh/modal"
        private const val VENV = "$DIR/venv"
        private const val SCRIPT = "$DIR/poordsh-modal.py"
        private const val IO = "$DIR/io"
        /** Added to Modal's default Debian image: what the app's scripts and opencode's tools use. */
        val IMAGE_PACKAGES = listOf("git", "curl", "zip", "unzip", "procps", "ripgrep")
        private const val IDLE_MS = 180_000L
        const val CALL_TIMEOUT_MS = 20 * 60_000L
        private const val START_TIMEOUT_MS = 120_000L
        private const val INSTALL_TIMEOUT_MS = 15 * 60_000L
        private const val UPGRADE_AFTER_MS = 7L * 24 * 3600_000
    }
}
