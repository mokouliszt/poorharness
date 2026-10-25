package dev.mokouliszt.poordsh.backend.opencode

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The first model-list read waits for opencode's catalog fetch, and only when one is under way. */
class ModelCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun aFreshCopyMeansNoWait() = runBlocking {
        val dir = tmp.newFolder()
        File(dir, "models.json").writeText("{}")
        val now = System.currentTimeMillis()
        assertFalse(ModelCatalog.fetching(dir, now))
        val t = System.currentTimeMillis()
        assertFalse(ModelCatalog.awaitFetch(dir, now, failed = { false }, alive = { true }))
        assertTrue(System.currentTimeMillis() - t < 100)
    }

    @Test
    fun anOldCopyWaitsForTheRewrite() = runBlocking {
        val dir = tmp.newFolder()
        val cache = File(dir, "models.json").apply { writeText("{}"); setLastModified(System.currentTimeMillis() - 3_600_000) }
        val launched = System.currentTimeMillis()
        assertTrue(ModelCatalog.fetching(dir, launched))
        val waiting = async { ModelCatalog.awaitFetch(dir, launched, failed = { false }, alive = { true }, timeoutMs = 5_000) }
        delay(600)
        // opencode writes a temp file and renames it over the copy.
        File(dir, "models.json.123.tmp").apply { writeText("{\"new\":1}"); renameTo(cache) }
        assertTrue(waiting.await())
    }

    @Test
    fun noCopyAtAllAlsoWaits() {
        assertTrue(ModelCatalog.fetching(tmp.newFolder(), System.currentTimeMillis()))
    }

    @Test
    fun aFailedFetchOrADeadServerEndsTheWait() = runBlocking {
        val dir = tmp.newFolder()
        File(dir, "models.json").apply { writeText("{}"); setLastModified(System.currentTimeMillis() - 3_600_000) }
        val launched = System.currentTimeMillis()
        var t = System.currentTimeMillis()
        assertFalse(ModelCatalog.awaitFetch(dir, launched, failed = { true }, alive = { true }, timeoutMs = 5_000))
        assertTrue(System.currentTimeMillis() - t < 500)
        t = System.currentTimeMillis()
        assertFalse(ModelCatalog.awaitFetch(dir, launched, failed = { false }, alive = { false }, timeoutMs = 5_000))
        assertTrue(System.currentTimeMillis() - t < 500)
        t = System.currentTimeMillis()
        assertFalse(ModelCatalog.awaitFetch(dir, launched, failed = { false }, alive = { true }, timeoutMs = 700))
        assertTrue(System.currentTimeMillis() - t in 600..2_000)
    }
}
