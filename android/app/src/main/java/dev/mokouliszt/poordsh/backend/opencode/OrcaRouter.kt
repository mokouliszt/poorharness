package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.backend.opencode.FreeGateway.Companion.MODALITIES
import dev.mokouliszt.poordsh.backend.opencode.FreeGateway.Companion.cleanName
import dev.mokouliszt.poordsh.backend.opencode.FreeGateway.Companion.strings
import org.json.JSONObject

/**
 * OrcaRouter, which opencode knows from models.dev as "orcarouter" (verified 2026-10).
 *
 * models.dev's copy lists OrcaRouter's paid models too, and a $0 router that is
 * not free, and lags OrcaRouter's own catalog. That catalog (`/api/pricing`) marks
 * each free model `is_free_tier` and gives its limits and parameters. A request
 * carrying a bad key gets 401 instead, so it is read without one.
 */
object OrcaRouter : FreeGateway("orcarouter", "https://api.orcarouter.ai/api/pricing") {
    override fun parse(body: String): List<Model> {
        val data = JSONObject(body).getJSONArray("data")
        val out = mutableListOf<Model>()
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            if (!m.optBoolean("is_free_tier")) continue
            val id = m.optString("model_name").takeIf { it.isNotBlank() } ?: continue
            val params = m.optJSONArray("supported_parameters").strings()
            if ("tools" !in params) continue
            val outputs = m.optJSONArray("output_modalities").strings()
            if (outputs.isNotEmpty() && "text" !in outputs) continue
            out += Model(
                id = id,
                name = cleanName(m.optString("display_name"), id),
                context = m.optLong("context_length").takeIf { it > 0 },
                output = m.optLong("max_completion_tokens").takeIf { it > 0 },
                inputs = m.optJSONArray("input_modalities").strings().filter { it in MODALITIES },
                reasoning = params.any { it == "reasoning" || it == "reasoning_effort" || it == "thinking" },
            )
        }
        return out.sortedBy { it.id }
    }
}
