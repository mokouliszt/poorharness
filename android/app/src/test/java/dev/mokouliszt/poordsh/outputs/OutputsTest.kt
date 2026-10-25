package dev.mokouliszt.poordsh.outputs

import dev.mokouliszt.poordsh.chat.ChatStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Handing over what an agent puts in the outputs folder (docs/adr/outputs.md). */
class OutputsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val chat = "c_0123456789abcdef"

    @Test
    fun onlyNewOrChangedFilesAreHandedOver() {
        val a = Outputs.Entry("report.md", false, 10, "10:1")
        val b = Outputs.Entry("data.csv", false, 5, "5:1")
        assertEquals(listOf(b, a), Outputs.changed(listOf(a, b), emptyMap()))
        assertEquals(listOf(b), Outputs.changed(listOf(a, b), mapOf("report.md" to "10:1")))
        assertEquals(emptyList<Outputs.Entry>(), Outputs.changed(listOf(a, b), mapOf("report.md" to "10:1", "data.csv" to "5:1")))
        // Hidden and temporary files stay.
        assertTrue(Outputs.changed(listOf(Outputs.Entry(".~lock.x#", false, 1, "1:1"), Outputs.Entry("x~", false, 1, "1:1")), emptyMap()).isEmpty())
        // A folder goes as <folder>.zip, unless a file already has that name.
        val dir = Outputs.Entry("pics", true, 0, "dir:abc")
        assertEquals(listOf("pics.zip"), Outputs.changed(listOf(dir), emptyMap()).map { it.deliveredName })
        assertTrue(Outputs.changed(listOf(dir, Outputs.Entry("pics.zip", false, 3, "3:1")), mapOf("pics.zip" to "3:1")).isEmpty())
    }

    @Test
    fun aFolderIsHandedOverAsOneZip() {
        val dir = tmp.newFolder("outputs")
        File(dir, "summary.md").writeText("# Summary\n")
        File(dir, "pics/a").mkdirs()
        File(dir, "pics/a/one.txt").writeText("1")
        File(dir, "pics/two.txt").writeText("2")
        val source = PhoneOutputs(dir)
        val entries = source.list().associateBy { it.name }
        assertFalse(entries.getValue("summary.md").isDir)
        val pics = entries.getValue("pics")
        assertTrue(pics.isDir && pics.signature.startsWith("dir:"))
        val zip = tmp.newFile("pics.zip")
        source.fetch(pics, zip)
        ZipFile(zip).use { z -> assertEquals(listOf("pics/a/one.txt", "pics/two.txt"), z.entries().toList().map { it.name }.sorted()) }
        // A change inside the folder changes its signature.
        File(dir, "pics/two.txt").writeText("22")
        assertTrue(source.list().first { it.name == "pics" }.signature != pics.signature)
    }

    /** A sandbox's outputs folder in memory. */
    private class Fake(val files: MutableMap<String, String>) : Outputs.Source {
        override fun list() = files.map { (n, t) -> Outputs.Entry(n, false, t.length.toLong(), "${t.length}:${t.hashCode()}") }
        override fun fetch(entry: Outputs.Entry, to: File) = to.writeText(files.getValue(entry.name))
    }

    private class FailingShelf : OutputShelf {
        override val id = OutputShelf.S3
        override fun put(chatId: String, name: String, file: File) = throw IOException("bucket down")
        override fun open(chatId: String, name: String) = null
        override fun list() = emptyList<OutputShelf.Item>()
        override fun delete(chatId: String, name: String) {}
        override fun deleteAll() {}
    }

    private fun service(store: ChatStore, destination: String, s3: OutputShelf?) = OutputService(
        device = OutputShelf.Device(tmp.newFolder("device")),
        s3 = { s3 },
        destination = { destination },
        staging = tmp.newFolder("staging"),
        delivered = store::outputSignatures,
        record = store::recordOutput,
        reserve = 0,
    )

    @Test
    fun handsOverOncePerVersionOntoThePhone() {
        val store = ChatStore(tmp.newFolder("chats"))
        val id = store.create()
        val svc = service(store, OutputShelf.DEVICE, null)
        val box = Fake(mutableMapOf("report.md" to "v1", "data.csv" to "a,b"))
        assertEquals(listOf("data.csv", "report.md"), svc.collect(id, box).map { it.name })
        assertEquals("v1", svc.device.open(id, "report.md")!!.use { it.readBytes().decodeToString() })
        // Unchanged: nothing again. Changed: only that one, replacing the kept copy.
        assertTrue(svc.collect(id, box).isEmpty())
        box.files["report.md"] = "v2!"
        assertEquals(listOf("report.md"), svc.collect(id, box).map { it.name })
        assertEquals("v2!", svc.device.open(id, "report.md")!!.use { it.readBytes().decodeToString() })
        assertEquals(setOf("data.csv", "report.md"), svc.device.list().map { it.name }.toSet())
        svc.device.delete(id, "data.csv")
        assertNull(svc.device.open(id, "data.csv"))
        svc.device.deleteAll()
        assertTrue(svc.device.list().isEmpty())
    }

    @Test
    fun aBucketThatFailsLeavesTheFileOnThePhone() {
        val store = ChatStore(tmp.newFolder("chats"))
        val id = store.create()
        val svc = service(store, OutputShelf.S3, FailingShelf())
        val handed = svc.collect(id, Fake(mutableMapOf("report.md" to "x")))
        assertEquals(listOf(OutputShelf.DEVICE), handed.map { it.shelf })
        assertNull(handed.single().error)
        assertTrue(svc.device.open(id, "report.md") != null)
    }

    @Test
    fun deliveriesStayWithTheirMessage() {
        val store = ChatStore(tmp.newFolder("chats"))
        val id = store.create()
        store.addSegment(id, ChatStore.Segment("opencode", "opencode:s1", "m"))
        val incoming = org.json.JSONArray()
            .put(JSONObject().put("id", "u1").put("role", "user").put("parts", org.json.JSONArray()))
            .put(JSONObject().put("id", "a1").put("role", "assistant").put("parts", org.json.JSONArray().put(JSONObject().put("kind", "text").put("text", "done"))))
        store.replaceSegmentMessages(id, "opencode:s1", incoming)
        val file = { name: String -> JSONObject().put("name", name).put("size", 3).put("shelf", "device").put("chatId", id) }
        val shown = store.addDelivery(id, "a1", listOf(file("report.md")))!!
        assertEquals("outputs", shown.getJSONArray("parts").getJSONObject(1).getString("kind"))
        store.addDelivery(id, "a1", listOf(file("report.md"), file("data.csv")))
        // The engine's copy is replaced after every turn; the files stay with their message.
        store.replaceSegmentMessages(id, "opencode:s1", JSONObject().put("x", incoming).getJSONArray("x"))
        val m = store.messagesWithOutputs(id).getJSONObject(1)
        val outputs = m.getJSONArray("parts").getJSONObject(1)
        assertEquals(listOf("report.md", "data.csv"), (0 until outputs.getJSONArray("files").length()).map { outputs.getJSONArray("files").getJSONObject(it).getString("name") })
        // The stored copy (handed to an engine on a switch) has no such part.
        assertEquals(1, store.messages(id).getJSONObject(1).getJSONArray("parts").length())
    }

    @Test
    fun namesThatCouldLeaveTheFolderAreRefused() {
        val shelf = OutputShelf.Device(tmp.newFolder("device"))
        val f = tmp.newFile("x")
        for (bad in listOf("../x", "a/b", ".hidden", "")) assertTrue(bad, runCatching { shelf.put(chat, bad, f) }.isFailure)
        assertTrue(runCatching { shelf.put("../c_x", "a.txt", f) }.isFailure)
    }
}
