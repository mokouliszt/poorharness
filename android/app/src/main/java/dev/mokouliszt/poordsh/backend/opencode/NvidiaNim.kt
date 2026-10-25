package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.backend.opencode.FreeGateway.Companion.strings
import org.json.JSONArray
import org.json.JSONObject

/**
 * NVIDIA NIM, which opencode knows from models.dev as "nvidia" (verified 2026-10).
 *
 * models.dev's copy can list models NIM no longer serves. NIM's own list
 * (`/v1/models`, no key needed) has every model it serves, but not whether a model
 * calls tools, which only models.dev says. So the app whitelists NIM's list and
 * keeps models.dev's definitions: a model is offered when both list it (and it
 * calls tools, as for every provider). Without either list, opencode's full
 * models.dev list stays, as before.
 */
object NvidiaNim {
    const val PROVIDER = "nvidia"
    const val MODELS_URL = "https://integrate.api.nvidia.com/v1/models"

    /** The ids NIM serves. Throws on a body that is not its list. */
    fun parse(body: String): List<String> {
        val data = JSONObject(body).getJSONArray("data")
        val ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
        // An empty answer would hide every NIM model; treat it as no answer.
        check(ids.isNotEmpty()) { "empty model list" }
        return ids.sorted()
    }

    fun providerConfig(ids: List<String>): JSONObject = JSONObject().put("whitelist", JSONArray(ids))

    fun catalog(
        cache: java.io.File,
        log: (String) -> Unit = {},
        fetch: () -> String = { CachedCatalog.download(MODELS_URL) },
    ) = CachedCatalog(
        PROVIDER, cache, ::parse,
        encode = { JSONArray(it).toString() },
        decode = { JSONArray(it).strings() },
        fetch = fetch,
        describe = { "${it.size} models served" },
        log = log,
    )
}
