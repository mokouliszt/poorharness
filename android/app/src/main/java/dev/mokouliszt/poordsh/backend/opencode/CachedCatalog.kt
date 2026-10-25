package dev.mokouliszt.poordsh.backend.opencode

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A provider's public model list, read at each opencode start and kept in [cache]
 * so a start without a connection still uses the one it had last time. [load] is
 * null when neither is available; each caller decides what that means.
 */
class CachedCatalog<T>(
    private val name: String,
    private val cache: File,
    /** The list from the body; throws on a body that is not the list. */
    private val parse: (String) -> T,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
    /** The body; throws when it cannot be had. */
    private val fetch: () -> String,
    private val describe: (T) -> String = { "read" },
    private val log: (String) -> Unit = {},
) {
    fun load(): T? {
        val fresh = runCatching { parse(fetch()) }
        fresh.onSuccess { value ->
            runCatching {
                cache.parentFile?.mkdirs()
                val tmp = File(cache.path + ".tmp")
                tmp.writeText(encode(value))
                tmp.renameTo(cache)
            }
            log("$name: ${describe(value)}")
            return value
        }
        log("$name list unavailable (${fresh.exceptionOrNull()?.message}); using the last one")
        return runCatching { decode(cache.readText()) }.getOrNull()
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        /** No key is sent: some providers answer a bad one with 401 instead of the list. */
        fun download(url: String): String =
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                check(res.isSuccessful) { "HTTP ${res.code}" }
                res.body?.string().orEmpty()
            }
    }
}
