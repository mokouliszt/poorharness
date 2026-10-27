package dev.mokouliszt.poordsh.settings

import android.content.Context
import dev.mokouliszt.poordsh.backend.EngineOptions
import dev.mokouliszt.poordsh.bridge.jsonOf
import org.json.JSONArray
import org.json.JSONObject

/** Plain (non-secret) preferences. No model is fixed here: the engines' catalogs decide. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    enum class Approval(val id: String) {
        FULL_AUTO("full_auto"), RISKY("risky"), ALWAYS("always");

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: FULL_AUTO
        }
    }

    var approval: Approval
        get() = Approval.of(prefs.getString("approval", null))
        set(v) { prefs.edit().putString("approval", v.id).apply() }

    /** Last model picked in the UI, as "<backend>:<model id>"; null = follow backend default. */
    var lastModel: String?
        get() = prefs.getString("last_model", null)
        set(v) { prefs.edit().putString("last_model", v).apply() }

    /** Last reasoning level picked for [lastModel]; null = the model's own default. */
    var lastEffort: String?
        get() = prefs.getString("last_effort", null)
        set(v) { prefs.edit().putString("last_effort", v).apply() }

    /** "system", "ja" or "en". */
    var language: String
        get() = prefs.getString("language", "system") ?: "system"
        set(v) { prefs.edit().putString("language", v).apply() }

    /** Diagnostics escape hatch for devices where PRoot's seccomp acceleration misbehaves. */
    var disableSeccomp: Boolean
        get() = prefs.getBoolean("disable_seccomp", false)
        set(v) { prefs.edit().putBoolean("disable_seccomp", v).apply() }

    /** Where handed-over files go: "device" or "s3" (docs/adr/outputs.md). */
    var outputsDestination: String
        get() = prefs.getString("outputs_destination", "device") ?: "device"
        set(v) { prefs.edit().putString("outputs_destination", v).apply() }

    /** The user's system prompt, given to both engines ([dev.mokouliszt.poordsh.backend.SystemPrompt]); "" = none. */
    var systemPrompt: String
        get() = prefs.getString("system_prompt", "") ?: ""
        set(v) { prefs.edit().putString("system_prompt", v.trim()).commit() }

    /** "<engine>:<provider>/<model>" subagents run on; null = the turn's model (docs/adr/subagent-model.md). */
    var subagentModel: String?
        get() = prefs.getString("subagent_model", null)
        set(v) { prefs.edit().putString("subagent_model", v).apply() }

    /** The reasoning level (opencode variant) for [subagentModel]; null = the model's own default. */
    var subagentEffort: String?
        get() = prefs.getString("subagent_effort", null)
        set(v) { prefs.edit().putString("subagent_effort", v).apply() }

    /** "<engine>:<provider>/<model>" chat titles are written with; null = left to opencode (docs/adr/title-model.md). */
    var titleModel: String?
        get() = prefs.getString("title_model", null)
        set(v) { prefs.edit().putString("title_model", v).apply() }

    /** Install newer opencode releases when the app finds them at launch. */
    var autoUpdateEngines: Boolean
        get() = prefs.getBoolean("auto_update_engines", false)
        set(v) { prefs.edit().putBoolean("auto_update_engines", v).apply() }

    /**
     * Engine features that are on (ids from [EngineOptions]): each option the user
     * switched, as switched, and the rest at their default. Stored as one state per
     * option; 0.3–0.8 stored only the ones turned on (`engine_options`).
     */
    var engineOptions: Set<String>
        get() {
            val states = runCatching { JSONObject(prefs.getString("engine_option_states", null) ?: return legacyOptions()) }
                .getOrNull() ?: return legacyOptions()
            return EngineOptions.ALL.filter { if (states.has(it.id)) states.optBoolean(it.id) else it.default }.map { it.id }.toSet()
        }
        set(v) {
            val on = EngineOptions.sanitize(v)
            val states = JSONObject().apply { EngineOptions.ALL.forEach { put(it.id, it.id in on) } }
            prefs.edit().putString("engine_option_states", states.toString()).apply()
        }

    private fun legacyOptions(): Set<String> = EngineOptions.DEFAULTS + EngineOptions.sanitize(
        runCatching { JSONArray(prefs.getString("engine_options", "[]")) }
            .getOrNull()?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
    )

    fun toJson(): JSONObject = jsonOf(
        "approval" to approval.id,
        "lastModel" to lastModel,
        "lastEffort" to lastEffort,
        "language" to language,
        "disableSeccomp" to disableSeccomp,
        "autoUpdate" to autoUpdateEngines,
        "outputsDestination" to outputsDestination,
        "systemPrompt" to systemPrompt,
        "subagentModel" to subagentModel,
        "subagentEffort" to subagentEffort,
        "titleModel" to titleModel,
    )
}
