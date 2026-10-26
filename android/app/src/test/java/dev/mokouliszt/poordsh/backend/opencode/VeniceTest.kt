package dev.mokouliszt.poordsh.backend.opencode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Only uncensored, private, cheap models reach opencode, as Venice describes them (a trimmed copy of its 2026-10 list). */
class VeniceTest {
    private val catalog = javaClass.getResource("/venice/models.json")!!.readText()

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun offersWhatMeetsTheRuleCheapestFirst() {
        val models = Venice.parse(catalog)
        // Left out: venice-uncensored-role-play (over the price cap), qwen-3-6-plus (uncensored but
        // anonymized, and dear), e2ee-glm-5-3-p and openai-gpt-oss-120b (not uncensored).
        assertEquals(
            listOf(
                "olafangensan-glm-4.7-flash-heretic",
                "gemma-4-uncensored",
                "e2ee-gemma-4-26b-a4b-uncensored-p",
                "venice-uncensored-1-2",
                "deepseek-v4-1-flash-uncensored",
            ),
            models.map { it.id },
        )
        val glm = models.first { it.id == "olafangensan-glm-4.7-flash-heretic" }
        assertEquals("GLM 4.7 Flash Heretic", glm.name)
        assertEquals(200_000L, glm.context)
        assertEquals(24_000L, glm.output)
        assertTrue(glm.toolCall && glm.reasoning)
        assertEquals(listOf("none", "low", "medium", "high"), glm.efforts)
        assertEquals(0.07, glm.inputPrice!!, 1e-9)
        assertEquals(0.4, glm.outputPrice!!, 1e-9)
        assertEquals(0.035, glm.cachePrice!!, 1e-9)
        // Venice's own words for the levels, not opencode's guess.
        assertEquals(listOf("none", "low", "high", "max"), models.first { it.id == "deepseek-v4-1-flash-uncensored" }.efforts)
        assertTrue(models.first { it.id == "venice-uncensored-1-2" }.vision)
    }

    @Test
    fun theE2eeGemmaIsChatOnly() {
        val models = Venice.parse(catalog)
        assertEquals(setOf("venice/e2ee-gemma-4-26b-a4b-uncensored-p"), Venice.chatOnly(models))
        val def = Venice.providerConfig(models).getJSONObject("models").getJSONObject("e2ee-gemma-4-26b-a4b-uncensored-p")
        assertFalse(def.getBoolean("tool_call"))
    }

    @Test
    fun configTellsOpencodeHowToReachIt() {
        val block = Venice.providerConfig(Venice.parse(catalog))
        assertEquals("@ai-sdk/openai-compatible", block.getString("npm"))
        assertEquals(Venice.BASE_URL, block.getJSONObject("options").getString("baseURL"))
        assertEquals(Venice.parse(catalog).map { it.id }, (0 until 5).map { block.getJSONArray("whitelist").getString(it) })
        val ds = block.getJSONObject("models").getJSONObject("deepseek-v4-1-flash-uncensored")
        assertTrue(ds.getBoolean("tool_call") && ds.getBoolean("reasoning") && ds.getBoolean("attachment"))
        assertEquals(1_000_000L, ds.getJSONObject("limit").getLong("context"))
        assertEquals(0.33, ds.getJSONObject("cost").getDouble("input"), 1e-9)
        val variants = ds.getJSONObject("variants")
        // Offered levels go out as reasoning_effort; the ones opencode would add that Venice rejects are off.
        for (level in listOf("none", "low", "high", "max")) {
            assertEquals(level, variants.getJSONObject(level).getString("reasoningEffort"))
        }
        for (level in listOf("minimal", "medium", "xhigh")) {
            assertTrue(variants.getJSONObject(level).getBoolean("disabled"))
        }
        // A model that does not reason gets no levels.
        assertFalse(block.getJSONObject("models").getJSONObject("gemma-4-uncensored").has("variants"))
    }

    @Test
    fun anOfflineModelIsNotOffered() {
        val body = JSONObject(catalog)
        val data = body.getJSONArray("data")
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            if (m.getString("id") == "gemma-4-uncensored") m.getJSONObject("model_spec").put("offline", true)
        }
        assertFalse(Venice.parse(body.toString()).any { it.id == "gemma-4-uncensored" })
    }

    @Test
    fun eachPartOfTheRuleKeepsAModelOut() {
        fun without(change: (JSONObject) -> Unit): Boolean {
            val body = JSONObject(catalog)
            val data = body.getJSONArray("data")
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                if (m.getString("id") == "gemma-4-uncensored") change(m.getJSONObject("model_spec"))
            }
            return Venice.parse(body.toString()).none { it.id == "gemma-4-uncensored" }
        }
        assertFalse(without { })
        assertTrue(without { it.put("uncensored", false) })
        assertTrue(without { it.put("privacy", "anonymized") })
        assertTrue(without { it.getJSONObject("pricing").getJSONObject("input").put("usd", Venice.MAX_INPUT + 0.01) })
        assertTrue(without { it.getJSONObject("pricing").getJSONObject("output").put("usd", Venice.MAX_OUTPUT + 0.01) })
        // The cap itself is allowed; an unknown price is not.
        assertFalse(without { it.getJSONObject("pricing").getJSONObject("input").put("usd", Venice.MAX_INPUT) })
        assertTrue(without { it.getJSONObject("pricing").remove("output") })
    }

    @Test
    fun theModelPickerShowsThePrice() {
        assertEquals("\$0.07 in · \$0.4 out / 1M", Venice.priceLabel(0.07, 0.4))
        assertEquals("\$0.33 in · \$1.32 out / 1M", Venice.priceLabel(0.33, 1.32))
        assertEquals(null, Venice.priceLabel(null, 1.0))
    }

    @Test
    fun aListWithoutTheModelsIsNotAnAnswer() {
        try {
            Venice.parse("""{"data":[{"id":"other","type":"text","model_spec":{}}]}""")
            fail()
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun theLastListIsKeptForTheNextStart() {
        val cache = tmp.newFile("venice.json")
        assertEquals(5, Venice.catalog(cache, fetch = { catalog }).load()!!.size)
        // Offline at the next start: the cached list is used, with everything it held.
        val again = Venice.catalog(cache, fetch = { error("offline") }).load()!!
        assertEquals(Venice.parse(catalog).map { it.id }, again.map { it.id })
        assertEquals(listOf("none", "low", "medium", "high"), again.first { it.id == "olafangensan-glm-4.7-flash-heretic" }.efforts)
        assertEquals(0.035, again.first { it.id == "olafangensan-glm-4.7-flash-heretic" }.cachePrice!!, 1e-9)
    }
}
