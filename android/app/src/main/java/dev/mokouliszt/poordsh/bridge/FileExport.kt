package dev.mokouliszt.poordsh.bridge

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Hands a file from a sandbox to the person: saved under Download/PoorHarness
 * (Android 10+, no permission needed) or offered through the share sheet.
 */
class FileExport(private val context: Context) {

    /** Returns where it went: "downloads", or "share" when saving is not possible on this Android. */
    fun save(name: String, input: InputStream): String {
        if (Build.VERSION.SDK_INT < 29) {
            share(name, input)
            return "share"
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime(name))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PoorHarness")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("could not create the file in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { out -> input.use { it.copyTo(out) } }
                ?: throw IOException("could not write to Downloads")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return "downloads"
    }

    fun share(name: String, input: InputStream) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
        val file = File(dir, name)
        file.outputStream().use { out -> input.use { it.copyTo(out) } }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime(name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}
