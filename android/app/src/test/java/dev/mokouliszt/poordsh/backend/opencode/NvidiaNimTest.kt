package dev.mokouliszt.poordsh.backend.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Only models NIM still serves are allowed (a trimmed copy of its 2026-10 list). */
class NvidiaNimTest {
    @get:Rule val tmp = TemporaryFolder()
    private val list = javaClass.getResource("/nvidia/models.json")!!.readText()

    @Test
    fun whitelistsWhatNimServes() {
        val ids = NvidiaNim.parse(list)
        assertEquals(listOf("deepseek-ai/deepseek-v4.1-flash", "meta/llama2-70b", "nvidia/nemotron-3.5-lightning-30b-a3b", "z-ai/glm-5.3-flash"), ids)
        val block = NvidiaNim.providerConfig(ids)
        // Only a whitelist: models.dev's definitions (tool calling, limits) stay opencode's.
        assertEquals(setOf("whitelist"), block.keys().asSequence().toSet())
        assertEquals(4, block.getJSONArray("whitelist").length())
    }

    @Test
    fun noListKeepsModelsDevs() {
        val cache = File(tmp.root, "served.json")
        var online = true
        val catalog = NvidiaNim.catalog(cache, fetch = { if (online) list else throw IOException("offline") })
        assertEquals(4, catalog.load()?.size)
        online = false
        assertEquals(4, catalog.load()?.size) // from the cache
        // No cache and no answer, or an empty answer: no whitelist at all (null), not an empty one.
        assertNull(NvidiaNim.catalog(File(tmp.root, "none.json"), fetch = { throw IOException("offline") }).load())
        assertNull(NvidiaNim.catalog(File(tmp.root, "empty.json"), fetch = { """{"object":"list","data":[]}""" }).load())
    }
}
