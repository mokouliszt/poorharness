package dev.mokouliszt.poordsh.backend.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Only OrcaRouter's free, tool-capable models reach opencode (a trimmed copy of the 2026-10 catalog). */
class OrcaRouterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val catalog = javaClass.getResource("/orcarouter/pricing.json")!!.readText()

    @Test
    fun keepsFreeModelsThatTakeTools() {
        val models = OrcaRouter.parse(catalog)
        // The paid base model and the text detector (no tools) are left out.
        assertEquals(listOf("deepseek/deepseek-v4.1-flash-free", "tencent/hy3-free", "z-ai/glm-5.3-flash-free"), models.map { it.id })
        val ds = models.first()
        assertEquals("DeepSeek V4.1 Flash", ds.name)
        assertEquals(1_048_576L, ds.context)
        assertEquals(384_000L, ds.output)
        assertEquals(listOf("text", "image"), ds.inputs)
        assertEquals(true, ds.reasoning)
        assertNull(models.first { it.id == "tencent/hy3-free" }.output)
    }

    @Test
    fun configDefinesAndWhitelistsThem() {
        val block = OrcaRouter.providerConfig(OrcaRouter.parse(catalog))
        val ids = block.getJSONArray("whitelist").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(3, ids.size)
        val defs = block.getJSONObject("models")
        val ds = defs.getJSONObject("deepseek/deepseek-v4.1-flash-free")
        assertTrue(ds.getBoolean("tool_call"))
        assertTrue(ds.getBoolean("attachment"))
        assertEquals(0, ds.getJSONObject("cost").getInt("input"))
        // opencode rejects a limit without both numbers; an unknown output becomes the context size.
        val hy3 = defs.getJSONObject("tencent/hy3-free").getJSONObject("limit")
        assertEquals(262_144L, hy3.getLong("context"))
        assertEquals(262_144L, hy3.getLong("output"))
        assertFalse(defs.getJSONObject("tencent/hy3-free").getBoolean("attachment"))
        // Kept for checking against a real opencode (docs/maintenance.md).
        File("build/orcarouter-provider.json").apply { parentFile?.mkdirs(); writeText(block.toString(2)) }
    }

    @Test
    fun anEmptyListAllowsNothing() {
        val block = OrcaRouter.providerConfig(emptyList())
        assertEquals(0, block.getJSONArray("whitelist").length())
    }

    @Test
    fun fallsBackToTheLastListWhenOffline() {
        val cache = File(tmp.root, "free.json")
        var online = true
        val free = FreeGateway.FreeModels(OrcaRouter, cache, fetch = { if (online) catalog else throw java.io.IOException("offline") })
        assertEquals(3, free.load()?.size)
        assertTrue(cache.isFile)
        online = false
        assertEquals(3, free.load()?.size) // from the cache
        assertNull(FreeGateway.FreeModels(OrcaRouter, File(tmp.root, "none.json"), fetch = { throw java.io.IOException("offline") }).load())
        // A page that is not the catalog counts as unavailable, not as "no free models".
        assertEquals(3, FreeGateway.FreeModels(OrcaRouter, cache, fetch = { "<html>" }).load()?.size)
    }
}
