package dev.mokouliszt.poordsh.runtime

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.Inet4Address

/**
 * Files the guest expects that Android does not provide the usual way.
 *
 * - /etc/resolv.conf: Android has no resolver config file; the device's current
 *   DNS servers are written in, with public resolvers as fallback.
 * - /proc entries that SELinux hides from apps (loadavg, stat, ...): programs
 *   such as Node/Bun read them, so readable stand-ins are bind-mounted over them.
 */
class GuestEnvironment(private val context: Context, private val paths: RuntimePaths) {

    fun writeNetworkFiles() {
        val etc = File(paths.rootfs, "etc")
        etc.mkdirs()
        val servers = currentDnsServers().ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        val resolv = File(etc, "resolv.conf")
        if (SafeFs.isSymlink(resolv)) resolv.delete()
        resolv.writeText(servers.joinToString("\n", postfix = "\n") { "nameserver $it" })
        File(etc, "hosts").writeText(
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n",
        )
    }

    private fun currentDnsServers(): List<String> = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork)
        props?.dnsServers.orEmpty()
            // IPv4 first: some resolvers misbehave with link-local v6 entries.
            .sortedBy { if (it is Inet4Address) 0 else 1 }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
            .take(3)
    } catch (_: Exception) {
        emptyList()
    }

    /** Returns (fake file, /proc path) pairs for /proc files the app cannot read. */
    fun fakeProcBinds(): List<Pair<String, String>> {
        paths.fakeProc.mkdirs()
        val binds = mutableListOf<Pair<String, String>>()
        for ((name, content) in FAKE_PROC) {
            val real = File("/proc/$name")
            val readable = try { real.canRead() && real.readBytes().isNotEmpty() } catch (_: Exception) { false }
            if (!readable) {
                val fake = File(paths.fakeProc, name.replace('/', '_'))
                if (!fake.exists()) fake.writeText(content)
                binds += fake.path to "/proc/$name"
            }
        }
        return binds
    }

    companion object {
        private val FAKE_PROC = linkedMapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "stat" to """
                cpu  1957 0 2877 93280 262 342 254 87 0 0
                cpu0 31 0 226 12027 82 10 4 9 0 0
                intr 63361 0 9 0 0 0 0 0 0 0 0 0 0 0 0 0 0
                ctxt 38014093
                btime 1694292441
                processes 26442
                procs_running 1
                procs_blocked 0
                softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
            """.trimIndent() + "\n",
            "uptime" to "124.08 932.80\n",
            "version" to "Linux version 6.1.0-PRoot (poordsh@android) #1 SMP PREEMPT\n",
            "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n",
            "sys/kernel/cap_last_cap" to "40\n",
            "sys/fs/inotify/max_user_watches" to "4096\n",
        )
    }
}
