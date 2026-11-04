package dev.mokouliszt.poordsh.upstash

import java.util.UUID

/**
 * Runs commands in a box through `box-runner.sh` (next to this class in the
 * resources), so a box answers like a device jail's agent: output in files, so
 * `cmd &` returns; 1 MiB per stream; exit 124 past the timeout. Box's gateway ends
 * a call after 300 s, so a command allowed longer than [CALL_MAX_SECS] runs apart
 * and is waited for over several calls of at most [WAIT_SECS].
 */
object BoxRunner {
    const val CALL_MAX_SECS = 270
    const val WAIT_SECS = 240
    /** With no word from the app for this long, the box's watchdog ends what commands left running. */
    const val WATCHDOG_SECS = 600
    private const val PENDING = "poordsh-runner: pending"

    val SCRIPT: String by lazy {
        BoxRunner::class.java.getResourceAsStream("box-runner.sh")?.use { it.readBytes().decodeToString() }
            ?: error("box-runner.sh is missing from the app")
    }

    /** Runs the shell [command] in [cwd] (created if missing) for at most [timeoutSecs]. */
    fun run(
        api: BoxApi,
        boxId: String,
        command: String,
        cwd: String,
        timeoutSecs: Int,
        callMaxSecs: Int = CALL_MAX_SECS,
        waitSecs: Int = WAIT_SECS,
        jobs: String = "/tmp/.poordsh-run",
    ): BoxApi.Exec {
        val t = timeoutSecs.coerceAtLeast(1)
        if (t <= callMaxSecs) return api.exec(boxId, argv("run", cwd, "$t", command), readSecs = t + 10)
        val dir = "$jobs/${UUID.randomUUID()}"
        var r = api.exec(boxId, argv("start", dir, cwd, "$t", command, "$waitSecs"), readSecs = waitSecs + 10)
        val deadline = System.currentTimeMillis() + (t + 30) * 1000L
        while (isPending(r)) {
            if (System.currentTimeMillis() > deadline) return BoxApi.Exec(124, "", "[timed out after ${t}s]")
            r = api.exec(boxId, argv("wait", dir, "$waitSecs"), readSecs = waitSecs + 10)
        }
        return r
    }

    /** Tells the box the app is here: makes [folders] and starts the watchdog if none runs. */
    fun alive(api: BoxApi, boxId: String, limitSecs: Int = WATCHDOG_SECS, folders: List<String> = emptyList()): BoxApi.Exec =
        api.exec(boxId, argv("alive", "$limitSecs", *folders.toTypedArray()), readSecs = 60)

    fun isPending(r: BoxApi.Exec) = r.exitCode == 75 && r.stdout.isEmpty() && r.stderr.trim() == PENDING

    /** [command] and [args] as one shell command line. */
    fun join(command: String, args: List<String>): String = (listOf(command) + args).joinToString(" ") { quote(it) }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun argv(vararg args: String) = listOf("sh", "-c", SCRIPT, "poordsh-runner") + args
}
