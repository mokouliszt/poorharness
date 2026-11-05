package dev.mokouliszt.poordsh.modal

import dev.mokouliszt.poordsh.runtime.LogBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** One Modal sandbox per chat against [FakeModal]: snapshots between uses, GPUs, the month's budget. */
class ModalSandboxesTest {
    private val dir: File = Files.createTempDirectory("modal").toFile()
    private val modal = FakeModal(File(dir, "io"))
    private var budget = 30.0
    private val gpuChats = mutableSetOf<String>()

    private fun sandboxes(idleMs: Long = 60_000, heartbeatMs: Long = 60_000, gpuHoldIdleMs: Long = 600_000) = ModalSandboxes(
        modal, File(dir, "state.json"), LogBuffer("test"),
        budgetUsd = { budget }, gpuType = { "T4" }, gpuFor = { it in gpuChats },
        idleMs = idleMs, heartbeatMs = heartbeatMs, gpuHoldIdleMs = gpuHoldIdleMs,
    )

    private suspend fun until(what: String, check: () -> Boolean) {
        repeat(200) {
            if (check()) return
            delay(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun firstUseStartsATaggedCpuSandboxWithLimits() = runBlocking {
        val s = sandboxes()
        val ready = s.ensure("c_1")
        assertTrue(ready.fresh)
        val b = modal.byName("poordsh-c-1")!!
        assertEquals(b.id, ready.sandbox.id)
        assertEquals("1", b.tags.getString("poordsh"))
        assertEquals("c_1", b.tags.getString("chat"))
        assertEquals("", b.tags.getString("gpu"))
        assertFalse(b.args.has("gpu"))
        // Billed at max(request, use): a small request, a ceiling, and Modal's own stops.
        assertEquals(0.125, b.args.getJSONArray("cpu").getDouble(0), 0.0)
        assertEquals(2.0, b.args.getJSONArray("cpu").getDouble(1), 0.0)
        assertEquals(4096, b.args.getJSONArray("memory").getInt(1))
        assertEquals(ModalSandboxes.CPU.idleSecs, b.args.getInt("idleTimeout"))
        assertEquals(ModalSandboxes.CPU.lifetimeSecs, b.args.getInt("timeout"))
        // A second use keeps it.
        assertFalse(s.ensure("c_1").fresh)
        assertEquals(1, modal.sandboxes.size)
    }

    @Test
    fun aReleasedSandboxIsSavedThenStoppedAndComesBackFromItsSnapshot() = runBlocking {
        val s = sandboxes(idleMs = 100)
        s.acquire("c_1", "t")
        s.ensure("c_1")
        s.release("c_1", "t")
        until("the stop") { modal.byName("poordsh-c-1") == null }
        assertEquals(1, modal.ops("snapshot").size)
        val image = modal.images.single()
        // Restored with its files: not fresh, from the snapshot.
        val again = s.ensure("c_1")
        assertFalse(again.fresh)
        assertEquals(image, modal.ops("start").last().getString("image"))
    }

    @Test
    fun aNewSnapshotReplacesTheOldOne() = runBlocking {
        val s = sandboxes(idleMs = 50)
        repeat(2) {
            s.acquire("c_1", "t")
            s.ensure("c_1")
            s.release("c_1", "t")
            until("the stop") { modal.byName("poordsh-c-1") == null }
        }
        until("the old snapshot to go") { modal.deletedImages.size == 1 }
        assertEquals(1, modal.images.size)
    }

    @Test
    fun aGoneSnapshotStartsEmpty() = runBlocking {
        val s = sandboxes(idleMs = 50)
        s.acquire("c_1", "t")
        s.ensure("c_1")
        s.release("c_1", "t")
        until("the stop") { modal.byName("poordsh-c-1") == null }
        modal.images.clear()
        assertTrue(s.ensure("c_1").fresh)
        assertFalse(modal.ops("start").last().has("image"))
    }

    @Test
    fun turningTheGpuOnRestartsTheSandboxWithItsFiles() = runBlocking {
        val s = sandboxes()
        val cpu = s.ensure("c_1").sandbox.id
        s.usage()
        gpuChats += "c_1"
        val ready = s.ensure("c_1")
        assertFalse(ready.fresh)
        assertTrue(cpu != ready.sandbox.id)
        val start = modal.ops("start").last()
        assertEquals("T4", start.getString("gpu"))
        assertEquals("T4", start.getJSONObject("tags").getString("gpu"))
        assertEquals(modal.images.single(), start.getString("image"))
        assertEquals(ModalSandboxes.GPU.idleSecs, start.getInt("idleTimeout"))
        // And off again.
        gpuChats -= "c_1"
        s.ensure("c_1")
        assertFalse(modal.ops("start").last().has("gpu"))
    }

    @Test
    fun aGpuNeedsHalfAnHourOfItLeftInTheBudget() = runBlocking {
        val s = sandboxes()
        budget = 10.0
        modal.meteredUsd = 9.0 // 0.5 left under 95%; half an hour of a T4 is 0.295
        s.usage()
        gpuChats += "c_1"
        s.ensure("c_1")
        modal.meteredUsd = 9.3
        s.usage(refresh = true)
        gpuChats += "c_2"
        val e = assertThrows(ModalSandboxes.GpuBudget::class.java) { runBlocking { s.ensure("c_2") } }
        assertEquals("modal_gpu_budget", e.message)
    }

    @Test
    fun newChatsStopAt80PercentAndChatsLeaveAt95() = runBlocking {
        val s = sandboxes()
        // Unknown: yes, and nobody leaves.
        assertTrue(s.allowsNewChats())
        assertFalse(s.shouldLeave())
        modal.meteredUsd = 23.9
        s.usage()
        assertTrue(s.allowsNewChats())
        modal.meteredUsd = 24.0
        s.usage(refresh = true)
        assertFalse(s.allowsNewChats())
        assertFalse(s.shouldLeave())
        modal.meteredUsd = 28.5
        s.usage(refresh = true)
        assertTrue(s.shouldLeave())
        // The budget is read live: raising it lets chats in again.
        budget = 100.0
        assertTrue(s.allowsNewChats())
        assertFalse(s.shouldLeave())
    }

    @Test
    fun aUsageRefusalCountsForTheMonthUntilTheBudgetChanges() = runBlocking {
        val s = sandboxes()
        modal.refuse["start"] = "usage"
        assertThrows(ModalAgent.ModalException::class.java) { runBlocking { s.ensure("c_1") } }
        assertFalse(s.allowsNewChats())
        assertTrue(s.shouldLeave())
        // Kept across restarts of the app.
        val after = sandboxes()
        assertFalse(after.allowsNewChats())
        after.budgetChanged()
        assertTrue(after.allowsNewChats())
    }

    @Test
    fun gpusComeFromModalsRates() = runBlocking {
        val s = sandboxes()
        val u = s.usage()
        assertEquals(listOf("T4" to 0.59, "A100-80GB" to 2.5, "RTX-PRO-6000" to 3.03), u.gpus.map { it.id to it.usdPerHour })
        assertEquals("H100", ModalSandboxes.gpuId("h100"))
        assertEquals("A100-40GB", ModalSandboxes.gpuId("a100_40gb"))
    }

    @Test
    fun aHeldSandboxIsKeptUpAndAnIdleHeldGpuIsSavedAndStopped() = runBlocking {
        val s = sandboxes(heartbeatMs = 40, gpuHoldIdleMs = 300)
        s.acquire("c_1", "t")
        s.ensure("c_1")
        until("a heartbeat") { modal.ops("touch").isNotEmpty() }
        assertNotNull(modal.byName("poordsh-c-1"))
        s.usage()
        gpuChats += "c_2"
        s.acquire("c_2", "t")
        s.ensure("c_2")
        until("the idle GPU to stop") { modal.byName("poordsh-c-2") == null }
        // The CPU one is still up; the GPU one comes back from its snapshot on the next tool call.
        assertNotNull(modal.byName("poordsh-c-1"))
        val back = s.sandboxFor("c_2")
        assertEquals(modal.byName("poordsh-c-2")!!.id, back.id)
        assertTrue(modal.ops("start").last().has("image"))
        s.release("c_1", "t")
        s.release("c_2", "t")
    }

    @Test
    fun strayRecoveryLeavesOtherPhonesSandboxesAlone() = runBlocking {
        modal.call("start", org.json.JSONObject().put("name", "poordsh-c-mine").put("tags", org.json.JSONObject().put("poordsh", "1").put("chat", "c_mine").put("gpu", "")), 0)
        modal.call("start", org.json.JSONObject().put("name", "poordsh-c-other").put("tags", org.json.JSONObject().put("poordsh", "1").put("chat", "c_other").put("gpu", "")), 0)
        modal.calls.clear()
        val s = sandboxes()
        s.recoverStrays { it == "c_mine" }
        until("the stray to stop") { modal.byName("poordsh-c-mine") == null }
        assertNotNull(modal.byName("poordsh-c-other"))
        assertEquals(1, modal.ops("snapshot").size)
        // Its snapshot is the chat's now.
        assertFalse(s.ensure("c_mine").fresh)
    }

    @Test
    fun deletingAChatStopsItsSandboxAndDeletesItsSnapshot() = runBlocking {
        val s = sandboxes(idleMs = 50)
        s.acquire("c_1", "t")
        s.ensure("c_1")
        s.release("c_1", "t")
        until("the stop") { modal.byName("poordsh-c-1") == null }
        s.ensure("c_1")
        val image = modal.images.single()
        s.delete("c_1")
        assertNull(modal.byName("poordsh-c-1"))
        assertTrue(image in modal.deletedImages)
        assertFalse(s.exists("c_1"))
    }

    @Test
    fun theAppCountsWhatItRanWhenModalsSummaryLags() = runBlocking {
        modal.meteredUsd = 0.0
        val s = sandboxes()
        s.usage()
        gpuChats += "c_1"
        s.ensure("c_1")
        Thread.sleep(50)
        // Some cost by the app's count while the summary still says 0.
        val u = s.usage(refresh = true)
        assertTrue(u.spentUsd > 0.0)
    }
}
