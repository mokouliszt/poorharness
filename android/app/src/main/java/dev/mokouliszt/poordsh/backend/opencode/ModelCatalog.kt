package dev.mokouliszt.poordsh.backend.opencode

import kotlinx.coroutines.delay
import java.io.File

/**
 * When to read opencode's model list after it starts (verified with 1.19.0).
 *
 * opencode keeps the models.dev catalog in its cache folder and, when that copy
 * is more than five minutes old, fetches a new one in the background at start.
 * A running server keeps the catalog it read first: a fetch that lands later
 * reaches neither `/config/providers` nor `/instance/dispose`, and its
 * `models-dev.refreshed` event is not sent on `/event` or `/global/event`. So the
 * first read waits, a bounded while, for that fetch; a model published since the
 * last start then shows up now rather than one start later. If opencode moves its
 * cache, nothing waits and the old behaviour remains.
 */
object ModelCatalog {
    /** opencode's own freshness rule for its cached copy. */
    const val FRESH_MS = 5 * 60_000L
    /** Its fetch gives up after 10 s; a little more covers the write. */
    const val WAIT_MS = 12_000L
    /** What opencode logs (with --print-logs) when the fetch fails, e.g. offline. */
    const val FETCH_FAILED = "Failed to fetch models.dev"

    /** Newest catalog copy in [cacheDir] ("models.json", or "models-<hash>.json" for another URL). */
    fun newest(cacheDir: File): Long =
        cacheDir.listFiles { f -> f.isFile && f.name.startsWith("models") && f.name.endsWith(".json") }
            ?.maxOfOrNull { it.lastModified() } ?: 0L

    /** Whether opencode, started at [launchedAt], is fetching a new catalog (its copy is missing or old). */
    fun fetching(cacheDir: File, launchedAt: Long): Boolean = launchedAt - newest(cacheDir) >= FRESH_MS

    /**
     * Returns once the catalog in [cacheDir] was rewritten after [launchedAt], the
     * fetch [failed], the server is no longer [alive], or [timeoutMs] passed. True
     * when a new catalog arrived.
     */
    suspend fun awaitFetch(
        cacheDir: File,
        launchedAt: Long,
        failed: () -> Boolean,
        alive: () -> Boolean,
        timeoutMs: Long = WAIT_MS,
    ): Boolean {
        if (!fetching(cacheDir, launchedAt)) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && alive() && !failed()) {
            if (newest(cacheDir) >= launchedAt) return true
            delay(200)
        }
        return newest(cacheDir) >= launchedAt
    }
}
