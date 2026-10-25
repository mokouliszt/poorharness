package dev.mokouliszt.poordsh.outputs

import java.io.File
import java.io.InputStream

/**
 * The outputs folder (docs/adr/outputs.md): what an agent puts in [DIR], in the
 * chat's sandbox, is handed to the user when its turn ends, onto the phone or
 * into the user's S3-compatible bucket ([OutputShelf]), and shows in the chat as
 * a file to save or share. Everything else in the sandbox stays there.
 *
 * The folder takes files only. The engines are told so ([INSTRUCTIONS]), and
 * opencode's plugin refuses writes into subfolders; a folder that is there
 * anyway is handed over as one `<folder>.zip`, so a delivery is always flat.
 * Plain JVM code, tested on the build host.
 */
object Outputs {
    const val DIR = "/tmp/workspace/outputs"

    /** What both engines are told, in their own instruction channel. */
    val INSTRUCTIONS = """
        Handing files to the user: save each file the user should receive directly in $DIR/.
        When your turn ends, every new or changed file there is handed to the user, who can save it on their phone.
        Put files there only, never subfolders; to hand over several files together, pack them into one .zip in that folder.
        Keep working files elsewhere. The files you hand over appear under your reply, so name them there without linking to them.
    """.trimIndent()

    /** One thing at the top of [DIR]. [signature] changes whenever its content may have. */
    data class Entry(val name: String, val isDir: Boolean, val size: Long, val signature: String) {
        /** The name it is handed over as. */
        val deliveredName: String get() = if (isDir) "$name.zip" else name
    }

    /** Where a chat's outputs folder is read: a Tensorlake sandbox or a device jail. */
    interface Source {
        /** The top level of [DIR]; empty when it does not exist. */
        fun list(): List<Entry>
        /** Writes [entry] to [to]: a file as it is, a folder as a zip of it. */
        fun fetch(entry: Entry, to: File)
    }

    /** A name that is handed over: no hidden or temporary files, nothing that could leave the chat's folder. */
    fun deliverable(name: String): Boolean =
        name.isNotEmpty() && name.length <= 200 && !name.startsWith(".") && !name.endsWith("~") && '/' !in name && '\\' !in name

    /**
     * Entries new or changed since [delivered] (delivered name to signature). A
     * folder whose zip name a file already has is left out: the file is the one handed over.
     */
    fun changed(entries: List<Entry>, delivered: Map<String, String>): List<Entry> {
        val files = entries.filter { !it.isDir }.map { it.name }.toSet()
        return entries.filter { deliverable(it.name) && !(it.isDir && it.deliveredName in files) && delivered[it.deliveredName] != it.signature }
            .sortedBy { it.name }
    }

    fun fileSignature(size: Long, modifiedAt: Long) = "$size:$modifiedAt"

    /** Copies [input] into [to], refusing more than [max] bytes. */
    fun copyLimited(input: InputStream, to: File, max: Long) {
        to.parentFile?.mkdirs()
        input.use { src ->
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) throw java.io.IOException("output_too_large")
                    out.write(buf, 0, n)
                }
            }
        }
    }
}
