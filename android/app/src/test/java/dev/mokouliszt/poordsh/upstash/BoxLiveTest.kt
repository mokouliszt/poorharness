package dev.mokouliszt.poordsh.upstash

import dev.mokouliszt.poordsh.runtime.LogBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

/**
 * Runs against the real Upstash Box when UPSTASH_BOX_API_KEY is set (skipped
 * otherwise; use a test account): one box's life, files, the runner, pausing and
 * the bridge's operations. Makes one box, `poordsh-test-*`, and deletes it.
 */
class BoxLiveTest {
    private val key = System.getenv("UPSTASH_BOX_API_KEY")

    @Test
    fun aChatsBoxEndToEnd() = runBlocking {
        assumeTrue("UPSTASH_BOX_API_KEY not set", !key.isNullOrBlank())
        val api = BoxApi({ key })
        val dir = Files.createTempDirectory("box-live").toFile()
        val boxes = BoxSandboxes(api, File(dir, "state.json"), LogBuffer("test"), idleMs = 1_000)
        val chat = "test-" + UUID.randomUUID().toString().take(8)
        try {
            val first = boxes.ensure(chat)
            assertTrue(first.fresh)
            val sb = first.sandbox
            val listed = api.list(BoxSandboxes.LABEL).first { it.name == BoxSandboxes.nameFor(chat) }
            assertEquals(sb.id, listed.id)

            // Files.
            sb.write("/tmp/workspace/hello.txt", "hi\n".toByteArray())
            assertEquals("hi\n", sb.read("/tmp/workspace/hello.txt")!!.use { String(it.readBytes()) })
            val up = File(dir, "up.bin").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
            sb.upload("/tmp/workspace/in/deep/up.bin", up)
            assertTrue(sb.listDir("/tmp/workspace/in/deep").any { it.name == "up.bin" && !it.isDir && it.size == 300_000L })
            assertTrue(sb.listDir("/tmp/workspace/nope").isEmpty())

            // Commands, as the app and opencode's tools run them.
            val r = sb.run("sh", listOf("-c", "echo out; echo err >&2; exit 3"), cwd = "/tmp/workspace")
            assertEquals(RemoteResult(3, "out\n", "err\n"), RemoteResult(r.exitCode, r.stdout, r.stderr))
            val t0 = System.currentTimeMillis()
            val bg = BoxRunner.run(api, sb.id, "(sleep 4; echo later > bg.txt) & echo started", "/tmp/workspace", 30)
            assertEquals("started\n", bg.stdout)
            assertTrue(System.currentTimeMillis() - t0 < 4_000)
            delay(5_000)
            assertEquals("later\n", sb.read("/tmp/workspace/bg.txt")!!.use { String(it.readBytes()) })
            val long = BoxRunner.run(api, sb.id, "sleep 7; echo long", "/tmp/workspace", 60, callMaxSecs = 2, waitSecs = 3)
            assertEquals(0, long.exitCode)
            assertEquals("long\n", long.stdout)
            val timedOut = BoxRunner.run(api, sb.id, "sleep 30", "/", 2)
            assertEquals(124, timedOut.exitCode)

            // The bridge's operations (what opencode's tools get).
            val ops = BoxOps(boxes, api)
            val run = ops.op(chat, JSONObject().put("op", "run").put("command", "pwd; ls").put("cwd", "/tmp/workspace").put("timeout", 30))
            assertEquals("/tmp/workspace", run.getString("stdout").lines().first())
            val write = ops.op(chat, JSONObject().put("op", "write").put("path", "/tmp/workspace/w/x.txt").put("dataB64", Base64.getEncoder().encodeToString("x".toByteArray())))
            assertTrue(write.getBoolean("ok"))
            val read = ops.op(chat, JSONObject().put("op", "read").put("path", "/tmp/workspace/w/x.txt"))
            assertEquals("x", String(Base64.getDecoder().decode(read.getString("dataB64"))))
            val list = ops.op(chat, JSONObject().put("op", "list").put("path", "/tmp/workspace"))
            assertTrue(list.getJSONArray("entries").toString().contains("hello.txt"))

            // Idle: paused by the manager; the next use wakes it with its files.
            boxes.acquire(chat, "t")
            boxes.release(chat, "t")
            var status = ""
            for (i in 0 until 30) {
                status = api.get(BoxSandboxes.nameFor(chat))!!.status
                if (status == "paused") break
                delay(1_000)
            }
            assertEquals("paused", status)
            val again = boxes.ensure(chat)
            assertFalse(again.fresh)
            assertEquals("hi\n", again.sandbox.read("/tmp/workspace/hello.txt")!!.use { String(it.readBytes()) })

            // Moving out with the file API alone.
            val out = File(dir, "moved")
            assertTrue(boxes.downloadWorkspace(chat, out))
            assertEquals("later\n", File(out, "bg.txt").readText())

            // The month's CPU is readable.
            assertTrue(boxes.usage(refresh = true).usedHours >= 0.0)
        } finally {
            boxes.delete(chat)
            dir.deleteRecursively()
        }
        assertFalse(boxes.exists(chat))
    }

    private data class RemoteResult(val exitCode: Int?, val stdout: String, val stderr: String)
}
