package dev.mokouliszt.poordsh.runtime

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A program running inside the guest. stderr (and stdout, unless the caller
 * consumes it as a protocol stream) is copied line by line into a [LogBuffer].
 */
class GuestProcess private constructor(
    val name: String,
    private val process: Process,
    private val log: LogBuffer,
) {
    val stdin get() = process.outputStream
    val stdout: InputStream get() = process.inputStream

    val isAlive: Boolean get() = process.isAlive

    fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()

    fun waitFor(): Int = process.waitFor()

    /**
     * Stops PRoot and everything running under it.
     *
     * Android's Process.destroy() and destroyForcibly() both send SIGTERM, which
     * PRoot ignores, so on their own they leave the guest running. SIGQUIT makes
     * PRoot SIGKILL every guest process and exit. SIGKILL on PRoot itself would
     * leave its guest processes running untraced, so that is the last resort,
     * after they have been killed one by one.
     */
    fun stop(graceMillis: Long = 2000) {
        if (!process.isAlive) return
        val pid = pid()
        if (pid == null) {
            log.add("$name: pid unknown; sending SIGTERM")
            process.destroy()
            return
        }
        signal(pid, OsConstants.SIGQUIT)
        if (process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) return
        log.add("$name: PRoot still running after SIGQUIT; killing its processes")
        tracees(pid).forEach { signal(it, OsConstants.SIGKILL) }
        signal(pid, OsConstants.SIGKILL)
        process.waitFor(1, TimeUnit.SECONDS)
    }

    /** java.lang.Process has no public pid on Android; its toString() carries it. */
    private fun pid(): Int? =
        PID.find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
            ?: runCatching { process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process) }.getOrNull()

    private fun signal(pid: Int, signal: Int) {
        try {
            Os.kill(pid, signal)
        } catch (e: Exception) {
            log.add("$name: signal $signal to $pid failed: ${e.message}")
        }
    }

    companion object {
        private val PID = Regex("""pid=(\d+)""")
        private val TRACER = Regex("""^TracerPid:\s*(\d+)""", RegexOption.MULTILINE)

        fun start(name: String, builder: ProcessBuilder, log: LogBuffer, captureStdout: Boolean): GuestProcess {
            log.add("start $name")
            val p = builder.start()
            pump("$name-stderr", p.errorStream, log)
            if (captureStdout) pump("$name-stdout", p.inputStream, log)
            return GuestProcess(name, p, log)
        }

        /** Processes traced by [tracer], i.e. everything PRoot runs. Only our own UID's are visible. */
        private fun tracees(tracer: Int): List<Int> =
            File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().mapNotNull { dir ->
                val status = runCatching { File(dir, "status").readText() }.getOrNull() ?: return@mapNotNull null
                dir.name.toInt().takeIf { TRACER.find(status)?.groupValues?.get(1)?.toIntOrNull() == tracer }
            }

        private fun pump(threadName: String, stream: InputStream, log: LogBuffer) {
            thread(name = threadName, isDaemon = true) {
                try {
                    stream.bufferedReader().forEachLine { log.add(it) }
                } catch (_: Exception) {
                    // stream closed when the process exits
                }
            }
        }
    }
}
