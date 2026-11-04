package dev.mokouliszt.poordsh.upstash

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The requests BoxApi sends and how it reads Box's answers, against the shapes seen in November 2026. */
class BoxApiTest {
    private val server = MockWebServer()
    private val api = BoxApi({ "box_test" }, base = server.url("/").toString().trimEnd('/'))

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sendsTheKeyAndCreatesWithALabel() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"devoted-grubworm-1","name":"poordsh-c-1","status":"creating","labels":["poordsh"]}"""))
        val box = api.create("poordsh-c-1", listOf("poordsh"))
        assertEquals("devoted-grubworm-1", box.id)
        assertEquals("creating", box.status)
        val req = server.takeRequest()
        assertEquals("box_test", req.getHeader("X-Box-Api-Key"))
        assertEquals("/v2/box", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("poordsh-c-1", body.getString("name"))
        assertEquals("poordsh", body.getJSONArray("labels").getString(0))
    }

    @Test
    fun aMissingBoxIsNull() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Box not found"}"""))
        assertNull(api.get("poordsh-nope"))
    }

    @Test
    fun execSendsAnArgvAndReadsBothStreams() {
        server.enqueue(MockResponse().setBody("""{"exit_code":3,"output":"out\n","error":"err\n","cpu_ns":1000}"""))
        val r = api.exec("b1", listOf("sh", "-c", "exit 3"))
        assertEquals(BoxApi.Exec(3, "out\n", "err\n"), r)
        val req = server.takeRequest()
        assertEquals("/v2/box/b1/exec", req.path)
        assertEquals("sh", JSONObject(req.body.readUtf8()).getJSONArray("command").getString(0))
    }

    @Test
    fun writesBase64AndListsAMissingFolderAsEmpty() {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        server.enqueue(MockResponse().setBody("""{"files":null}"""))
        api.write("b1", "/tmp/workspace/a.bin", byteArrayOf(0, 1, 2, -1))
        val w = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("base64", w.getString("encoding"))
        assertEquals("AAEC/w==", w.getString("content"))
        assertTrue(api.listDir("b1", "/tmp/workspace/nope").isEmpty())
        assertEquals("/v2/box/b1/files/list?folder=%2Ftmp%2Fworkspace%2Fnope", server.takeRequest().path)
    }

    @Test
    fun readsRawBytesAndNullForAMissingFile() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(9, 8, 7))))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"file not found"}"""))
        assertEquals(listOf<Byte>(9, 8, 7), api.read("b1", "/tmp/workspace/x")!!.use { it.readBytes() }.toList())
        assertNull(api.read("b1", "/tmp/workspace/y"))
    }

    @Test
    fun sumsTheMonthsCpu() {
        server.enqueue(MockResponse().setBody("""{"month":"2026-11","box_billing":[{"cpu_ns":278095000},{"cpu_ns":73078000}]}"""))
        assertEquals(351173000L, api.cpuNs("2026-11"))
        assertEquals("/v2/box/billing?month=2026-11", server.takeRequest().path)
    }

    @Test
    fun classifiesRefusals() {
        assertTrue(BoxApi.classify(401, "Invalid box API key") is BoxApi.AuthException)
        assertTrue(
            BoxApi.classify(409, "Box limit reached. Maximum 10 boxes allowed in the Free plan. Please upgrade by adding a payment method")
                is BoxApi.BoxCountLimit,
        )
        // A name in use is not a usage problem.
        assertTrue(BoxApi.classify(409, "Box name \"x\" is already in use") is BoxApi.ApiException)
        assertTrue(BoxApi.classify(402, "anything") is BoxApi.UsageLimit)
        assertTrue(BoxApi.classify(429, "slow down") is BoxApi.UsageLimit)
        assertTrue(BoxApi.classify(403, "Monthly CPU hours exceeded") is BoxApi.UsageLimit)
        assertTrue(BoxApi.classify(500, "Failed to execute command") is BoxApi.ApiException)
        assertTrue(BoxApi.classify(404, "Box not found") is BoxApi.ApiException)
    }

    @Test
    fun uploadsAsAForm() {
        server.enqueue(MockResponse().setBody("""{"count":1,"success":true}"""))
        val f = kotlin.io.path.createTempFile().toFile().apply { writeBytes(Base64.getDecoder().decode("aGVsbG8=")) }
        try {
            api.upload("b1", "/tmp/workspace/in/hello.txt", f)
        } finally {
            f.delete()
        }
        val req = server.takeRequest()
        assertEquals("/v2/box/b1/files/upload", req.path)
        val text = req.body.readUtf8()
        assertTrue(text.contains("name=\"paths\""))
        assertTrue(text.contains("/tmp/workspace/in/hello.txt"))
        assertTrue(text.contains("name=\"files\"; filename=\"hello.txt\""))
        assertTrue(text.contains("hello"))
    }
}
