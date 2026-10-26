package dev.mokouliszt.poordsh.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A model list cached by another app version is dropped, so the picker shows what the new version reports. */
class StaleCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache(): File = tmp.newFolder("cache").also {
        File(it, "models-opencode.json").writeText("[]")
        File(it, "other-opencode.json").writeText("{}")
    }

    @Test
    fun aListFromAnotherVersionIsDropped() {
        val dir = cache()
        File(dir, "app-version").writeText("0.14.0")
        EngineManager.dropStaleCatalogs(dir, "0.15.0")
        assertFalse(File(dir, "models-opencode.json").exists())
        assertTrue(File(dir, "other-opencode.json").exists())
        assertEquals("0.15.0", File(dir, "app-version").readText())
    }

    @Test
    fun aListFromAnUnmarkedVersionIsDropped() {
        val dir = cache()
        EngineManager.dropStaleCatalogs(dir, "0.15.0")
        assertFalse(File(dir, "models-opencode.json").exists())
    }

    @Test
    fun theSameVersionKeepsItsList() {
        val dir = cache()
        File(dir, "app-version").writeText("0.15.0")
        EngineManager.dropStaleCatalogs(dir, "0.15.0")
        assertTrue(File(dir, "models-opencode.json").exists())
    }
}
