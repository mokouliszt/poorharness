package dev.mokouliszt.poordsh.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Accepts what Anthropic's skill-creator packages and refuses what its validator refuses (docs/adr/skills.md). */
class SkillPackageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun md(front: String, body: String = "# Body\n") = "---\n$front\n---\n\n$body"

    private fun extract(bytes: ByteArray) = SkillPackage.extract(ByteArrayInputStream(bytes), tmp.newFolder())

    private fun reason(bytes: ByteArray): String = try {
        extract(bytes)
        fail("accepted")
        ""
    } catch (e: SkillPackage.Invalid) {
        e.reason
    }

    @Test
    fun acceptsAFilePackagedBySkillCreator() {
        // Made with skill-creator's scripts/package_skill.py (folder at the top, evals and caches left out).
        val bytes = javaClass.getResourceAsStream("/skills/csv-tidy.skill")!!.readBytes()
        val parsed = extract(bytes)
        assertEquals("csv-tidy", parsed.name)
        assertTrue(parsed.description.startsWith("Cleans up CSV files"))
        assertTrue(File(parsed.root, "scripts/tidy.py").isFile)
        assertEquals("csv-tidy", parsed.root.name)
    }

    @Test
    fun acceptsSkillMdAtTheTopAndDropsJunk() {
        val parsed = extract(
            zip(
                "SKILL.md" to md("name: top-level\ndescription: Works from the top."),
                "scripts/run.sh" to "echo hi",
                "scripts/__pycache__/x.pyc" to "junk",
                "__MACOSX/._SKILL.md" to "junk",
                "evals/evals.json" to "{}",
                ".DS_Store" to "junk",
            ),
        )
        assertEquals("top-level", parsed.name)
        val files = parsed.root.walkTopDown().filter { it.isFile }.map { it.relativeTo(parsed.root).invariantSeparatorsPath }.toSet()
        assertEquals(setOf("SKILL.md", "scripts/run.sh"), files)
    }

    @Test
    fun foldedDescriptionsAndMetadataParse() {
        val parsed = extract(
            zip(
                "my-skill/SKILL.md" to md(
                    "name: my-skill\ndescription: >\n  A long description\n  over two lines.\nlicense: Apache-2.0\n" +
                        "allowed-tools: Bash Read\ncompatibility: Needs python3\nmetadata:\n  author: someone\n  tags: [a, b]",
                ),
            ),
        )
        assertEquals("A long description over two lines.", parsed.description)
    }

    @Test
    fun windowsLineEndingsAreFine() {
        val parsed = extract(zip("w/SKILL.md" to "---\r\nname: crlf-skill\r\ndescription: Saved on Windows.\r\n---\r\nbody\r\n"))
        assertEquals("crlf-skill", parsed.name)
    }

    @Test
    fun keysOtherAgentsAddAreKept() {
        // Claude Code's own keys, as in a skill made for it (quick_validate.py would refuse them).
        val md = md(
            "name: ui-kit\ndescription: Builds UI.\nuser-invocable: false\ndisable-model-invocation: false\n" +
                "argument-hint: \"[component]\"\nallowed-tools: Bash(npx ui-kit *)\nversion: 2",
        )
        val parsed = extract(zip("ui-kit/SKILL.md" to md))
        assertEquals("ui-kit", parsed.name)
        // The file is kept as it was: opencode reads the keys it knows.
        assertEquals(md, File(parsed.root, "SKILL.md").readText())
    }

    @Test
    fun refusesWhatQuickValidateRefuses() {
        assertEquals("no_frontmatter", reason(zip("s/SKILL.md" to "# no frontmatter")))
        assertEquals("bad_frontmatter", reason(zip("s/SKILL.md" to "---\nname: x\n")))
        assertEquals("no_name", reason(zip("s/SKILL.md" to md("description: d"))))
        assertEquals("no_description", reason(zip("s/SKILL.md" to md("name: s"))))
        assertEquals("bad_description", reason(zip("s/SKILL.md" to md("name: s\ndescription:"))))
        for (bad in listOf("My Skill", "-lead", "trail-", "two--dashes", "a".repeat(65), "snake_case")) {
            assertEquals(bad, "bad_name", reason(zip("s/SKILL.md" to md("name: \"$bad\"\ndescription: d"))))
        }
        assertEquals("bad_description", reason(zip("s/SKILL.md" to md("name: s\ndescription: uses <tags>"))))
        assertEquals("bad_description", reason(zip("s/SKILL.md" to md("name: s\ndescription: ${"x".repeat(1025)}"))))
        assertEquals("bad_compatibility", reason(zip("s/SKILL.md" to md("name: s\ndescription: d\ncompatibility: ${"x".repeat(501)}"))))
        assertEquals("bad_yaml", reason(zip("s/SKILL.md" to md("name: [unclosed\ndescription: d"))))
    }

    @Test
    fun refusesBadLayouts() {
        assertEquals("no_skill_md", reason(zip("a/README.md" to "x", "b/SKILL.md" to md("name: b\ndescription: d"))))
        assertEquals(
            "multiple_skill_md",
            reason(zip("s/SKILL.md" to md("name: s\ndescription: d"), "s/sub/SKILL.md" to md("name: t\ndescription: d"))),
        )
        assertEquals("bad_path", reason(zip("s/SKILL.md" to md("name: s\ndescription: d"), "s/../../evil.sh" to "x")))
        assertEquals("not_zip", reason("not a zip at all".toByteArray()))
    }

    @Test
    fun nestedSkillMdInIgnoredFoldersDoesNotCount() {
        val parsed = extract(
            zip(
                "s/SKILL.md" to md("name: s\ndescription: d"),
                "s/node_modules/pkg/SKILL.md" to "x",
                "s/evals/SKILL.md" to "x",
            ),
        )
        assertFalse(File(parsed.root, "node_modules").exists())
        assertFalse(File(parsed.root, "evals").exists())
    }
}
