package dev.mokouliszt.poordsh.attachments

/**
 * Decisions about attached files that need no Android: names in the sandbox,
 * and whether a file can also go to the model directly. Plain JVM code, tested
 * on the build host.
 */
object Attachments {
    /** Where a chat's attached files land, in its sandbox. */
    const val DIR = "/tmp/workspace/attachments"

    /** Data-URL images: within what common providers accept (Anthropic's 5 MB is the lowest). */
    const val IMAGE_MAX = 5L shl 20
    /** Data-URL PDFs, for models that take them. */
    const val PDF_MAX = 10L shl 20

    /** Image types every multimodal provider takes; others (HEIC, SVG, TIFF…) are only uploaded. */
    val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    enum class Inline { NONE, AS_IS, SHRINK }

    /**
     * Whether a file also reaches the model directly, for a model that takes
     * [inputs] besides text. Everything else is only uploaded, and the model is
     * told where it is.
     */
    fun inlineFor(inputs: Set<String>, mime: String, size: Long): Inline {
        val image = mime in IMAGE_TYPES && "image" in inputs
        return when {
            image -> if (size <= IMAGE_MAX) Inline.AS_IS else Inline.SHRINK
            mime == "application/pdf" && "pdf" in inputs && size <= PDF_MAX -> Inline.AS_IS
            else -> Inline.NONE
        }
    }

    /** A file name safe for the sandbox: no folders, no control characters. */
    fun safeName(raw: String?): String {
        val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' }.trim()
        return base.take(200).takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "file"
    }

    /** [name], or "name (2).ext", "name (3).ext"… when it is taken. */
    fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val ext = name.substring(dot)
        var n = 2
        while ("$stem ($n)$ext" in taken) n++
        return "$stem ($n)$ext"
    }

    /** "image/jpeg; charset=…" -> "image/jpeg"; unknown -> application/octet-stream. */
    fun cleanMime(mime: String?): String =
        mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { Regex("^[a-z0-9.+-]+/[a-z0-9.+-]+$").matches(it) }
            ?: "application/octet-stream"
}
