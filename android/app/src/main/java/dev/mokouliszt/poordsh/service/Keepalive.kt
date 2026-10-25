package dev.mokouliszt.poordsh.service

import android.content.Context

/**
 * Runs [AgentService] (and its notification) only while something needs the
 * process to survive in the background: unpacking Ubuntu, or an engine running.
 * Each holder names itself; the service stops when the last one lets go.
 */
class Keepalive(private val context: Context) {
    private val holders = HashSet<String>()

    @Synchronized
    fun acquire(holder: String) {
        if (holders.add(holder) && holders.size == 1) AgentService.start(context)
    }

    @Synchronized
    fun release(holder: String) {
        if (holders.remove(holder) && holders.isEmpty()) AgentService.stop(context)
    }

    /** Forgets every holder without touching the service (used when the user stops the app). */
    @Synchronized
    fun clear() = holders.clear()
}
