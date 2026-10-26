package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.backend.opencode.FreeGateway.Companion.strings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Venice AI (docs/adr/venice.md): a paid, OpenAI-compatible API. opencode knows it
 * from models.dev as "venice", but through an npm package it would have to download
 * at run time, and without one of the models the app offers; so the app defines the
 * provider itself (`@ai-sdk/openai-compatible` at [BASE_URL]), as it does for Infron.
 *
 * Which models are offered is a rule over Venice's own list (`/models`, no key needed),
 * not a list of ids: Venice marks it `uncensored`, `privacy` is `private` (the provider
 * keeps no prompts and no third party sees them), it is online, and both prices are at
 * or under [MAX_INPUT] / [MAX_OUTPUT]. Names, limits, prices, tool calls, reasoning
 * levels and images in come from the same list. A model that stops meeting the rule, or
 * is no longer listed, disappears at the next opencode start; a new one appears.
 */
object Venice {
    const val PROVIDER = "venice"
    const val BASE_URL = "https://api.venice.ai/api/v1"
    const val MODELS_URL = "$BASE_URL/models"

    /** The only hardcoded part: the most a model may cost, in USD per million tokens (Venice's unit). */
    const val MAX_INPUT = 0.35
    const val MAX_OUTPUT = 1.5

    /** The level names opencode may derive for a reasoning model; those a model lacks are switched off. */
    private val LEVELS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    data class Model(
        val id: String,
        val name: String,
        val context: Long?,
        val output: Long?,
        /** False for Venice's E2EE gemma: it rejects `tools`, so it is chat only (the app's `chat` agent). */
        val toolCall: Boolean,
        val reasoning: Boolean,
        /** The reasoning levels the model accepts, in Venice's words. */
        val efforts: List<String>,
        val vision: Boolean,
        /** USD per million tokens. */
        val inputPrice: Double?,
        val outputPrice: Double?,
        val cachePrice: Double?,
    )

    /** The models that meet the rule, cheapest input first. Throws on a body that is not Venice's list, or that offers nothing. */
    fun parse(body: String): List<Model> {
        val data = JSONObject(body).getJSONArray("data")
        val out = (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").ifBlank { return@mapNotNull null }
            val spec = m.optJSONObject("model_spec") ?: return@mapNotNull null
            if (m.optString("type", "text") != "text" || spec.optBoolean("offline")) return@mapNotNull null
            if (!spec.optBoolean("uncensored") || spec.optString("privacy") != "private") return@mapNotNull null
            val caps = spec.optJSONObject("capabilities") ?: JSONObject()
            val price = spec.optJSONObject("pricing")
            fun usd(key: String): Double? = price?.optJSONObject(key)?.optDouble("usd")?.takeIf { !it.isNaN() }
            // A model whose price is unknown is not offered: the cap is the point.
            val input = usd("input") ?: return@mapNotNull null
            val output = usd("output") ?: return@mapNotNull null
            if (input > MAX_INPUT || output > MAX_OUTPUT) return@mapNotNull null
            Model(
                id = id,
                name = spec.optString("name").ifBlank { id },
                context = spec.optLong("availableContextTokens").takeIf { it > 0 } ?: m.optLong("context_length").takeIf { it > 0 },
                output = spec.optLong("maxCompletionTokens").takeIf { it > 0 },
                toolCall = caps.optBoolean("supportsFunctionCalling"),
                reasoning = caps.optBoolean("supportsReasoning"),
                efforts = if (caps.optBoolean("supportsReasoningEffort")) caps.optJSONArray("reasoningEffortOptions").strings() else emptyList(),
                vision = caps.optBoolean("supportsVision"),
                inputPrice = input,
                outputPrice = output,
                cachePrice = usd("cache_input"),
            )
        }.sortedWith(compareBy({ it.inputPrice }, { it.id }))
        // An empty answer would hide every Venice model; treat it as no answer.
        check(out.isNotEmpty()) { "no model meets the rule" }
        return out
    }

    /**
     * The `provider.venice` block. Each reasoning level is sent as `reasoning_effort`
     * (opencode's `reasoningEffort`); the level names opencode would add on its own
     * that the model does not accept are switched off, since Venice may answer 400.
     */
    fun providerConfig(models: List<Model>): JSONObject {
        val defs = JSONObject()
        for (m in models) {
            val def = JSONObject()
                .put("name", m.name)
                .put("tool_call", m.toolCall)
                .put("reasoning", m.reasoning)
                .put("attachment", m.vision)
                .put("modalities", JSONObject().put("input", JSONArray(if (m.vision) listOf("text", "image") else listOf("text"))).put("output", JSONArray().put("text")))
            val cost = JSONObject()
            m.inputPrice?.let { cost.put("input", it) }
            m.outputPrice?.let { cost.put("output", it) }
            m.cachePrice?.let { cost.put("cache_read", it) }
            if (cost.length() > 0) def.put("cost", cost)
            // opencode wants both limits or neither.
            if (m.context != null) def.put("limit", JSONObject().put("context", m.context).put("output", m.output ?: m.context))
            if (m.reasoning && m.efforts.isNotEmpty()) {
                val variants = JSONObject()
                m.efforts.forEach { variants.put(it, JSONObject().put("reasoningEffort", it)) }
                LEVELS.filter { it !in m.efforts }.forEach { variants.put(it, JSONObject().put("disabled", true)) }
                def.put("variants", variants)
            }
            defs.put(m.id, def)
        }
        return JSONObject()
            .put("npm", "@ai-sdk/openai-compatible")
            .put("name", "Venice")
            .put("options", JSONObject().put("baseURL", BASE_URL))
            .put("whitelist", JSONArray(models.map { it.id }))
            .put("models", defs)
    }

    /** "$0.07 in · $0.4 out per 1M tokens", for the model picker. */
    fun priceLabel(input: Double?, output: Double?): String? {
        if (input == null || output == null) return null
        fun usd(v: Double) = "$" + "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
        return "${usd(input)} in · ${usd(output)} out / 1M"
    }

    /** `venice/<id>` of the models that cannot call tools, which the app runs as chat only. */
    fun chatOnly(models: List<Model>): Set<String> = models.filter { !it.toolCall }.map { "$PROVIDER/${it.id}" }.toSet()

    private fun toJson(models: List<Model>): String = JSONArray().apply {
        models.forEach {
            put(
                JSONObject().put("id", it.id).put("name", it.name).put("context", it.context).put("output", it.output)
                    .put("toolCall", it.toolCall).put("reasoning", it.reasoning).put("efforts", JSONArray(it.efforts))
                    .put("vision", it.vision).put("in", it.inputPrice).put("out", it.outputPrice).put("cache", it.cachePrice),
            )
        }
    }.toString()

    private fun fromJson(text: String): List<Model> {
        val a = JSONArray(text)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            fun num(key: String): Double? = if (o.isNull(key)) null else o.optDouble(key)
            Model(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                context = o.optLong("context").takeIf { it > 0 },
                output = o.optLong("output").takeIf { it > 0 },
                toolCall = o.optBoolean("toolCall"),
                reasoning = o.optBoolean("reasoning"),
                efforts = o.optJSONArray("efforts").strings(),
                vision = o.optBoolean("vision"),
                inputPrice = num("in"),
                outputPrice = num("out"),
                cachePrice = num("cache"),
            )
        }
    }

    fun catalog(
        cache: File,
        log: (String) -> Unit = {},
        fetch: () -> String = { CachedCatalog.download(MODELS_URL) },
    ) = CachedCatalog(PROVIDER, cache, ::parse, ::toJson, ::fromJson, fetch, describe = { "${it.size} models offered" }, log = log)
}
