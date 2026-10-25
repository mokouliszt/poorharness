package dev.mokouliszt.poordsh.backend

import dev.mokouliszt.poordsh.outputs.Outputs

/**
 * The user's system prompt (docs/adr/system-prompt.md). opencode gets it after
 * the app's own instructions, in its `instructions` file, which adds to its
 * built-in prompt rather than replacing it.
 */
object SystemPrompt {
    const val MAX_CHARS = 20_000

    /** What opencode is told: the app's instructions ([app]), then the user's prompt. */
    fun compose(prompt: String, app: List<String> = listOf(Outputs.INSTRUCTIONS)): String =
        (app + prompt.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
}
