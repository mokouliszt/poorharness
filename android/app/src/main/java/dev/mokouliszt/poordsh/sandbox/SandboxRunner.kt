package dev.mokouliszt.poordsh.sandbox

/**
 * `runner.sh` (next to this class in the resources): how commands run in a cloud
 * sandbox the app drives itself (Upstash Box, Modal), so both answer like a
 * device jail's agent: output in files, so `cmd &` returns; 1 MiB per stream;
 * exit 124 past the timeout.
 */
object SandboxRunner {
    val SCRIPT: String by lazy {
        SandboxRunner::class.java.getResourceAsStream("runner.sh")?.use { it.readBytes().decodeToString() }
            ?: error("runner.sh is missing from the app")
    }

    /** [command] and [args] as one shell command line. */
    fun join(command: String, args: List<String>): String = (listOf(command) + args).joinToString(" ") { quote(it) }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
