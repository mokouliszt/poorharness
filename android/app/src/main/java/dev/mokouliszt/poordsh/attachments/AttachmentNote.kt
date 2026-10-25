package dev.mokouliszt.poordsh.attachments

import dev.mokouliszt.poordsh.backend.SentFile
import dev.mokouliszt.poordsh.bridge.jsonOf
import org.json.JSONObject

/**
 * The text that tells the model about a message's files: where each one was put
 * in the sandbox, and which also reached it as an image or document. It travels
 * with the message (hidden like [dev.mokouliszt.poordsh.backend.ContextTag]), so
 * the engine's own history is the record and the UI turns it back into chips.
 */
object AttachmentNote {
    const val OPEN = "<poordsh-attachments>"
    const val CLOSE = "</poordsh-attachments>"
    private const val INTRO = "The user attached these files. They have been uploaded to the sandbox:"
    private const val INLINE = "also attached to this message"
    private val LINE = Regex("""^- (/.+) \((\d+) bytes, ([^,()]+?)(, $INLINE)?\)$""")

    data class Item(val name: String, val path: String, val size: Long, val mime: String, val inline: Boolean)

    fun matches(text: String) = text.trimStart().startsWith(OPEN)

    fun build(files: List<SentFile>): String = buildString {
        append(OPEN).append('\n').append(INTRO).append('\n')
        for (f in files) {
            append("- ").append(f.path).append(" (").append(f.size).append(" bytes, ").append(f.mime)
            if (f.inline != null) append(", ").append(INLINE)
            append(")\n")
        }
        append(CLOSE)
    }

    /** A message part for the UI: the files as chips. */
    fun part(id: String, items: List<Item>): JSONObject = jsonOf(
        "id" to id,
        "kind" to "attachments",
        "items" to items.map { jsonOf("name" to it.name, "path" to it.path, "size" to it.size, "mime" to it.mime, "inline" to it.inline) },
    )

    /** The files a note lists, or null when [text] is not a note. */
    fun parse(text: String): List<Item>? {
        if (!matches(text)) return null
        return text.lineSequence().mapNotNull { line ->
            val m = LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val path = m.groupValues[1]
            Item(path.substringAfterLast('/'), path, m.groupValues[2].toLong(), m.groupValues[3], m.groupValues[4].isNotEmpty())
        }.toList()
    }
}
