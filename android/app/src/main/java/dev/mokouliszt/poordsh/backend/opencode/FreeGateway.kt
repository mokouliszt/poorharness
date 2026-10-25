package dev.mokouliszt.poordsh.backend.opencode

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * An OpenAI-compatible gateway whose free models the app offers through opencode
 * ([OrcaRouter], [Infron]; docs/adr/gateway-free-models.md).
 *
 * Each gateway publishes a model catalog that needs no key and says which models
 * are free. With the gateway's key stored, the app reads that catalog at each
 * opencode start and writes a `provider.<id>` block: the free models an agent can
 * use (tools, text out), defined from the gateway's own fields and whitelisted, so
 * a paid model is never offered and one published upstream shows up at the next
 * start without an app update.
 */
abstract class FreeGateway(
    /** opencode's provider id, also the key's name in its auth store. */
    val id: String,
    /** Public, no key needed. */
    val catalogUrl: String,
) {
    data class Model(
        val id: String,
        val name: String,
        val context: Long?,
        val output: Long?,
        val inputs: List<String>,
        /** Null when the gateway does not say; opencode then decides from the model. */
        val reasoning: Boolean?,
    )

    /** Free models an agent can use, from the catalog's body. Throws on a body that is not the catalog. */
    abstract fun parse(body: String): List<Model>

    /**
     * How opencode reaches a gateway models.dev does not know (`npm`, `name`,
     * `options.baseURL`). Empty for one it knows: opencode keeps its own settings.
     */
    open fun connection(): JSONObject = JSONObject()

    /**
     * The reasoning levels offered for [m], as opencode `variants` (name → provider
     * options sent with the request). Null leaves them to opencode, which derives
     * them from models.dev for a model it knows.
     */
    open fun variants(m: Model): JSONObject? = null

    /**
     * The `provider.<id>` block of the opencode config. For a model models.dev also
     * knows, the fields here override its; the rest (such as how reasoning is passed
     * back, or effort variants) stay opencode's.
     */
    fun providerConfig(models: List<Model>): JSONObject {
        val defs = JSONObject()
        for (m in models) {
            val def = JSONObject()
                .put("name", m.name)
                .put("tool_call", true)
                .put("cost", JSONObject().put("input", 0).put("output", 0))
            m.reasoning?.let { def.put("reasoning", it) }
            variants(m)?.let { def.put("variants", it) }
            if (m.inputs.isNotEmpty()) {
                def.put("attachment", m.inputs.any { it != "text" })
                def.put("modalities", JSONObject().put("input", JSONArray(m.inputs)).put("output", JSONArray().put("text")))
            }
            // opencode wants both or neither, and rejects the whole config otherwise. An
            // unknown output limit becomes the context size; opencode caps a reply at 32k anyway.
            if (m.context != null) def.put("limit", JSONObject().put("context", m.context).put("output", m.output ?: m.context))
            defs.put(m.id, def)
        }
        val block = JSONObject(connection().toString())
        return block
            .put("whitelist", JSONArray(models.map { it.id }))
            .put("models", defs)
    }

    /**
     * The free list, fetched from the gateway and cached ([CachedCatalog]). Null when
     * neither is available: then none of the gateway's models is offered.
     */
    class FreeModels(
        gateway: FreeGateway,
        cache: File,
        log: (String) -> Unit = {},
        /** The catalog's body; throws when it cannot be had. */
        fetch: () -> String = { CachedCatalog.download(gateway.catalogUrl) },
    ) {
        private val catalog = CachedCatalog(
            gateway.id, cache, gateway::parse, ::toJson, ::fromJson, fetch,
            describe = { "${it.size} free models" }, log = log,
        )

        fun load(): List<Model>? = catalog.load()
    }

    companion object {
        /** Every gateway the app offers; their ids are also key providers ([OpencodeConfig.KEY_PROVIDERS]). */
        val ALL: List<FreeGateway> by lazy { listOf(OrcaRouter, Infron) }

        fun byId(id: String): FreeGateway? = ALL.firstOrNull { it.id == id }

        /** What opencode's config accepts as a modality; anything else would fail validation. */
        val MODALITIES = setOf("text", "audio", "image", "video", "pdf")
        private val FREE_SUFFIX = Regex("""\s*\((?i:free)\)\s*$""")

        /**
         * "DeepSeek: DeepSeek V4.1 Flash (Free)" → "DeepSeek V4.1 Flash": the picker's
         * group for a gateway holds its free models only.
         */
        fun cleanName(displayName: String, id: String): String =
            displayName.substringAfter(": ").replace(FREE_SUFFIX, "").ifBlank { id }

        fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

        fun toJson(models: List<Model>): String = JSONArray().apply {
            models.forEach {
                put(
                    JSONObject().put("id", it.id).put("name", it.name).put("context", it.context).put("output", it.output)
                        .put("inputs", JSONArray(it.inputs)).put("reasoning", it.reasoning ?: JSONObject.NULL),
                )
            }
        }.toString()

        fun fromJson(text: String): List<Model> {
            val a = JSONArray(text)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Model(
                    id = o.getString("id"),
                    name = o.optString("name", o.getString("id")),
                    context = o.optLong("context").takeIf { it > 0 },
                    output = o.optLong("output").takeIf { it > 0 },
                    inputs = o.optJSONArray("inputs").strings(),
                    reasoning = if (o.isNull("reasoning")) null else o.optBoolean("reasoning"),
                )
            }
        }
    }
}
