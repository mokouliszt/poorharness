package dev.mokouliszt.poordsh.update

import dev.mokouliszt.poordsh.backend.EventBus
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.runtime.Logs
import dev.mokouliszt.poordsh.runtime.Tools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * When opencode is checked for updates and installed (docs/adr/engine-updates.md).
 *
 * Every time the app opens, it is checked in the background; with [autoUpdate]
 * on, a newer release is installed right after and swapped in once opencode is
 * idle, as with an update started from Settings, which goes through [apply] too.
 */
class EngineUpdates(
    private val updater: Updater,
    private val autoUpdate: () -> Boolean,
    /** Restarts an engine onto its new build once it is idle. */
    private val applyWhenIdle: (String) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The last check's result (as `updates.check` returns it), if one ran in this process. */
    @Volatile var last: JSONObject? = null
        private set
    @Volatile private var lastAt = 0L
    private val lock = Mutex()

    /**
     * The app came to the foreground. Checks unless a check ran in the last few
     * minutes (an activity recreated on rotation is not a new launch), then installs
     * what is newer when [autoUpdate] is on.
     */
    fun onAppOpened() {
        if (clock() - lastAt < RECHECK_MS) return
        scope.launch { runCatching { checkAndMaybeApply() }.onFailure { Logs.app.add("update check: ${it.message}") } }
    }

    /** Auto update was just turned on: what is already newer is installed now. */
    fun onAutoUpdateEnabled() {
        scope.launch { runCatching { checkAndMaybeApply(force = true) }.onFailure { Logs.app.add("auto update: ${it.message}") } }
    }

    suspend fun check(): JSONObject = lock.withLock { checkLocked() }

    /** Installs the newest version of [tool] ("opencode"); returns it, or null if already current. */
    suspend fun apply(tool: String): String? = lock.withLock { applyLocked(tool) }

    private suspend fun checkAndMaybeApply(force: Boolean = false) = lock.withLock {
        if (!force && clock() - lastAt < RECHECK_MS) return@withLock
        val result = checkLocked(quiet = true)
        if (!autoUpdate()) return@withLock
        val newer = result.keys().asSequence().filter { result.optJSONObject(it)?.optBoolean("updateAvailable") == true }.toList()
        if (newer.isEmpty()) return@withLock
        for (tool in newer) {
            runCatching { applyLocked(tool) }.onFailure { Logs.app.add("auto update $tool: ${it.message}") }
        }
        checkLocked(quiet = true)
    }

    /**
     * [quiet]: a check nobody asked for (at launch) keeps its failures (offline, say)
     * in the log rather than on the Settings screen.
     */
    private suspend fun checkLocked(quiet: Boolean = false): JSONObject {
        val result = updater.check()
        if (quiet) {
            for (tool in result.keys().asSequence().toList()) {
                result.optJSONObject(tool)?.let { e -> e.remove("error")?.let { Logs.app.add("update check $tool: $it") } }
            }
        }
        last = result
        lastAt = clock()
        EventBus.emit("updates.changed", jsonOf("result" to result))
        return result
    }

    private suspend fun applyLocked(tool: String): String? {
        val t = Tools.Tool.entries.first { it.id == tool }
        val version = updater.apply(t) { progress ->
            EventBus.emit("update.progress", jsonOf("tool" to t.id, "progress" to progress.toDouble()))
        }
        if (version != null) applyWhenIdle(t.id)
        return version
    }

    /** What [BinaryUpdater] does, so the timing above can be tested without the network. */
    interface Updater {
        suspend fun check(): JSONObject
        suspend fun apply(tool: Tools.Tool, onProgress: (Float) -> Unit): String?
    }

    companion object {
        /** A launch within this long of the last check does not check again. */
        const val RECHECK_MS = 10 * 60_000L
    }
}
