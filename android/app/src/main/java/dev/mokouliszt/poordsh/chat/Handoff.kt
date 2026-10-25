package dev.mokouliszt.poordsh.chat

import dev.mokouliszt.poordsh.backend.ContextTag
import dev.mokouliszt.poordsh.bridge.arr
import dev.mokouliszt.poordsh.bridge.objects
import org.json.JSONArray

/**
 * Builds the context a chat carries into a new engine session: the conversation
 * so far as plain text, wrapped in [ContextTag] so neither engine's history view
 * nor ours shows it. Files need no hand-off: both engines work in the chat's
 * sandbox, in /tmp/workspace. With [reset] the sandbox is new (the old one
 * expired, or the chat predates sandboxes), and the text says the files are gone.
 */
object Handoff {
    private const val LIMIT = 24_000
    private const val OMITTED = "[earlier messages omitted]"
    private const val INTRO =
        "This chat began with another assistant in the same app, working in the same sandbox and folder " +
            "(/tmp/workspace). The conversation so far is below for context. Continue from it; do not redo finished work."
    private const val INTRO_RESET =
        "This chat began earlier in the same app. Its sandbox has been replaced since, so files from before are gone " +
            "and /tmp/workspace starts empty. The conversation so far is below for context."

    fun build(messages: JSONArray, reset: Boolean = false): String? {
        val turns = mutableListOf<String>()
        for (m in messages.objects()) {
            val lines = mutableListOf<String>()
            for (p in m.arr("parts").objects()) {
                when (p.optString("kind")) {
                    "text" -> p.optString("text").trim()
                        .takeIf { it.isNotEmpty() && !ContextTag.matches(it) }?.let { lines += it }
                    "tool" -> lines += "[${p.optString("tool")}] ${p.optString("title").take(200)}"
                    "diff" -> lines += "[changed] " + p.arr("files").objects().joinToString(", ") { it.optString("path") }
                    "attachments" -> lines += "[attached] " + p.arr("items").objects().joinToString(", ") { it.optString("path") }
                }
            }
            if (lines.isEmpty()) continue
            val who = if (m.optString("role") == "user") "User" else "Assistant"
            turns += "$who: " + lines.joinToString("\n")
        }
        if (turns.isEmpty()) return null

        // Keep the opening message and as many of the latest ones as fit.
        val first = turns.first()
        val latest = ArrayDeque<String>()
        var size = first.length
        for (turn in turns.drop(1).asReversed()) {
            if (size + turn.length + 2 > LIMIT) break
            latest.addFirst(turn)
            size += turn.length + 2
        }
        val omitted = latest.size < turns.size - 1
        val parts = listOf(first) + (if (omitted) listOf(OMITTED) else emptyList()) + latest
        val text = parts.joinToString("\n\n").let { if (it.length > LIMIT) it.takeLast(LIMIT) else it }
        return "${ContextTag.OPEN}\n${if (reset) INTRO_RESET else INTRO}\n\n$text\n${ContextTag.CLOSE}"
    }
}
