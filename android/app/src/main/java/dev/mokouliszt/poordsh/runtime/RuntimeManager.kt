package dev.mokouliszt.poordsh.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import dev.mokouliszt.poordsh.backend.EventBus
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.service.Keepalive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException

/**
 * Makes the Ubuntu userland usable: unpacks the rootfs if needed and keeps the
 * guest's network files current. Engines are not started here; EngineManager
 * starts them when they are needed, so this is the only step that can take
 * over the screen (first run, or a broken install).
 */
class RuntimeManager(
    private val context: Context,
    private val paths: RuntimePaths,
    private val installer: RootfsInstaller,
    private val guestEnv: GuestEnvironment,
    private val keepalive: Keepalive,
) {
    enum class Phase { IDLE, INSTALLING, READY, FAILED }

    data class State(val phase: Phase, val progress: Float = 0f, val error: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _state = MutableStateFlow(State(Phase.IDLE))
    val state: StateFlow<State> = _state
    private var networkWatch = false

    fun ensureReady() {
        val phase = _state.value.phase
        if (phase == Phase.READY || phase == Phase.INSTALLING) return
        scope.launch { prepare() }
    }

    /** Suspends until the runtime is usable; throws if preparing it failed. */
    suspend fun awaitReady() {
        if (_state.value.phase != Phase.READY) prepare()
        if (_state.value.phase != Phase.READY) throw IOException(_state.value.error ?: "runtime is not ready")
    }

    /** Re-unpacks the image (user data in home is kept). Callers stop the engines first. */
    fun reinstall() {
        scope.launch {
            lock.withLock {
                paths.marker.delete()
                set(State(Phase.IDLE))
            }
            prepare()
        }
    }

    private suspend fun prepare() = lock.withLock {
        if (_state.value.phase == Phase.READY) return@withLock
        try {
            paths.ensureDirs()
            // Secret env files are deleted by the guest as soon as they are read;
            // anything left over is from a launch that never got that far.
            paths.tmp.listFiles { f -> f.name.startsWith(".env-") }?.forEach { it.delete() }
            if (!paths.prootBinary.isFile) {
                throw IllegalStateException("PRoot runner missing from APK (unsupported CPU?)")
            }
            val manifest = installer.manifest()
            if (!installer.isInstalled(manifest)) {
                keepalive.acquire(KEEPALIVE)
                try {
                    set(State(Phase.INSTALLING, 0f))
                    installer.install(manifest) { p -> set(State(Phase.INSTALLING, p)) }
                } finally {
                    keepalive.release(KEEPALIVE)
                }
            }
            guestEnv.writeNetworkFiles()
            watchNetwork()
            set(State(Phase.READY))
        } catch (e: Exception) {
            Logs.app.add("runtime failed: ${e.message}")
            set(State(Phase.FAILED, error = e.message ?: e.javaClass.simpleName))
        }
    }

    private fun watchNetwork() {
        if (networkWatch) return
        networkWatch = true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { guestEnv.writeNetworkFiles() }
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                runCatching { guestEnv.writeNetworkFiles() }
            }
        })
    }

    private fun set(s: State) {
        _state.value = s
        EventBus.emit("runtime.state", toJson())
    }

    fun toJson(): JSONObject {
        val s = _state.value
        return jsonOf("phase" to s.phase.name.lowercase(), "progress" to s.progress.toDouble(), "error" to s.error)
    }

    companion object {
        private const val KEEPALIVE = "install"
    }
}
