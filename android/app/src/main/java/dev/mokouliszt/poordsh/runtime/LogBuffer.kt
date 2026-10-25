package dev.mokouliszt.poordsh.runtime

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Bounded, thread-safe log of recent lines from one source (installer, opencode,
 * app, tensorlake). The diagnostics screen shows these so failures on a device can be
 * reported without adb.
 */
class LogBuffer(val source: String, private val capacity: Int = 600) {
    private val lines = ArrayDeque<String>(capacity)
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(line: String) {
        val clean = redact(line.trimEnd())
        if (clean.isEmpty()) return
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast("${stamp.format(Date())} $clean")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    companion object {
        // Logs can be copied out of the app; keep obvious secrets out of them.
        private val SECRET = Regex("""(tl_apiKey_|nvapi-|sk-|Bearer\s+)[A-Za-z0-9._\-]{8,}""")
        fun redact(s: String): String = SECRET.replace(s) { it.groupValues[1] + "…" }
    }
}

/** All log sources, keyed by name, shared across the app. */
object Logs {
    val installer = LogBuffer("installer")
    val opencode = LogBuffer("opencode")
    val app = LogBuffer("app")
    val tensorlake = LogBuffer("tensorlake")
    val all get() = listOf(app, tensorlake, installer, opencode)
}
