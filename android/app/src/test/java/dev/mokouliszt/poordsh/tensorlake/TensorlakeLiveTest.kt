package dev.mokouliszt.poordsh.tensorlake

import dev.mokouliszt.poordsh.runtime.LogBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Runs against the real Tensorlake API when TENSORLAKE_API_KEY is set (skipped
 * otherwise): sandbox lifecycle, files, and the free plan's one-sandbox limit.
 */
class TensorlakeLiveTest {
    private val key = System.getenv("TENSORLAKE_API_KEY")
    private val log = LogBuffer("test")

    @Test
    fun sandboxLifecycleAndFiles() = runBlocking {
        assumeTrue("TENSORLAKE_API_KEY not set", !key.isNullOrBlank())
        val api = TensorlakeApi({ key })
        val mgr = SandboxManager(api, log, idleMs = 1_500)
        val a = "poordsh-test-" + UUID.randomUUID().toString().take(8)
        val b = "poordsh-test-" + UUID.randomUUID().toString().take(8)
        try {
            val first = mgr.ensure(a)
            assertTrue(first.fresh)
            assertTrue(first.sandbox.running)

            // Files round trip in the workspace.
            api.run(first.sandbox, "mkdir", listOf("-p", SandboxManager.WORKSPACE))
            api.write(first.sandbox, "${SandboxManager.WORKSPACE}/hello.txt", "hi\n".toByteArray())
            assertEquals("hi\n", api.read(first.sandbox, "${SandboxManager.WORKSPACE}/hello.txt")!!.use { String(it.readBytes()) })
            assertTrue(api.listDir(first.sandbox, SandboxManager.WORKSPACE).any { it.name == "hello.txt" && !it.isDir })
            val r = api.run(first.sandbox, "bash", listOf("-c", "echo out; echo err >&2; exit 3"))
            assertEquals(3, r.exitCode)
            assertEquals("out\n", r.stdout)
            assertEquals("err\n", r.stderr)

            // Idle: suspended by the manager; the next use resumes it with its files.
            mgr.acquire(a, "t")
            mgr.release(a, "t")
            waitFor(api, a, "suspended")
            val again = mgr.ensure(a)
            assertFalse(again.fresh)
            assertEquals("hi\n", api.read(again.sandbox, "${SandboxManager.WORKSPACE}/hello.txt")!!.use { String(it.readBytes()) })

            // The free plan runs one sandbox: starting another suspends the idle one.
            val second = mgr.ensure(b)
            assertTrue(second.sandbox.running)
            assertEquals("suspended", api.get(a)!!.status)

            // A busy sandbox is never suspended to make room.
            mgr.acquire(b, "busy")
            val busy = runCatching { mgr.ensure(a) }.exceptionOrNull()
            assertTrue("expected Busy, got $busy", busy is SandboxManager.Busy)
            mgr.release(b, "busy")
        } finally {
            runCatching { api.delete(a) }
            runCatching { api.delete(b) }
        }
    }

    private suspend fun waitFor(api: TensorlakeApi, name: String, status: String) {
        val deadline = System.currentTimeMillis() + 60_000
        while (api.get(name)?.status != status) {
            check(System.currentTimeMillis() < deadline) { "$name never became $status" }
            delay(500)
        }
    }
}
