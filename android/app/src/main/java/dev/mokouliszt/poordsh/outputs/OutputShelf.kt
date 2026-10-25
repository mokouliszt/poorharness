package dev.mokouliszt.poordsh.outputs

import dev.mokouliszt.poordsh.storage.S3Storage
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Where handed-over files are kept, one folder per chat ([Outputs]): on the phone
 * ([Device]) or in the user's S3-compatible bucket ([S3]). A file of the same
 * name in the same chat replaces the one before. Plain JVM code.
 */
interface OutputShelf {
    /** "device" or "s3", as stored with each delivery. */
    val id: String

    data class Item(val chatId: String, val name: String, val size: Long, val modifiedAt: Long)

    fun put(chatId: String, name: String, file: File)
    /** The file, or null when it is no longer kept. The caller closes it. */
    fun open(chatId: String, name: String): InputStream?
    fun list(): List<Item>
    fun delete(chatId: String, name: String)
    fun deleteAll()

    /** On the phone: `<dir>/<chat id>/<name>` (the Ubuntu home's `outputs` folder). */
    class Device(val dir: File) : OutputShelf {
        override val id = DEVICE

        override fun put(chatId: String, name: String, file: File) {
            val target = File(folder(chatId), checkName(name))
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".$name.part")
            file.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("could not keep $name")
            }
        }

        override fun open(chatId: String, name: String): InputStream? =
            File(folder(chatId), checkName(name)).takeIf { it.isFile }?.inputStream()

        override fun list(): List<Item> = dir.listFiles().orEmpty().filter { it.isDirectory && CHAT.matches(it.name) }.flatMap { chat ->
            chat.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }
                .map { Item(chat.name, it.name, it.length(), it.lastModified()) }
        }

        override fun delete(chatId: String, name: String) {
            File(folder(chatId), checkName(name)).delete()
            folder(chatId).takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        }

        override fun deleteAll() {
            dir.listFiles().orEmpty().filter { it.isDirectory && CHAT.matches(it.name) }.forEach { it.deleteRecursively() }
        }

        private fun folder(chatId: String) = File(dir, checkChat(chatId))
    }

    /** In the bucket's `outputs` folder: `<prefix>/outputs/<chat id>/<name>`. */
    class S3(private val space: S3Storage.Space) : OutputShelf {
        override val id = S3

        override fun put(chatId: String, name: String, file: File) = space.put("${checkChat(chatId)}/${checkName(name)}", file)

        override fun open(chatId: String, name: String): InputStream? = space.get("${checkChat(chatId)}/${checkName(name)}")

        override fun list(): List<Item> = space.list().mapNotNull { item ->
            val chat = item.key.substringBefore('/', "")
            val name = item.key.substringAfter('/', "")
            if (!CHAT.matches(chat) || name.isEmpty() || '/' in name) null else Item(chat, name, item.size, item.modifiedAt)
        }

        override fun delete(chatId: String, name: String) = space.delete("${checkChat(chatId)}/${checkName(name)}")

        override fun deleteAll() = space.deleteAll()
    }

    companion object {
        const val DEVICE = "device"
        const val S3 = "s3"
        private val CHAT = Regex("^c_[A-Za-z0-9]+$")

        fun checkChat(chatId: String): String {
            require(CHAT.matches(chatId)) { "bad chat id" }
            return chatId
        }

        fun checkName(name: String): String {
            require(Outputs.deliverable(name)) { "bad file name" }
            return name
        }
    }
}
