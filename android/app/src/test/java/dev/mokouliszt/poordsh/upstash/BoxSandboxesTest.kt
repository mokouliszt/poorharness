package dev.mokouliszt.poordsh.upstash

import dev.mokouliszt.poordsh.runtime.LogBuffer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** One box per chat against [FakeBox]: lifetime, the ten-box limit, the month's CPU, moving files out. */
class BoxSandboxesTest {
    private val box = FakeBox()
    private val dir: File = Files.createTempDirectory("boxes").toFile()
    private val log = LogBuffer("test")

    private fun boxes(idleMs: Long = 60_000) = BoxSandboxes(box.api(), File(dir, "state.json"), log, idleMs)

    @After
    fun tearDown() {
        box.server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun firstUseMakesALabelledBoxAndLaterUsesKeepIt() = runBlocking {
        val b = boxes()
        val first = b.ensure("c_1")
        assertTrue(first.fresh)
        val made = box.byName("poordsh-c-1")!!
        assertEquals(listOf(BoxSandboxes.LABEL), made.labels)
        assertEquals(made.id, first.sandbox.id)
        // The check makes the workspace and starts the box's watchdog.
        assertEquals(listOf("alive", "600", "/tmp/workspace"), box.execs.last().drop(4))
        assertFalse(b.ensure("c_1").fresh)
        assertEquals(1, box.boxes.size)
    }

    @Test
    fun toolCallsUseTheCheckedBoxWithoutAskingAgain() = runBlocking {
        val b = boxes()
        val id = b.ensure("c_1").sandbox.id
        val before = box.server.requestCount
        assertEquals(id, b.sandboxFor("c_1").id)
        assertEquals(before, box.server.requestCount)
        // Once forgotten (Box refused a call), the next use checks the box again.
        b.forget("c_1")
        assertEquals(id, b.sandboxFor("c_1").id)
        assertTrue(box.server.requestCount > before)
    }

    @Test
    fun boxesLeftAwakeByAKilledAppArePausedAtStart() = runBlocking {
        val old = box.api().create("poordsh-c-old", listOf(BoxSandboxes.LABEL))
        val recent = box.api().create("poordsh-c-new", listOf(BoxSandboxes.LABEL))
        val mine = box.api().create("my-own-box", listOf("work"))
        box.boxes.values.forEach { it.status = "idle" }
        box.boxes.getValue(old.id).lastActivity = System.currentTimeMillis() / 1000 - 3600
        box.boxes.getValue(mine.id).lastActivity = System.currentTimeMillis() / 1000 - 3600
        boxes().pauseStrays()
        Thread.sleep(500)
        assertEquals("paused", box.boxes.getValue(old.id).status)
        // Used a moment ago (maybe from another phone), or not this app's: left alone.
        assertEquals("idle", box.boxes.getValue(recent.id).status)
        assertEquals("idle", box.boxes.getValue(mine.id).status)
    }

    @Test
    fun aHeldBoxHearsFromTheApp() = runBlocking {
        val b = BoxSandboxes(box.api(), File(dir, "state.json"), log, heartbeatMs = 100)
        b.acquire("c_1", "turn")
        b.ensure("c_1")
        val before = box.execs.size
        Thread.sleep(450)
        assertTrue(box.execs.size - before >= 2)
        assertTrue(box.execs.drop(before).all { it[4] == "alive" })
        b.release("c_1", "turn")
        Thread.sleep(300)
        val after = box.execs.size
        Thread.sleep(300)
        assertEquals(after, box.execs.size)
    }

    @Test
    fun anIdleBoxIsPaused() = runBlocking {
        val b = boxes(idleMs = 150)
        b.ensure("c_1")
        b.acquire("c_1", "t")
        b.release("c_1", "t")
        Thread.sleep(600)
        assertEquals("paused", box.byName("poordsh-c-1")!!.status)
    }

    @Test
    fun aFullAccountArchivesTheLeastRecentlyUsedIdleBox() = runBlocking {
        box.limit = 2
        val b = boxes()
        val one = b.ensure("c_1").sandbox
        one.write("/tmp/workspace/keep.txt", "kept".toByteArray())
        Thread.sleep(20)
        b.ensure("c_2")
        Thread.sleep(20)
        // c_1 was used longest ago: it goes to a snapshot so c_3 can have a box.
        assertTrue(b.ensure("c_3").fresh)
        assertNull(box.byName("poordsh-c-1"))
        assertEquals(1, box.snapshots.size)
        assertTrue(b.exists("c_1"))

        // c_1 comes back from its snapshot, with its files, in place of c_2 (now the oldest).
        val back = b.ensure("c_1")
        assertFalse(back.fresh)
        assertEquals("kept", back.sandbox.read("/tmp/workspace/keep.txt")!!.use { String(it.readBytes()) })
        assertNull(box.byName("poordsh-c-2"))
        assertNotNull(box.byName("poordsh-c-3"))
        // c_1's own snapshot is gone; c_2's is kept.
        assertEquals(1, box.snapshots.size)
        assertNotNull(box.byName("poordsh-c-1"))
        assertEquals(2, box.boxes.size)
    }

    @Test
    fun aBusyBoxIsNeverArchived() = runBlocking {
        box.limit = 1
        val b = boxes()
        b.acquire("c_1", "turn")
        b.ensure("c_1")
        val e = runCatching { b.ensure("c_2") }.exceptionOrNull()
        assertTrue("expected Full, got $e", e is BoxSandboxes.Full)
        assertNotNull(box.byName("poordsh-c-1"))
    }

    @Test
    fun boxesThisAppDidNotMakeAreLeftAlone() = runBlocking {
        box.limit = 1
        box.api().create("my-own-box", listOf("work"))
        val e = runCatching { boxes().ensure("c_1") }.exceptionOrNull()
        assertTrue("expected Full, got $e", e is BoxSandboxes.Full)
        assertNotNull(box.byName("my-own-box"))
        assertTrue(box.snapshots.isEmpty())
    }

    @Test
    fun newChatsStopShortOfTheFreeAllowance() = runBlocking {
        val b = boxes()
        // Nothing read yet: Box counts as available.
        assertTrue(b.allowsNewChats())
        box.cpuNs = (1.0 * 3.6e12).toLong()
        assertEquals(1.0, b.usage(refresh = true).usedHours, 1e-9)
        assertTrue(b.allowsNewChats())
        box.cpuNs = (4.8 * 3.6e12).toLong()
        b.usage(refresh = true)
        assertFalse(b.allowsNewChats())
    }

    @Test
    fun aRefusalForUsageSendsNewChatsElsewhereForTheMonth() = runBlocking {
        val b = boxes()
        b.ensure("c_1")
        box.refuseUsage = true
        val e = runCatching { b.ensure("c_1") }.exceptionOrNull()
        assertTrue("expected UsageLimit, got $e", e is BoxApi.UsageLimit)
        assertFalse(b.allowsNewChats())
        assertTrue(b.usage(refresh = true).spent)
        // Remembered across restarts.
        assertFalse(boxes().allowsNewChats())
    }

    @Test
    fun theWorkspaceComesOutWithoutCommands() = runBlocking {
        val b = boxes()
        val sb = b.ensure("c_1").sandbox
        sb.write("/tmp/workspace/a.txt", "A".toByteArray())
        sb.write("/tmp/workspace/src/b.txt", "B".toByteArray())
        sb.write("/tmp/workspace/node_modules/x/index.js", "x".toByteArray())
        box.refuseUsage = true
        val into = File(dir, "out")
        assertTrue(b.downloadWorkspace("c_1", into))
        assertEquals("A", File(into, "a.txt").readText())
        assertEquals("B", File(into, "src/b.txt").readText())
        assertFalse(File(into, "node_modules").exists())
        assertFalse(b.downloadWorkspace("c_1", File(dir, "small"), maxFiles = 1))
    }

    @Test
    fun deletingAChatRemovesItsBoxAndSnapshot() = runBlocking {
        box.limit = 1
        val b = boxes()
        b.ensure("c_1")
        b.ensure("c_2") // archives c_1
        assertEquals(1, box.snapshots.size)
        b.delete("c_1")
        b.delete("c_2")
        assertTrue(box.snapshots.isEmpty())
        assertTrue(box.boxes.isEmpty())
        assertFalse(b.exists("c_1"))
    }
}
