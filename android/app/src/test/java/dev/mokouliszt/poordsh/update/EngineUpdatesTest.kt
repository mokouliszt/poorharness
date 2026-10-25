package dev.mokouliszt.poordsh.update

import dev.mokouliszt.poordsh.runtime.Tools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Engine updates: checked at every launch, installed only with auto update on (docs/adr/engine-updates.md). */
class EngineUpdatesTest {
    private class Fake(var opencodeNewer: Boolean) : EngineUpdates.Updater {
        var checks = 0
        val applied = mutableListOf<String>()
        override suspend fun check(): JSONObject {
            checks++
            return JSONObject().put("opencode", JSONObject().put("updateAvailable", opencodeNewer))
        }
        override suspend fun apply(tool: Tools.Tool, onProgress: (Float) -> Unit): String? {
            applied += tool.id; opencodeNewer = false; onProgress(1f); return "1.20.0"
        }
    }

    private var now = 1_000_000L
    private val restarted = mutableListOf<String>()
    private fun updates(fake: Fake, auto: Boolean) = EngineUpdates(
        fake, autoUpdate = { auto }, applyWhenIdle = { restarted += it },
        // Unconfined: the fake never suspends, so each launch finishes before returning.
        scope = CoroutineScope(Dispatchers.Unconfined), clock = { now },
    )

    @Test
    fun withoutAutoUpdateALaunchOnlyChecks() {
        val fake = Fake(opencodeNewer = true)
        val u = updates(fake, auto = false)
        u.onAppOpened()
        assertEquals(1, fake.checks)
        assertEquals(emptyList<String>(), fake.applied)
        assertEquals(true, u.last?.getJSONObject("opencode")?.getBoolean("updateAvailable"))
    }

    @Test
    fun withAutoUpdateALaunchInstallsWhatIsNewer() {
        val fake = Fake(opencodeNewer = true)
        val u = updates(fake, auto = true)
        u.onAppOpened()
        assertEquals(listOf("opencode"), fake.applied)
        // opencode moves to the new build once idle; the result is checked again.
        assertEquals(listOf("opencode"), restarted)
        assertEquals(2, fake.checks)
        assertEquals(false, u.last?.getJSONObject("opencode")?.getBoolean("updateAvailable"))
    }

    @Test
    fun aReturnWithinMinutesDoesNotCheckAgain() {
        val fake = Fake(opencodeNewer = false)
        val u = updates(fake, auto = true)
        u.onAppOpened()
        now += 60_000
        u.onAppOpened()
        assertEquals(1, fake.checks)
        now += EngineUpdates.RECHECK_MS
        u.onAppOpened()
        assertEquals(2, fake.checks)
    }

    @Test
    fun aLaunchCheckThatFailsShowsNoError() {
        val fake = object : EngineUpdates.Updater {
            override suspend fun check() = JSONObject().put("opencode", JSONObject().put("current", "1.19.0").put("error", "offline"))
            override suspend fun apply(tool: Tools.Tool, onProgress: (Float) -> Unit): String? = null
        }
        val u = EngineUpdates(fake, autoUpdate = { true }, applyWhenIdle = {}, scope = CoroutineScope(Dispatchers.Unconfined), clock = { now })
        u.onAppOpened()
        assertEquals(false, u.last!!.getJSONObject("opencode").has("error"))
    }

    @Test
    fun turningAutoUpdateOnInstallsAtOnce() {
        val fake = Fake(opencodeNewer = true)
        val u = updates(fake, auto = true)
        u.onAppOpened() // checked and installed
        fake.opencodeNewer = true // a release lands later
        u.onAutoUpdateEnabled() // not held back by the recent check
        assertEquals(listOf("opencode", "opencode"), fake.applied)
    }
}
