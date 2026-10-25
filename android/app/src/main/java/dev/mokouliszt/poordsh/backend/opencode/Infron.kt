package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.backend.opencode.FreeGateway.Companion.MODALITIES
import dev.mokouliszt.poordsh.backend.opencode.FreeGateway.Companion.cleanName
import dev.mokouliszt.poordsh.backend.opencode.FreeGateway.Companion.strings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Infron, which models.dev does not list (verified 2026-10), so the app also tells
 * opencode how to reach it: an OpenAI-compatible provider at [BASE_URL], with the
 * key from opencode's auth store.
 *
 * Its model list (`/v1/models`, no key needed) gives every model's lowest prices,
 * limits, modalities and whether it calls functions. Free variants end in `:free`
 * and cost $0; both are required, since a few paid models also show $0 prices.
 */
object Infron : FreeGateway("infron", "https://llm.onerouter.pro/v1/models") {
    const val BASE_URL = "https://llm.onerouter.pro/v1"

    /**
     * The levels of Infron's own `reasoning.effort`, which it converts for whichever
     * provider serves the model and which a model without them ignores. Fixed by
     * Infron's API, not by any model. Measured 2026-10: DeepSeek V4.1 Flash thinks
     * only when asked (any level but none), Ling and Apodex at every level, and
     * the Qwen3.8 27B pair at minimal to medium but not at high or xhigh.
     */
    val EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh")

    /** Every free model gets the levels: the list does not say which models reason. */
    override fun variants(m: Model): JSONObject = JSONObject().apply {
        EFFORTS.forEach { put(it, JSONObject().put("reasoning", JSONObject().put("effort", it))) }
    }

    override fun connection(): JSONObject = JSONObject()
        .put("npm", "@ai-sdk/openai-compatible")
        .put("name", "Infron")
        .put("options", JSONObject().put("baseURL", BASE_URL))

    override fun parse(body: String): List<Model> {
        val data = JSONObject(body).getJSONArray("data")
        val out = mutableListOf<Model>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id").takeIf { it.endsWith(":free") } ?: continue
            if (!zero(m, "min_prompt_price") || !zero(m, "min_completion_price")) continue
            if (m.has("min_request_price") && !m.isNull("min_request_price") && !zero(m, "min_request_price")) continue
            if (!m.optBoolean("supports_function_calling")) continue
            if (m.optBoolean("deprecated") || m.optBoolean("is_display_only")) continue
            if (m.optString("category_type", "LLM") != "LLM") continue
            val outputs = m.optJSONArray("output_modalities").strings()
            if (outputs.isNotEmpty() && "text" !in outputs) continue
            out += Model(
                id = id,
                name = cleanName(m.optString("display_name"), id),
                context = context(m),
                output = m.optLong("max_output_tokens").takeIf { it > 0 },
                inputs = m.optJSONArray("input_modalities").strings().filter { it in MODALITIES },
                // The list does not say; left to opencode, which still passes reasoning
                // back for the families it knows (verified with DeepSeek).
                reasoning = null,
            )
        }
        return out.sortedBy { it.id }
    }

    private fun zero(m: JSONObject, key: String): Boolean =
        m.has(key) && !m.isNull(key) && m.optDouble(key, -1.0) == 0.0

    /** The model's context, but no more than the largest any provider behind it serves. */
    private fun context(m: JSONObject): Long? {
        val model = m.optLong("context_length").takeIf { it > 0 }
        val served = (m.optJSONArray("providers") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optLong("context_length")?.takeIf { c -> c > 0 } }.maxOrNull()
        }
        return listOfNotNull(model, served).minOrNull()
    }
}
