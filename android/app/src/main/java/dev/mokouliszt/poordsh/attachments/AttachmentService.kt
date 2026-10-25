package dev.mokouliszt.poordsh.attachments

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.mokouliszt.poordsh.backend.Place
import dev.mokouliszt.poordsh.backend.SentFile
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.phone.PhoneSandboxManager
import dev.mokouliszt.poordsh.runtime.LogBuffer
import dev.mokouliszt.poordsh.tensorlake.SandboxManager
import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Files attached to a message. Picking one copies it into the app ([dir]) at
 * once, after checking it fits where the chat's files live; sending moves it
 * into the chat's sandbox ([Attachments.DIR]) and tells the model about it.
 * Size is limited only by free space. Unsent files are dropped on the next start.
 */
class AttachmentService(
    private val resolver: ContentResolver,
    private val dir: File,
    private val cacheDir: File,
    private val api: TensorlakeApi,
    private val sandboxes: SandboxManager,
    private val phoneSandboxes: PhoneSandboxManager,
    /** Where chat [chatId] (null: a chat not created yet) keeps its files. */
    private val placeFor: (chatId: String?) -> Place,
    /** Whether the chat has run, so its sandbox may exist. */
    private val hasRun: (chatId: String) -> Boolean,
    private val log: LogBuffer,
) {
    /** Not enough room; the UI says how much was needed and how much is free. */
    class NoSpace(val where: String, val needed: Long, val free: Long) : IOException("attach_no_space")

    class Staged(val id: String, val name: String, val size: Long, val mime: String, val file: File) {
        /** Set once the file is in a chat's sandbox, so a send that failed later does not upload it twice. */
        @Volatile var delivered: Delivered? = null
    }

    /** A file in chat [chatId]'s sandbox at [path]; [local] is a readable copy on the phone. */
    data class Delivered(val chatId: String, val path: String, val local: File)

    private val staged = ConcurrentHashMap<String, Staged>()

    init {
        dir.deleteRecursively()
        File(cacheDir, INLINE_DIR).deleteRecursively()
    }

    fun json(s: Staged): JSONObject = jsonOf("id" to s.id, "name" to s.name, "size" to s.size, "mime" to s.mime)

    /** Copies a picked file in, refusing it when it cannot fit where chat [chatId]'s files go. */
    suspend fun stage(uri: Uri, chatId: String?): Staged = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        val safe = Attachments.safeName(name ?: uri.lastPathSegment)
        val mime = Attachments.cleanMime(
            resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(safe.substringAfterLast('.', "").lowercase()),
        )
        size?.let { checkRoom(chatId, safe, it) }
        val id = UUID.randomUUID().toString()
        val file = File(File(dir, id).apply { mkdirs() }, safe)
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("could not open $safe")
            input.use { src -> file.outputStream().use { src.copyTo(it, 256 * 1024) } }
            // Some providers report no size up front; check the real one.
            if (size == null) checkRoom(chatId, safe, file.length())
        } catch (e: Exception) {
            File(dir, id).deleteRecursively()
            throw e
        }
        Staged(id, safe, file.length(), mime, file).also { staged[id] = it }
    }

    fun nameOf(id: String): String = staged[id]?.name ?: "file"

    fun discard(ids: Collection<String>) = ids.forEach { id ->
        staged.remove(id)
        File(dir, id).deleteRecursively()
    }

    /** After the message went out: its files live in the sandbox now. */
    fun finish(ids: Collection<String>) {
        discard(ids)
        ids.forEach { File(File(cacheDir, INLINE_DIR), "$it.jpg").delete() }
    }

    /**
     * Puts the files [ids] into chat [chatId]'s sandbox (a move on the phone, an
     * upload to Tensorlake) and returns where each one is. [progress] gets 0..1.
     */
    suspend fun deliver(
        chatId: String,
        place: Place,
        sandbox: TensorlakeApi.Sandbox?,
        ids: List<String>,
        progress: (Double) -> Unit = {},
    ): List<Pair<Staged, Delivered>> = withContext(Dispatchers.IO) {
        val files = ids.map { staged[it] ?: throw IOException("attachment_missing") }
        val todo = files.filter { it.delivered?.chatId != chatId }
        if (todo.isNotEmpty()) {
            if (place == Place.PHONE) deliverToJail(chatId, todo) else deliverToCloud(chatId, sandbox!!, todo, progress)
        }
        files.map { it to it.delivered!! }
    }

    private fun deliverToJail(chatId: String, files: List<Staged>) {
        val target = File(phoneSandboxes.dirs(chatId).workspace, Attachments.DIR.removePrefix(PhoneSandboxManager.WORKSPACE + "/"))
        target.mkdirs()
        val taken = target.list().orEmpty().toMutableSet()
        for (f in files) {
            val name = Attachments.uniqueName(f.name, taken).also { taken += it }
            val out = File(target, name)
            // Same filesystem: a rename, however big the file.
            if (!f.file.renameTo(out)) {
                f.file.copyTo(out)
                f.file.delete()
            }
            f.delivered = Delivered(chatId, "${Attachments.DIR}/$name", out)
        }
    }

    private fun deliverToCloud(chatId: String, sb: TensorlakeApi.Sandbox, files: List<Staged>, progress: (Double) -> Unit) {
        val total = files.sumOf { it.size }
        api.freeBytes(sb, SandboxManager.WORKSPACE)?.let { free ->
            if (total > free - SANDBOX_RESERVE) throw NoSpace("sandbox", total, (free - SANDBOX_RESERVE).coerceAtLeast(0))
        }
        val taken = try {
            api.listDir(sb, Attachments.DIR).map { it.name }.toMutableSet()
        } catch (e: TensorlakeApi.ApiException) {
            if (e.code == 404) mutableSetOf() else throw e
        }
        var done = 0L
        for (f in files) {
            val name = Attachments.uniqueName(f.name, taken).also { taken += it }
            val path = "${Attachments.DIR}/$name"
            api.upload(sb, path, f.file) { sent -> if (total > 0) progress((done + sent).toDouble() / total) }
            done += f.size
            log.add("${SandboxManager.nameFor(chatId)}: uploaded $name (${f.size} bytes)")
            f.delivered = Delivered(chatId, path, f.file)
        }
    }

    /**
     * What the engine gets: every file's sandbox path, and for those the model
     * takes directly ([Attachments.inlineFor]) the bytes.
     */
    fun sent(delivered: List<Pair<Staged, Delivered>>, inputs: Set<String>): List<SentFile> =
        delivered.map { (s, d) ->
            val inline = when (Attachments.inlineFor(inputs, s.mime, s.size)) {
                Attachments.Inline.NONE -> null
                Attachments.Inline.AS_IS -> SentFile.Inline(s.mime, d.local)
                Attachments.Inline.SHRINK -> shrink(s.id, d.local)?.let { SentFile.Inline("image/jpeg", it) }
            }
            SentFile(s.name, d.path, s.size, s.mime, inline)
        }

    /** A JPEG of at most [MAX_SIDE] px and [Attachments.IMAGE_MAX] bytes, or null if the image cannot be read. */
    private fun shrink(id: String, src: File): File? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(decoded.width, decoded.height))
        val w = (decoded.width * scale).toInt().coerceAtLeast(1)
        val h = (decoded.height * scale).toInt().coerceAtLeast(1)
        // Flattened onto white: JPEG has no transparency.
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(decoded, w, h, true), 0f, 0f, null)
        }
        val file = File(File(cacheDir, INLINE_DIR).apply { mkdirs() }, "$id.jpg")
        for (quality in intArrayOf(85, 70, 55)) {
            file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (file.length() <= Attachments.IMAGE_MAX) return file
        }
        null
    }.getOrNull()

    /** Throws [NoSpace] when [size] more bytes would not fit on the phone or in the chat's sandbox. */
    private suspend fun checkRoom(chatId: String?, name: String, size: Long) {
        val pending = staged.values.filter { it.delivered == null }.sumOf { it.size }
        dir.mkdirs()
        val device = StatFs(dir.path).availableBytes - DEVICE_RESERVE - pending
        if (size > device) throw NoSpace("device", size, device.coerceAtLeast(0))
        if (placeFor(chatId) == Place.CLOUD) {
            val free = cloudFree(chatId) - SANDBOX_RESERVE - pending
            if (size > free) throw NoSpace("sandbox", size, free.coerceAtLeast(0))
        }
        log.add("attach: $name ($size bytes) fits")
    }

    /** Free space in the chat's Tensorlake sandbox, or what a new one has. Wakes a paused one to ask. */
    private suspend fun cloudFree(chatId: String?): Long {
        if (chatId == null || !hasRun(chatId)) return NEW_SANDBOX_FREE
        val box = SandboxManager.nameFor(chatId)
        val sb = runCatching { api.get(box) }.getOrNull()
        if (sb == null || sb.status == "terminated") return NEW_SANDBOX_FREE
        return runCatching {
            if (sb.running) api.freeBytes(sb, SandboxManager.WORKSPACE)
            else sandboxes.use(box, "attach") { api.freeBytes(it.sandbox, SandboxManager.WORKSPACE) }
        }.getOrNull() ?: NEW_SANDBOX_FREE
    }

    companion object {
        private const val INLINE_DIR = "attachments-inline"
        private const val MAX_SIDE = 2048
        /** Left free on the phone so the app and the jails keep working. */
        private const val DEVICE_RESERVE = 200L shl 20
        private const val SANDBOX_RESERVE = 64L shl 20
        /** A new sandbox's free space (its disk minus the OS image), used before one exists. */
        val NEW_SANDBOX_FREE = TensorlakeApi.DISK_MB.toLong() * (1 shl 20) * 85 / 100
    }
}
