package dev.mokouliszt.poordsh.backend.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only Infron's free, function-calling models reach opencode (a trimmed copy of its 2026-10 list). */
class InfronTest {
    private val catalog = javaClass.getResource("/infron/models.json")!!.readText()

    @Test
    fun keepsFreeModelsThatCallFunctions() {
        val models = Infron.parse(catalog)
        // Left out: the paid base model, a paid model listed at $0 without ":free", and a video model.
        assertEquals(
            listOf(
                "apodex/apodex-1.1-mini:free",
                "deepseek/deepseek-v4.1-flash:free",
                "inclusionai/ling-3.1-flash:free",
                "internlm/atria-dawn-preview:free",
                "qwen/qwen3.8-27b-abliterated-cyber:free",
                "qwen/qwen3.8-27b:free",
            ),
            models.map { it.id },
        )
        val ds = models.first { it.id == "deepseek/deepseek-v4.1-flash:free" }
        assertEquals("Deepseek V4.1 Flash", ds.name)
        assertEquals(1_000_000L, ds.context)
        assertEquals(384_000L, ds.output)
        assertEquals(listOf("text", "image"), ds.inputs)
        // The list does not say whether a model reasons; opencode decides.
        assertEquals(null, ds.reasoning)
        // No more context than the provider behind it serves.
        assertEquals(204_000L, models.first { it.id == "qwen/qwen3.8-27b:free" }.context)
    }

    @Test
    fun configTellsOpencodeHowToReachIt() {
        val block = Infron.providerConfig(Infron.parse(catalog))
        assertEquals("@ai-sdk/openai-compatible", block.getString("npm"))
        assertEquals(Infron.BASE_URL, block.getJSONObject("options").getString("baseURL"))
        assertEquals(6, block.getJSONArray("whitelist").length())
        val ds = block.getJSONObject("models").getJSONObject("deepseek/deepseek-v4.1-flash:free")
        assertTrue(ds.getBoolean("attachment"))
        assertFalse(ds.has("reasoning"))
        assertEquals(0, ds.getJSONObject("cost").getInt("output"))
        // Reasoning levels go out as Infron's own `reasoning.effort`.
        val variants = ds.getJSONObject("variants")
        // (As a set: the JVM's org.json does not keep key order; Android's, which writes the config, does.)
        assertEquals(Infron.EFFORTS.toSet(), variants.keys().asSequence().toSet())
        assertEquals("high", variants.getJSONObject("high").getJSONObject("reasoning").getString("effort"))
        // Kept for checking against a real opencode (docs/maintenance.md).
        java.io.File("build/infron-provider.json").apply { parentFile?.mkdirs(); writeText(block.toString(2)) }
    }

    @Test
    fun aKnownGatewayKeepsOpencodesSettings() {
        // OrcaRouter is in models.dev, so its block carries only models, and opencode
        // derives their reasoning levels.
        val block = OrcaRouter.providerConfig(OrcaRouter.parse(javaClass.getResource("/orcarouter/pricing.json")!!.readText()))
        assertFalse(block.has("npm"))
        assertFalse(block.has("options"))
        assertFalse(block.getJSONObject("models").getJSONObject("tencent/hy3-free").has("variants"))
    }
}
