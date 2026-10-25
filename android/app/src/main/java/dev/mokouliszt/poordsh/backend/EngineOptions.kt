package dev.mokouliszt.poordsh.backend

/**
 * Engine features the user can switch, listed in the UI's options sheet
 * (docs/adr/engine-options.md): plainly useful ones that are off by default, and
 * subagents, which are on by default. Web search stays on with no switch. The
 * host owns how each one is applied (opencode config and environment); the UI
 * only shows the id (worded by `opt.<id>` / `opt.<id>.desc` in
 * i18n) and which are on ([dev.mokouliszt.poordsh.settings.Settings.engineOptions]).
 *
 * Changing an option restarts the engine when it is next idle, like any other
 * setting that needs a restart.
 */
object EngineOptions {
    /** [default]: on until the user turns it off. [requires]: an option this one does nothing without. */
    data class Option(val id: String, val engine: String, val default: Boolean = false, val requires: String? = null)

    /** opencode's task tool and its background subagents. */
    const val SUBAGENTS = "subagents"
    const val BATCH_TOOL = "batch_tool"
    const val PRUNE = "prune"
    const val EXPERIMENTAL_MODELS = "experimental_models"

    val ALL = listOf(
        // opencode (its provider models, Zen and NVIDIA NIM alike, run through opencode).
        Option(SUBAGENTS, "opencode", default = true),
        Option(BATCH_TOOL, "opencode"),
        Option(PRUNE, "opencode"),
        Option(EXPERIMENTAL_MODELS, "opencode"),
    )

    private val IDS = ALL.map { it.id }.toSet()

    /** What is on before the user changes anything. */
    val DEFAULTS: Set<String> = ALL.filter { it.default }.map { it.id }.toSet()

    /** Keeps only ids this build knows, so a stored option dropped from a later build is ignored. */
    fun sanitize(ids: Collection<String>): Set<String> = ids.filterTo(LinkedHashSet()) { it in IDS }

    fun forEngine(engine: String, enabled: Set<String>): Set<String> =
        ALL.filter { it.engine == engine && it.id in enabled }.map { it.id }.toSet()
}
