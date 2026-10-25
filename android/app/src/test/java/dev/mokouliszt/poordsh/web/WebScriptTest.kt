package dev.mokouliszt.poordsh.web

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * assets/opencode/poordsh-web.py, run the way the plugin runs it (python3 -c, the
 * script and its arguments as base64) against a local server. Skipped without python3.
 */
class WebScriptTest {
    private lateinit var server: MockWebServer
    private val page = """<html><head><title>T</title><style>p{}</style></head><body><h1>Hi &amp; bye</h1>
        <p>A <b>bold</b> <a href="/x">link</a>.</p><ul><li>one</li><li>two</li></ul><script>var a=1</script></body></html>"""

    @Before
    fun setUp() {
        assumeTrue(File("/usr/bin/python3").canExecute())
        server = MockWebServer().apply {
            fun reply(status: Int, type: String, body: ByteArray) =
                MockResponse().setResponseCode(status).addHeader("Content-Type", type).setBody(Buffer().write(body))
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/page" -> reply(200, "text/html; charset=utf-8", page.toByteArray())
                    "/img" -> reply(200, "image/png", byteArrayOf(1, 2, 3))
                    "/bin" -> reply(200, "application/octet-stream", byteArrayOf(0, -1, -2, 0))
                    "/limit" -> reply(429, "text/plain", "slow down".toByteArray())
                    "/rpc" -> {
                        val q = JSONObject(request.body.readUtf8()).getJSONObject("params").getJSONObject("arguments").getString("query")
                        val body = when (q) {
                            "limit" -> """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"Rate limit exceeded"}}"""
                            "empty" -> """{"jsonrpc":"2.0","id":1,"result":{"content":[]}}"""
                            else -> """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Title: $q"}]}}"""
                        }
                        reply(200, "text/event-stream", "event: message\ndata: $body\n\n".toByteArray())
                    }
                    else -> reply(404, "text/plain", "no".toByteArray())
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.shutdown()
    }

    private fun run(request: JSONObject): JSONObject {
        val b64 = { s: ByteArray -> Base64.getEncoder().encodeToString(s) }
        val script = b64(File("src/main/assets/opencode/poordsh-web.py").readBytes())
        val args = b64(request.toString().toByteArray())
        val p = ProcessBuilder(
            "python3", "-c", "import sys,base64;exec(base64.b64decode(sys.argv.pop(1)))", script, args,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        p.waitFor()
        val line = out.substringAfterLast("\u0001POORDSH-WEB\u0001").lines().first()
        return JSONObject(line)
    }

    private fun url(path: String) = server.url(path).toString().replace("localhost", "127.0.0.1")
    private fun fetch(path: String, format: String = "markdown") =
        run(JSONObject().put("kind", "fetch").put("url", url(path)).put("format", format).put("keepHttp", true))

    private fun search(query: String) =
        run(JSONObject().put("kind", "search").put("query", query).put("endpoint", url("/rpc")))

    @Test
    fun convertsHtmlToMarkdownTextOrKeepsIt() {
        val md = fetch("/page").getString("text")
        assertEquals("# Hi & bye\n\nA **bold** [link](${url("/x")}).\n\n- one\n- two", md)
        assertEquals("Hi & bye\n\nA bold link.\n\n- one\n- two", fetch("/page", "text").getString("text"))
        assertTrue(fetch("/page", "html").getString("text").contains("<script>"))
    }

    @Test
    fun imagesComeBackAsBase64() {
        val r = fetch("/img")
        assertEquals("image", r.getString("kind"))
        assertEquals("image/png", r.getString("mime"))
        assertEquals("AQID", r.getString("b64"))
    }

    @Test
    fun binaryIsRefusedWithAHint() {
        val r = fetch("/bin")
        assertFalse(r.getBoolean("ok"))
        assertTrue(r.getString("error").contains("curl"))
    }

    @Test
    fun onlyUsageLimitsAreMarkedAsLimits() {
        val limited = fetch("/limit")
        assertFalse(limited.getBoolean("ok"))
        assertTrue(limited.optBoolean("limit"))
        val gone = fetch("/gone")
        assertFalse(gone.getBoolean("ok"))
        assertFalse(gone.optBoolean("limit"))
        assertFalse(run(JSONObject().put("kind", "fetch").put("url", "ftp://x")).optBoolean("limit"))
    }

    @Test
    fun searchReadsTheStreamAndMarksLimits() {
        assertEquals("Title: kotlin", search("kotlin").getString("text"))
        val limited = search("limit")
        assertFalse(limited.getBoolean("ok"))
        assertTrue(limited.optBoolean("limit"))
        val empty = search("empty")
        assertFalse(empty.getBoolean("ok"))
        assertFalse(empty.optBoolean("limit"))
    }
}
