package dev.mokouliszt.poordsh.attachments

import dev.mokouliszt.poordsh.attachments.Attachments.Inline
import dev.mokouliszt.poordsh.backend.SentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the model is told about attached files, and which also reach it directly (docs/adr/attachments.md). */
class AttachmentsTest {
    @Test
    fun noteRoundTrips() {
        val files = listOf(
            SentFile("report (final).pdf", "/tmp/workspace/attachments/report (final).pdf", 245_760, "application/pdf"),
            SentFile("photo.jpg", "/tmp/workspace/attachments/photo.jpg", 3_145_728, "image/jpeg", SentFile.Inline("image/jpeg", java.io.File("photo.jpg"))),
        )
        val note = AttachmentNote.build(files)
        assertTrue(note.startsWith(AttachmentNote.OPEN))
        assertTrue(note.contains("/tmp/workspace/attachments/report (final).pdf"))
        val items = AttachmentNote.parse(note)!!
        assertEquals(listOf("report (final).pdf", "photo.jpg"), items.map { it.name })
        assertEquals(listOf(245_760L, 3_145_728L), items.map { it.size })
        assertEquals(listOf(false, true), items.map { it.inline })
        assertEquals("application/pdf", items[0].mime)
        assertNull(AttachmentNote.parse("an ordinary message"))
    }

    @Test
    fun whatGoesToTheModelDirectly() {
        val mb = 1L shl 20
        // Images as data URLs (large ones shrunk), PDFs when the model takes them.
        assertEquals(Inline.AS_IS, Attachments.inlineFor(setOf("image"), "image/png", 2 * mb))
        assertEquals(Inline.SHRINK, Attachments.inlineFor(setOf("image"), "image/jpeg", 9 * mb))
        assertEquals(Inline.NONE, Attachments.inlineFor(emptySet(), "image/png", mb))
        assertEquals(Inline.NONE, Attachments.inlineFor(setOf("image"), "image/heic", mb))
        assertEquals(Inline.AS_IS, Attachments.inlineFor(setOf("pdf"), "application/pdf", 4 * mb))
        assertEquals(Inline.NONE, Attachments.inlineFor(setOf("image"), "application/pdf", 4 * mb))
        assertEquals(Inline.NONE, Attachments.inlineFor(setOf("pdf"), "application/pdf", 40 * mb))
        assertEquals(Inline.NONE, Attachments.inlineFor(setOf("audio", "video"), "audio/mpeg", mb))
    }

    @Test
    fun namesInTheSandbox() {
        assertEquals("a.txt", Attachments.uniqueName("a.txt", emptySet()))
        assertEquals("a (2).txt", Attachments.uniqueName("a.txt", setOf("a.txt")))
        assertEquals("a (3).txt", Attachments.uniqueName("a.txt", setOf("a.txt", "a (2).txt")))
        assertEquals("Makefile (2)", Attachments.uniqueName("Makefile", setOf("Makefile")))
        assertEquals("passwd", Attachments.safeName("../../etc/passwd"))
        assertEquals(".env", Attachments.safeName(".env"))
        assertEquals("file", Attachments.safeName(".."))
        assertEquals("file", Attachments.safeName(null))
        assertEquals("file", Attachments.safeName("\u0000\u0001"))
        assertEquals("image/jpeg", Attachments.cleanMime("Image/JPEG; charset=binary"))
        assertEquals("application/octet-stream", Attachments.cleanMime(null))
    }
}
