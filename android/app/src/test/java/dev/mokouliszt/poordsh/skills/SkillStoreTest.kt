package dev.mokouliszt.poordsh.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** What Settings shows of an installed skill: its files, and each one's content. */
class SkillStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(): SkillStore {
        val dir = tmp.newFolder()
        val root = File(dir, "ui-kit").apply { mkdirs() }
        File(root, "SKILL.md").writeText("---\nname: ui-kit\ndescription: Builds UI.\n---\n# UI kit\n")
        File(root, "rules").mkdirs()
        File(root, "rules/forms.md").writeText("日本語のルール\n")
        File(root, "assets").mkdirs()
        File(root, "assets/logo.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 1, 2))
        File(root, "assets/font.bin").writeBytes(byteArrayOf(1, 0, 2, 0))
        File(dir, "secret.txt").writeText("outside")
        return SkillStore(dir)
    }

    @Test
    fun listsEveryFileByPath() {
        val files = store().files("ui-kit")!!
        assertEquals(listOf("SKILL.md", "assets/font.bin", "assets/logo.png", "rules/forms.md"), files.map { it.path })
        assertEquals(4L, files.first { it.path == "assets/font.bin" }.size)
        assertNull(store().files("missing"))
        assertNull(store().files("../x"))
    }

    @Test
    fun readsTextImagesAndKnowsBinaries() {
        val s = store()
        val md = s.read("ui-kit", "rules/forms.md") as SkillStore.Content.Text
        assertEquals("日本語のルール\n", md.text)
        val png = s.read("ui-kit", "assets/logo.png") as SkillStore.Content.Image
        assertEquals("image/png", png.mime)
        assertEquals(7, Base64.getDecoder().decode(png.base64).size)
        assertTrue(s.read("ui-kit", "assets/font.bin") is SkillStore.Content.Binary)
    }

    @Test
    fun longTextIsCutWithoutBreakingACharacter() {
        val s = store()
        // Three-byte characters, so the cut at MAX_TEXT falls inside one.
        File(s.dir, "ui-kit/long.md").writeText("あ".repeat(SkillStore.MAX_TEXT / 3 + 10))
        val text = s.read("ui-kit", "long.md") as SkillStore.Content.Text
        assertTrue(text.truncated)
        assertTrue(text.text.all { it == 'あ' })
    }

    @Test
    fun onlyPathsInsideTheSkillAreRead() {
        val s = store()
        for (bad in listOf("../secret.txt", "/etc/hosts", "rules/../../secret.txt", "", "rules", "nope.md")) {
            try {
                s.read("ui-kit", bad)
                fail(bad)
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            s.read("..", "SKILL.md")
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }
}
