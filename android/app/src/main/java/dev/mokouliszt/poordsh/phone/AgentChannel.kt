package dev.mokouliszt.poordsh.phone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The [PhoneAgent] protocol over its pipes: one JSON object per line each way,
 * replies matched to requests by `id`, so several tool calls can be in flight at
 * once. Plain JVM code (no Android), tested against the real agent on the build host.
 */
class AgentChannel(
    private val name: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val onClosed: () -> Unit = {},
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1)
    private val writeLock = Any()

    /** Completes when the agent has said it is ready. */
    val ready = CompletableDeferred<Unit>()

    fun start() {
        thread(name = "agent-$name-reader", isDaemon = true) {
            try {
                input.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val msg = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    if (!ready.isCompleted && msg.optBoolean("ready")) {
                        ready.complete(Unit)
                        return@forEachLine
                    }
                    if (!msg.isNull("id")) pending.remove(msg.optLong("id"))?.complete(msg)
                }
            } catch (_: Exception) {
                // pipe closed when the agent exits
            }
            fail(IOException("phone sandbox stopped"))
            onClosed()
        }
    }

    /** Sends [request] and returns the agent's reply. */
    suspend fun call(request: JSONObject, timeoutMs: Long): JSONObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            request.put("id", id)
            synchronized(writeLock) {
                output.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun fail(e: Throwable) {
        if (!ready.isCompleted) ready.completeExceptionally(e)
        pending.values.toList().forEach { it.completeExceptionally(e) }
        pending.clear()
    }
}
