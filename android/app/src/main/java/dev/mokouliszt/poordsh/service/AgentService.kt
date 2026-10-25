package dev.mokouliszt.poordsh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.mokouliszt.poordsh.MainActivity
import dev.mokouliszt.poordsh.PoorDshApp
import dev.mokouliszt.poordsh.R
import dev.mokouliszt.poordsh.backend.BackendState
import dev.mokouliszt.poordsh.backend.EventBus
import dev.mokouliszt.poordsh.runtime.Logs
import dev.mokouliszt.poordsh.runtime.RuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the process (and the engines under it) alive while an engine runs or
 * Ubuntu is being unpacked; [Keepalive] starts and stops it. Holds a partial
 * wake lock only while a chat is working.
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val working = ConcurrentHashMap.newKeySet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground()
        val graph = (application as PoorDshApp).graph
        scope.launch { graph.runtime.state.collect { refresh() } }
        graph.engines.backends.values.forEach { b -> scope.launch { b.state.collect { refresh() } } }
        scope.launch {
            EventBus.events.collect { e ->
                if (e.optString("type") == "chat.status") {
                    val sid = e.optString("sessionId")
                    if (e.optString("status") == "busy") working.add(sid) else working.remove(sid)
                    refresh()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // React at once: drop the notification, then let engines wind down in the background.
            stopping = true
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            (application as PoorDshApp).graph.shutdown()
            stopSelf()
        } else if (stopping) {
            // Started again (a new message) before this instance finished stopping.
            stopping = false
            goForeground()
            refresh()
        }
        // Not sticky: after a kill there is nothing to resume; engines start on demand.
        return START_NOT_STICKY
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(getString(R.string.notif_starting)),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    override fun onDestroy() {
        stopping = true
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        // Late events while shutting down must not bring the notification back.
        if (stopping) return
        val graph = (application as PoorDshApp).graph
        val text = when {
            working.isNotEmpty() -> resources.getQuantityString(R.plurals.notif_working, working.size, working.size)
            graph.runtime.state.value.phase == RuntimeManager.Phase.INSTALLING -> getString(R.string.notif_unpacking)
            graph.engines.backends.values.any { it.state.value.phase == BackendState.Phase.STARTING } ->
                getString(R.string.notif_starting)
            else -> getString(R.string.notif_idle)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text))
        if (working.isNotEmpty()) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PoorHarness:agent")
            .apply { acquire(2 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_app)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_agent), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL = "agent"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.mokouliszt.poordsh.STOP"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, AgentService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses foreground starts from the background; the work still runs.
                Logs.app.add("foreground service not started: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
