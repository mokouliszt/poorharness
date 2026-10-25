package dev.mokouliszt.poordsh.phone

import dev.mokouliszt.poordsh.runtime.LogBuffer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A loopback bridge from opencode (which runs in its own PRoot on the phone) to a
 * chat's on-device jail. opencode's PoorHarness plugin, when a chat has no Tensorlake
 * sandbox, routes the official plugin's file and command operations here instead
 * of to Tensorlake; this server runs them in the chat's jail through
 * [PhoneOps] (the [PhoneSandboxManager] in the app).
 *
 * Loopback is shared by every app on the device, so each launch gets a random
 * bearer token (as opencode's own server does); requests without it are refused.
 * Only `127.0.0.1` is bound and only these operations are served: the four jail
 * operations, `/repo`, the plugin's repo tool for any chat ([repos]), and `/web`,
 * its webfetch and websearch fallback ([web]).
 */
class PhoneSandboxServer(
    private val sandboxes: PhoneOps,
    private val log: LogBuffer,
) {
    data class Endpoint(val url: String, val token: String)

    /**
     * The repo tool (docs/adr/git-repos.md): [sandbox] is what the plugin's map says
     * for the session (a Tensorlake sandbox name, or "phone:<chat>"); returns what the
     * model reads.
     */
    @Volatile var repos: (suspend (sandbox: String, action: String, name: String?) -> String)? = null

    /**
     * The web fallback (docs/adr/web-in-sandbox.md): [kind] is "fetch" (url, format) or
     * "search" (query, numResults); returns what the model reads, or throws. It runs
     * here so the TinyFish key stays in the app.
     */
    @Volatile var web: ((kind: String, request: JSONObject) -> String)? = null

    private var server: ServerSocket? = null
    private var endpoint: Endpoint? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "phone-http").apply { isDaemon = true } }

    /** Starts the server once and returns its loopback URL and token for this app process. */
    @Synchronized
    fun ensureStarted(): Endpoint {
        endpoint?.let { return it }
        val loopback = InetAddress.getByName("127.0.0.1")
        val sock = ServerSocket(0, 50, loopback)
        server = sock
        val token = randomHex(24)
        val ep = Endpoint("http://127.0.0.1:${sock.localPort}", token)
        endpoint = ep
        thread(name = "phone-http-accept", isDaemon = true) { acceptLoop(sock, token) }
        log.add("phone bridge on 127.0.0.1:${sock.localPort}")
        return ep
    }

    private fun acceptLoop(sock: ServerSocket, token: String) {
        while (!sock.isClosed) {
            val client = try { sock.accept() } catch (_: Exception) { break }
            pool.execute { runCatching { serve(client, token) } }
        }
    }

    private fun serve(socket: Socket, token: String) = socket.use {
        socket.soTimeout = 0
        val input = socket.getInputStream()
        val out = BufferedOutputStream(socket.getOutputStream())
        while (true) {
            val request = readRequest(input) ?: break
            val keepAlive = !request.headers["connection"].equals("close", true)
            val reply: JSONObject
            var status = 200
            if (request.headers["authorization"] != "Bearer $token") {
                status = 401; reply = JSONObject().put("error", "unauthorized")
            } else {
                try {
                    reply = handle(request)
                } catch (e: Exception) {
                    status = 500
                    write(out, 500, JSONObject().put("error", e.message ?: "error"))
                    if (keepAlive) continue else break
                }
            }
            write(out, status, reply)
            if (!keepAlive) break
        }
    }

    private fun handle(request: Request): JSONObject {
        val body = JSONObject(request.body.ifBlank { "{}" })
        if (request.path == "/repo") {
            val handler = repos ?: throw IOException("repositories are not available")
            val sandbox = body.optString("sandbox").ifBlank { throw IOException("sandbox required") }
            return JSONObject().put("text", runBlocking { handler(sandbox, body.optString("action"), body.optString("name").ifBlank { null }) })
        }
        if (request.path == "/web") {
            val handler = web ?: throw IOException("web fallback is not available")
            return JSONObject().put("text", handler(body.optString("kind"), body))
        }
        val chat = body.optString("chatId").ifBlank { throw IOException("chatId required") }
        val token = "op:" + UUID.randomUUID()
        sandboxes.acquire(chat, token)
        try {
            val op = when (request.path) {
                "/run" -> JSONObject().put("op", "run").put("command", body.optString("command"))
                    .put("cwd", body.optString("cwd", PhoneSandboxManager.WORKSPACE))
                    .put("timeout", (body.optLong("timeoutMs", 120_000) / 1000).coerceAtLeast(1))
                "/read" -> JSONObject().put("op", "read").put("path", body.optString("path"))
                "/write" -> JSONObject().put("op", "write").put("path", body.optString("path"))
                    .put("dataB64", body.optString("dataB64"))
                "/list" -> JSONObject().put("op", "list").put("path", body.optString("path"))
                else -> throw IOException("unknown path ${request.path}")
            }
            return runBlocking { sandboxes.op(chat, op) }
        } finally {
            sandboxes.release(chat, token)
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        endpoint = null
    }

    // --- minimal HTTP/1.1 ---------------------------------------------------

    private data class Request(val path: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val path = parts[1].substringBefore('?')
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        } else ""
        return Request(path, headers, body)
    }

    /** Reads one CRLF-terminated line (bytes), without buffering past it. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(c.toChar())
        }
    }

    private fun write(out: BufferedOutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
