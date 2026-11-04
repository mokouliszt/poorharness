package dev.mokouliszt.poordsh.sandbox

import java.io.File
import java.io.InputStream

/**
 * A chat's sandbox in a cloud service (Tensorlake or Upstash Box), as the app
 * itself reaches it: commands, and files in and out. Skills and plugins sync,
 * attachments, outputs, git and the Files screen work through this, so they do
 * not depend on which service holds the chat. Calls block; run them off the main
 * thread.
 */
interface RemoteSandbox {
    /** Stable for the sandbox's life; used to tell whether work was already done in it. */
    val id: String

    data class Entry(val name: String, val isDir: Boolean, val size: Long?, val modifiedAt: Long?)
    data class RunResult(val exitCode: Int?, val stdout: String, val stderr: String)

    /** Runs [command] with [args] to completion (no shell unless [command] is one). */
    fun run(command: String, args: List<String> = emptyList(), cwd: String? = null, timeoutSecs: Int = 120): RunResult

    /** The file's bytes as a stream; the caller closes it. Null when the file does not exist. */
    fun read(path: String): InputStream?

    fun write(path: String, bytes: ByteArray)

    /** Streams a local file to [path], creating missing folders. [progress] gets the bytes sent so far. */
    fun upload(path: String, file: File, progress: ((Long) -> Unit)? = null)

    /** The folder's entries; empty when it does not exist. */
    fun listDir(path: String): List<Entry>

    /** Free bytes on the filesystem that holds (or will hold) [path]; null if it cannot be told. */
    fun freeBytes(path: String): Long? {
        val script = "p=\"\$1\"; while [ ! -e \"\$p\" ]; do p=\$(dirname \"\$p\"); done; df -B1 --output=avail \"\$p\""
        val r = run("sh", listOf("-c", script, "df", path), timeoutSecs = 30)
        return r.stdout.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.toLongOrNull()
    }
}

/**
 * One cloud service's chat sandboxes, by chat id: made on first use, paused
 * when idle, deleted with the chat. [acquire] and [release] hold a sandbox up
 * while something uses it ([use] does both).
 */
interface CloudSandboxes {
    data class Ready(val sandbox: RemoteSandbox, val fresh: Boolean)

    fun acquire(chatId: String, token: String)

    fun release(chatId: String, token: String)

    /** Creates, restores or wakes the chat's sandbox. [fresh] when its files are new (none from before). */
    suspend fun ensure(chatId: String): Ready

    /** Whether the chat has a sandbox there (without creating or waking it). */
    suspend fun exists(chatId: String): Boolean

    suspend fun delete(chatId: String)

    /** The notification's Stop: let every sandbox of this app pause, without waiting. */
    fun suspendAllNow()

    suspend fun <T> use(chatId: String, token: String, block: suspend (Ready) -> T): T {
        acquire(chatId, token)
        try {
            return block(ensure(chatId))
        } finally {
            release(chatId, token)
        }
    }
}
