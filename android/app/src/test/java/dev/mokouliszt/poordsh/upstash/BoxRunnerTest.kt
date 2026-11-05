package dev.mokouliszt.poordsh.upstash

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * runner.sh through BoxApi, run on the build host by [FakeBox]: the replies a
 * device jail's agent gives, and commands longer than one gateway call.
 */
class BoxRunnerTest {
    private val box = FakeBox().apply { runLocally = true }
    private val api = box.api()
    private lateinit var dir: File
    private lateinit var id: String

    @Before
    fun setUp() {
        assumeTrue(File("/usr/bin/timeout").canExecute() && File("/usr/bin/setsid").canExecute())
        dir = Files.createTempDirectory("runner").toFile()
        box.stateDir = File(dir, "state")
        id = api.create("poordsh-test", listOf("poordsh")).id
    }

    @After
    fun tearDown() {
        box.server.shutdown()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun run(command: String, timeout: Int = 30, callMax: Int = BoxRunner.CALL_MAX_SECS, wait: Int = BoxRunner.WAIT_SECS) =
        BoxRunner.run(api, id, command, File(dir, "work").path, timeout, callMax, wait, jobs = File(dir, "jobs").path)

    @Test
    fun runsInItsFolderWithBothStreams() {
        val r = run("echo hi; pwd; echo oops >&2; exit 3")
        assertEquals(3, r.exitCode)
        assertEquals("hi\n${File(dir, "work").canonicalPath}\n", r.stdout)
        assertEquals("oops\n", r.stderr)
    }

    @Test
    fun aBackgroundProcessDoesNotHoldTheCall() {
        val t0 = System.currentTimeMillis()
        val r = run("(sleep 3; echo done > bg.txt) & echo started")
        assertEquals(0, r.exitCode)
        assertEquals("started\n", r.stdout)
        assertTrue("returned after ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_500)
        Thread.sleep(3_500)
        assertEquals("done\n", File(dir, "work/bg.txt").readText())
    }

    @Test
    fun stopsAtItsTimeout() {
        val r = run("sleep 10", timeout = 1)
        assertEquals(124, r.exitCode)
        assertTrue(r.stderr, r.stderr.contains("[timed out after 1s]"))
    }

    @Test
    fun capsEachStream() {
        val r = run("head -c 1100000 /dev/zero | tr '\\0' a")
        assertTrue(r.stdout.endsWith("[output truncated]"))
        assertEquals(1048576 + "\n[output truncated]".length, r.stdout.length)
    }

    @Test
    fun aLongCommandIsWaitedForOverSeveralCalls() {
        val r = run("sleep 3; echo long; exit 4", timeout = 20, callMax = 1, wait = 1)
        assertEquals(4, r.exitCode)
        assertEquals("long\n", r.stdout)
        // start, then at least two waits; the job's folder is cleaned up.
        assertTrue(box.execs.size >= 3)
        assertTrue(box.execs.any { it[4] == "start" } && box.execs.any { it[4] == "wait" })
        assertTrue(File(dir, "jobs").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theWatchdogEndsWhatIsLeftRunningOnceTheAppIsGone() {
        // A background loop the agent forgot, as on a phone that then died.
        val r = run("(while :; do sleep 0.2; done) & echo \$! > loop.pid")
        assertEquals(0, r.exitCode)
        val pid = File(dir, "work/loop.pid").readText().trim()
        assertTrue(File("/proc/$pid").exists())
        BoxRunner.alive(api, id, limitSecs = 2)
        // Still running while the app keeps coming back.
        Thread.sleep(1_000)
        BoxRunner.alive(api, id, limitSecs = 2)
        assertTrue(File("/proc/$pid").exists())
        // Then nothing for longer than the limit.
        var gone = false
        for (i in 0 until 40) {
            Thread.sleep(250)
            if (!File("/proc/$pid").exists()) {
                gone = true
                break
            }
        }
        assertTrue("the loop outlived the watchdog", gone)
        // The watchdog left too; the next visit starts a new one.
        assertTrue(!File(dir, "state/watchdog").exists())
    }

    @Test
    fun quotesArguments() {
        val r = BoxRunner.run(api, id, BoxRunner.join("printf", listOf("%s|", "a b", "it's")), dir.path, 10)
        assertEquals("a b|it's|", r.stdout)
    }
}
