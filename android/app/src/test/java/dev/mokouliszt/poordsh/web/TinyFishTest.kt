package dev.mokouliszt.poordsh.web

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class TinyFishTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<Triple<String, String, String>>() // path+query, key, body
    private var status = 200
    private var reply = "{}"
    private var extraHeaders = mapOf<String, String>()
    private var key: String? = "k-test"

    private fun client() = TinyFish({ key }, searchUrl = "${server.url("/search")}", fetchUrl = "${server.url("/fetch")}")

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += Triple(request.path.orEmpty(), request.getHeader("X-API-Key").orEmpty(), request.body.readUtf8())
                    return MockResponse().setResponseCode(status).setBody(reply).apply { extraHeaders.forEach { (k, v) -> addHeader(k, v) } }
                }
            }
            start()
        }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun searchSendsTheKeyAndShapesResults() {
        reply = """{"query":"q","results":[
            {"position":1,"site_name":"A","title":"First","snippet":"one","url":"https://a.test/","date":"2026-01-02"},
            {"position":2,"site_name":"B","title":"Second","snippet":"two","url":"https://b.test/"},
            {"position":3,"site_name":"C","title":"Third","snippet":"three","url":"https://c.test/"}],"total_results":3,"page":0}"""
        val text = client().search("open code & more", 2)
        assertEquals("/search?query=open%20code%20%26%20more", seen.single().first.replace("+", "%20"))
        assertEquals("k-test", seen.single().second)
        assertTrue(text, text.startsWith("Title: First\nURL: https://a.test/\nPublished: 2026-01-02\nSite: A\nHighlights:\none\n---\nTitle: Second"))
        assertTrue(!text.contains("Third"))
    }

    @Test
    fun emptySearchSaysSo() {
        reply = """{"query":"q","results":[],"total_results":0,"page":0}"""
        assertEquals("No search results found. Please try a different query.", client().search("q"))
    }

    @Test
    fun fetchAsksForOnePageAndReturnsItsText() {
        reply = """{"results":[{"url":"https://a.test/","final_url":"https://a.test/","format":"markdown","text":"# Hi"}],"errors":[]}"""
        assertEquals("# Hi", client().fetch("https://a.test/", "text"))
        val body = JSONObject(seen.single().third)
        assertEquals("https://a.test/", body.getJSONArray("urls").getString(0))
        assertEquals("markdown", body.getString("format"))
        client().fetch("https://a.test/", "html")
        assertEquals("html", JSONObject(seen.last().third).getString("format"))
    }

    @Test
    fun fetchFailureCarriesTheReason() {
        reply = """{"results":[],"errors":[{"url":"https://a.test/","error":"page_not_found"}]}"""
        try {
            client().fetch("https://a.test/")
            fail()
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("page_not_found"))
        }
    }

    @Test
    fun rejectedKeyIsAnAuthError() {
        status = 401
        reply = """{"error":{"code":"INVALID_API_KEY","message":"bad key"},"request_id":"r"}"""
        try {
            client().check()
            fail()
        } catch (e: TinyFish.AuthException) {
            assertTrue(e.message, e.message!!.contains("INVALID_API_KEY"))
        }
    }

    @Test
    fun usageLimitsAreReadable() {
        status = 402
        reply = """{"error":{"code":"FREE_ALLOWANCE_EXHAUSTED","message":"used up"}}"""
        assertTrue(runCatching { client().search("q") }.exceptionOrNull()!!.message!!.contains("used up"))
        status = 429
        extraHeaders = mapOf("Retry-After" to "12")
        assertTrue(runCatching { client().search("q") }.exceptionOrNull()!!.message!!.contains("retry after 12s"))
    }

    @Test
    fun withoutAKeyNothingIsSent() {
        key = null
        assertTrue(runCatching { client().search("q") }.exceptionOrNull() is TinyFish.NoKeyException)
        assertTrue(seen.isEmpty())
    }
}
