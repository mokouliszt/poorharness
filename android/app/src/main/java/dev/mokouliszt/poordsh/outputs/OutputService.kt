package dev.mokouliszt.poordsh.outputs

import java.io.File
import java.util.UUID

/**
 * Hands a chat's new outputs over when a turn ends ([Outputs]). Each file goes to
 * the shelf the user chose; when the bucket cannot take it, it goes to the phone
 * instead, so nothing an agent handed over is lost. Plain JVM code.
 */
class OutputService(
    val device: OutputShelf.Device,
    /** The bucket's shelf when one is connected. */
    private val s3: () -> OutputShelf?,
    /** [OutputShelf.DEVICE] or [OutputShelf.S3]. */
    private val destination: () -> String,
    /** Where a file waits between the sandbox and its shelf. */
    private val staging: File,
    /** What a chat has handed over: delivered name to signature. */
    private val delivered: (chatId: String) -> Map<String, String>,
    private val record: (chatId: String, name: String, signature: String) -> Unit,
    private val log: (String) -> Unit = {},
    /** Free space the phone keeps after a delivery. */
    private val reserve: Long = 200L shl 20,
) {
    /** One file handed over; [error] when it could not be kept anywhere. */
    data class Delivered(val name: String, val size: Long, val shelf: String, val error: String? = null)

    fun shelf(id: String): OutputShelf? = if (id == OutputShelf.S3) s3() else device

    /** Hands over what is new or changed in [source] since the last time; [onStart] runs once there is something. */
    fun collect(chatId: String, source: Outputs.Source, onStart: () -> Unit = {}): List<Delivered> {
        val entries = runCatching { source.list() }.getOrElse {
            log("$chatId: outputs not read (${it.message})")
            return emptyList()
        }
        val todo = Outputs.changed(entries, delivered(chatId))
        if (todo.isEmpty()) return emptyList()
        onStart()
        staging.mkdirs()
        val out = mutableListOf<Delivered>()
        for (e in todo) {
            val tmp = File(staging, UUID.randomUUID().toString())
            try {
                source.fetch(e, tmp)
                out += keep(chatId, e.deliveredName, tmp)
                if (out.last().error == null) record(chatId, e.deliveredName, e.signature)
            } catch (ex: Exception) {
                log("$chatId: ${e.name} not handed over (${ex.message})")
                out += Delivered(e.deliveredName, e.size, destination(), "output_failed")
            } finally {
                tmp.delete()
            }
        }
        return out
    }

    private fun keep(chatId: String, name: String, file: File): Delivered {
        val size = file.length()
        if (destination() == OutputShelf.S3) {
            val bucket = s3()
            if (bucket != null) {
                try {
                    bucket.put(chatId, name, file)
                    return Delivered(name, size, OutputShelf.S3)
                } catch (e: Exception) {
                    log("$chatId: $name not stored in the bucket (${e.message}); kept on the phone")
                }
            } else {
                log("$chatId: no bucket connected; $name kept on the phone")
            }
        }
        if (device.dir.apply { mkdirs() }.usableSpace - size < reserve) return Delivered(name, size, OutputShelf.DEVICE, "output_no_space")
        device.put(chatId, name, file)
        return Delivered(name, size, OutputShelf.DEVICE)
    }
}
