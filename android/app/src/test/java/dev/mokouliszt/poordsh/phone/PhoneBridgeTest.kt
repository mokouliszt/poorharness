package dev.mokouliszt.poordsh.phone

import dev.mokouliszt.poordsh.runtime.LogBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * The device bridge end to end on the build host: OkHttp (standing in for
 * opencode's plugin) -> [PhoneSandboxServer] -> [AgentChannel] -> the real
 * [PhoneAgent] in python3. Only PRoot is left out; paths are host paths here.
 * Skipped where python3 is missing.
 */
class PhoneBridgeTest {
    private lateinit var dir: File
    private lateinit var agent: Process
    private lateinit var server: PhoneSandboxServer
    private lateinit var ep: PhoneSandboxServer.Endpoint
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()

    @Before
    fun setUp() {
        assumeTrue(File("/usr/bin/python3").canExecute())
        dir = Files.createTempDirectory("phone").toFile()
        val script = File(dir, "agent.py").apply { writeText(PhoneAgent.SOURCE) }
        agent = ProcessBuilder("python3", "-I", script.path).directory(dir).start()
        val channel = AgentChannel("test", agent.inputStream, agent.outputStream).apply { start() }
        runBlocking { withTimeout(10_000) { channel.ready.await() } }
        val ops = object : PhoneOps {
            override fun acquire(name: String, token: String) {}
            override fun release(name: String, token: String) {}
            override suspend fun op(name: String, request: JSONObject) = channel.call(request, 30_000)
        }
        server = PhoneSandboxServer(ops, LogBuffer("test"))
        ep = server.ensureStarted()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        if (::agent.isInitialized) agent.destroy()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun post(path: String, body: JSONObject, token: String? = ep.token): Pair<Int, JSONObject> {
        val req = Request.Builder().url(ep.url + path).post(body.toString().toRequestBody(json))
            .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        http.newCall(req).execute().use { return it.code to JSONObject(it.body!!.string()) }
    }

    @Test
    fun refusesWithoutToken() {
        assertEquals(401, post("/run", JSONObject().put("chatId", "c").put("command", "true"), token = null).first)
        assertEquals(401, post("/run", JSONObject().put("chatId", "c").put("command", "true"), token = "wrong").first)
    }

    @Test
    fun runsCommandsInTheirFolder() {
        val (code, r) = post("/run", JSONObject().put("chatId", "c").put("command", "echo hi; pwd; echo oops >&2; exit 3").put("cwd", dir.path))
        assertEquals(200, code)
        assertEquals(3, r.getInt("exitCode"))
        assertEquals("hi\n${dir.canonicalPath}\n", r.getString("stdout"))
        assertEquals("oops\n", r.getString("stderr"))
    }

    @Test
    fun writesReadsAndListsFiles() {
        val bytes = byteArrayOf(0, 1, 2, -1, 'a'.code.toByte(), '\n'.code.toByte())
        val path = File(dir, "sub/data.bin").path
        val b64 = Base64.getEncoder().encodeToString(bytes)
        assertEquals(200, post("/write", JSONObject().put("chatId", "c").put("path", path).put("dataB64", b64)).first)
        val read = post("/read", JSONObject().put("chatId", "c").put("path", path)).second
        assertTrue(Base64.getDecoder().decode(read.getString("dataB64")).contentEquals(bytes))
        val list = post("/list", JSONObject().put("chatId", "c").put("path", File(dir, "sub").path)).second
        val e = list.getJSONArray("entries").getJSONObject(0)
        assertEquals("data.bin", e.getString("name"))
        assertEquals(bytes.size, e.getInt("size"))
        // A missing file comes back as an error the plugin raises.
        assertTrue(post("/read", JSONObject().put("chatId", "c").put("path", File(dir, "nope").path)).second.has("error"))
    }

    @Test
    fun servesConcurrentCallsOverOnePipe() = runBlocking {
        val started = System.currentTimeMillis()
        val results = (1..8).map { i ->
            async(Dispatchers.IO) {
                post("/run", JSONObject().put("chatId", "c").put("command", "sleep 0.5; echo $i").put("cwd", dir.path)).second
            }
        }.awaitAll()
        assertEquals((1..8).map { "$it\n" }, results.map { it.getString("stdout") })
        // Eight half-second commands overlap instead of queueing behind each other.
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun backgroundedCommandDoesNotHoldTheReply() {
        val started = System.currentTimeMillis()
        val r = post("/run", JSONObject().put("chatId", "c").put("command", "sleep 20 & echo started").put("cwd", dir.path)).second
        assertEquals("started\n", r.getString("stdout"))
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun timesOutAndKillsTheCommand() {
        val r = post("/run", JSONObject().put("chatId", "c").put("command", "sleep 30").put("cwd", dir.path).put("timeoutMs", 1500)).second
        assertEquals(124, r.getInt("exitCode"))
    }

    @Test
    fun unknownPathIsAnError() {
        assertEquals(500, post("/exec", JSONObject().put("chatId", "c")).first)
    }

    @Test
    fun webRouteAnswersWithTheHandlersText() {
        server.web = { kind, request -> "$kind:" + request.optString("query", request.optString("url")) }
        val (code, r) = post("/web", JSONObject().put("kind", "search").put("query", "q"))
        assertEquals(200, code)
        assertEquals("search:q", r.getString("text"))
        assertEquals(401, post("/web", JSONObject().put("kind", "fetch"), token = null).first)
        server.web = { _, _ -> throw java.io.IOException("TinyFish: rate limited") }
        val failed = post("/web", JSONObject().put("kind", "fetch"))
        assertEquals(500, failed.first)
        assertEquals("TinyFish: rate limited", failed.second.getString("error"))
        server.web = null
        assertEquals(500, post("/web", JSONObject().put("kind", "fetch")).first)
    }

    @Test
    fun runsTheWebScriptWithThePluginsCommand() {
        // The plugin's command (poordsh-sandboxes.js, inSandbox) goes through the jail's `sh -c`.
        val b64 = { b: ByteArray -> Base64.getEncoder().encodeToString(b) }
        val script = b64(File("src/main/assets/opencode/poordsh-web.py").readBytes())
        val args = b64("""{"kind":"fetch","url":"ftp://nowhere"}""".toByteArray())
        val command = "python3 -c 'import sys,base64;exec(base64.b64decode(sys.argv.pop(1)))' $script $args"
        val r = post("/run", JSONObject().put("chatId", "c").put("command", command).put("cwd", dir.path)).second
        assertEquals(0, r.getInt("exitCode"))
        val line = r.getString("stdout").substringAfter("\u0001POORDSH-WEB\u0001").lines().first()
        assertEquals("URL must start with http:// or https://", JSONObject(line).getString("error"))
    }
}
