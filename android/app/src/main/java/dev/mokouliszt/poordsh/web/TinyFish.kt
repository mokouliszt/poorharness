package dev.mokouliszt.poordsh.web

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TinyFish's Search and Fetch APIs (docs.tinyfish.ai/search-api, /fetch-api): the
 * fallback when webfetch or websearch cannot be done from the chat's sandbox
 * (docs/adr/web-in-sandbox.md). Called from the app, so the key stays here and
 * never reaches a sandbox. Plain JVM code, tested on the build host.
 *
 * Both return what the model reads, shaped like opencode's own results.
 */
class TinyFish(
    private val apiKey: () -> String?,
    private val http: OkHttpClient = defaultClient(),
    private val searchUrl: String = "https://api.search.tinyfish.ai",
    private val fetchUrl: String = "https://api.fetch.tinyfish.ai",
) {
    /** 401 or 403: the key is not accepted. */
    class AuthException(message: String) : IOException(message)

    /** No key is set. */
    class NoKeyException : IOException("TinyFish: no API key is set in PoorHarness")

    fun search(query: String, numResults: Int = 8): String {
        val url = searchUrl.toHttpUrl().newBuilder().addQueryParameter("query", query).build()
        val body = JSONObject(send(Request.Builder().url(url).get()))
        val results = body.optJSONArray("results") ?: JSONArray()
        if (results.length() == 0) return "No search results found. Please try a different query."
        return (0 until minOf(results.length(), numResults.coerceAtLeast(1))).joinToString("\n---\n") { i ->
            val r = results.getJSONObject(i)
            buildString {
                append("Title: ").append(r.optString("title"))
                append("\nURL: ").append(r.optString("url"))
                r.optString("date").takeIf { it.isNotBlank() }?.let { append("\nPublished: ").append(it) }
                r.optString("site_name").takeIf { it.isNotBlank() }?.let { append("\nSite: ").append(it) }
                append("\nHighlights:\n").append(r.optString("snippet"))
            }
        }
    }

    /** [format] is opencode's: html stays html, text and markdown are both asked for as markdown. */
    fun fetch(url: String, format: String = "markdown"): String {
        val payload = JSONObject().put("urls", JSONArray().put(url)).put("format", if (format == "html") "html" else "markdown")
        val body = JSONObject(send(Request.Builder().url(fetchUrl).post(payload.toString().toRequestBody(JSON))))
        val result = body.optJSONArray("results")?.optJSONObject(0)
        if (result == null) {
            val why = body.optJSONArray("errors")?.optJSONObject(0)?.optString("error")?.takeIf { it.isNotBlank() }
            throw IOException("TinyFish could not fetch the page" + (why?.let { ": $it" } ?: ""))
        }
        return result.optString("text")
    }

    /** Throws [AuthException] when TinyFish does not accept the key. */
    fun check() {
        search("test", 1)
    }

    private fun send(request: Request.Builder): String {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw NoKeyException()
        http.newCall(request.header("X-API-Key", key).build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (res.isSuccessful) return text
            val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
            val message = error?.optString("message")?.takeIf { it.isNotBlank() }
            val code = error?.optString("code")?.takeIf { it.isNotBlank() }
            val what = listOfNotNull(code, message).joinToString(": ").ifBlank { "HTTP ${res.code}" }
            when (res.code) {
                401, 403 -> throw AuthException("TinyFish: $what")
                402 -> throw IOException("TinyFish: the free allowance or credits are used up ($what)")
                429 -> throw IOException("TinyFish: rate limited" + (res.header("Retry-After")?.let { ", retry after ${it}s" } ?: ""))
                else -> throw IOException("TinyFish ${res.code}: $what")
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
