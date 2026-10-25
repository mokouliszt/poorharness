package dev.mokouliszt.poordsh.storage

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * An S3-compatible bucket the user connects (docs/adr/s3-storage.md): AWS S3,
 * Cloudflare R2, Backblaze B2, MinIO and the like, through the S3 API with
 * Signature V4 and path-style URLs, which all of them accept.
 *
 * The app keeps everything it writes under [Config.prefix] and splits that into
 * one folder per use ([space]); a use never reads or deletes outside its own.
 * Plain JVM code, tested on the build host.
 */
class S3Storage(val config: Config, private val clock: () -> Long = System::currentTimeMillis) {

    data class Config(
        /** `https://s3.us-east-1.amazonaws.com` and the like. */
        val endpoint: String,
        val bucket: String,
        val accessKeyId: String,
        val secretAccessKey: String,
        /** Blank: read from the endpoint (`s3.<region>.…`), else `us-east-1`. */
        val region: String = "",
        /** Folder in the bucket for everything this app writes; blank for the bucket's top. */
        val prefix: String = "",
    ) {
        val signingRegion: String get() = region.trim().ifEmpty { regionOf(endpoint) }

        /** The prefix as a key start: "" or "a/b/". */
        val base: String get() = prefix.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }

        fun toJson(): JSONObject = JSONObject().put("endpoint", endpoint).put("bucket", bucket).put("accessKeyId", accessKeyId)
            .put("secretAccessKey", secretAccessKey).put("region", region).put("prefix", prefix)

        /** Without the secret, for the UI. */
        fun describe(): JSONObject = JSONObject().put("endpoint", endpoint).put("bucket", bucket).put("accessKeyId", accessKeyId)
            .put("region", region).put("prefix", prefix)

        companion object {
            fun fromJson(o: JSONObject) = Config(
                endpoint = o.getString("endpoint"), bucket = o.getString("bucket"), accessKeyId = o.getString("accessKeyId"),
                secretAccessKey = o.getString("secretAccessKey"), region = o.optString("region"), prefix = o.optString("prefix"),
            )

            fun regionOf(endpoint: String): String {
                val host = runCatching { endpoint.toHttpUrl().host }.getOrNull() ?: return DEFAULT_REGION
                val labels = host.split('.')
                // s3.eu-central-1.amazonaws.com, s3.dualstack.eu-central-1.amazonaws.com,
                // s3.us-east-005.backblazeb2.com, s3.us-west-1.wasabisys.com
                if (labels.size < 4 || labels[0] != "s3") return DEFAULT_REGION
                return if (labels[1] == "dualstack" && labels.size >= 5) labels[2] else labels[1]
            }
        }
    }

    data class Item(val key: String, val size: Long, val modifiedAt: Long)

    /** [status] is the HTTP status; [code] is S3's error code when it sent one. */
    class S3Exception(val status: Int, val code: String?, message: String) : IOException(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val bucketUrl: HttpUrl = config.endpoint.trim().trimEnd('/').toHttpUrl().newBuilder()
        .addEncodedPathSegment(encode(config.bucket.trim())).build()

    /** The folder of one use: keys under `<prefix>/<name>/`. */
    fun space(name: String): Space {
        require(SPACE.matches(name)) { "bad space name" }
        return Space(this, config.base + name + "/")
    }

    fun put(key: String, file: File, contentType: String? = null) {
        val type = (contentType ?: URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream").toMediaType()
        send("PUT", key, body = file.asRequestBody(type), payloadHash = sha256Hex(file)).close()
    }

    fun put(key: String, bytes: ByteArray) {
        send("PUT", key, body = bytes.toRequestBody("application/octet-stream".toMediaType()), payloadHash = sha256Hex(bytes)).close()
    }

    /** The object's content, or null when there is no such key. The caller closes it. */
    fun get(key: String): InputStream? {
        val res = send("GET", key, allow404 = true)
        if (res.code == 404) {
            res.close()
            return null
        }
        return res.body?.byteStream() ?: run { res.close(); null }
    }

    fun delete(key: String) {
        send("DELETE", key, allow404 = true).close()
    }

    /** Every object under [prefix] (a full key prefix). */
    fun list(prefix: String): List<Item> {
        val out = mutableListOf<Item>()
        var token: String? = null
        do {
            val query = sortedMapOf("list-type" to "2", "prefix" to prefix).apply { token?.let { put("continuation-token", it) } }
            val doc = send("GET", null, query = query).use { res -> parse(res) }
            val contents = doc.getElementsByTagName("Contents")
            for (i in 0 until contents.length) {
                val c = contents.item(i) as Element
                out += Item(text(c, "Key") ?: continue, text(c, "Size")?.toLongOrNull() ?: 0, parseTime(text(c, "LastModified")))
            }
            token = if (text(doc.documentElement, "IsTruncated") == "true") text(doc.documentElement, "NextContinuationToken") else null
        } while (token != null)
        return out
    }

    /** Deletes [keys], up to 1000 a request (DeleteObjects). */
    fun deleteAll(keys: List<String>) {
        for (chunk in keys.chunked(1000)) {
            val xml = buildString {
                append("<Delete><Quiet>true</Quiet>")
                chunk.forEach { append("<Object><Key>").append(escapeXml(it)).append("</Key></Object>") }
                append("</Delete>")
            }.toByteArray()
            val md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(xml))
            val res = send(
                "POST", null, query = sortedMapOf("delete" to ""),
                body = xml.toRequestBody("application/xml".toMediaType()), payloadHash = sha256Hex(xml),
                extra = mapOf("content-md5" to md5),
            )
            val doc = res.use { parse(it) }
            val errors = doc.getElementsByTagName("Error")
            if (errors.length > 0) {
                val e = errors.item(0) as Element
                throw S3Exception(200, text(e, "Code"), "could not delete ${text(e, "Key")}: ${text(e, "Message")}")
            }
        }
    }

    /** Writes, reads back and deletes one small object in the app's folder: the permissions every use needs. */
    fun check() {
        val key = config.base + CHECK_KEY
        val stamp = clock().toString().toByteArray()
        put(key, stamp)
        val back = get(key)?.use { it.readBytes() } ?: throw S3Exception(404, "NoSuchKey", "the test object could not be read back")
        if (!back.contentEquals(stamp)) throw S3Exception(200, null, "the test object came back different")
        delete(key)
    }

    // --- Signature V4 ----------------------------------------------------------

    private fun send(
        method: String,
        key: String?,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        payloadHash: String = EMPTY_SHA256,
        extra: Map<String, String> = emptyMap(),
        allow404: Boolean = false,
    ): Response {
        // Encoded here, the S3 way, so the path and query sent are the ones signed.
        val url = bucketUrl.newBuilder().apply {
            key?.split('/')?.forEach { addEncodedPathSegment(encode(it)) }
            query.forEach { (k, v) -> addEncodedQueryParameter(encode(k), encode(v)) }
        }.build()
        val now = Date(clock())
        val amzDate = format("yyyyMMdd'T'HHmmss'Z'", now)
        val day = amzDate.take(8)
        val hostHeader = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
        val headers = sortedMapOf(
            "host" to hostHeader,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate,
        ).apply { extra.forEach { (k, v) -> put(k.lowercase(), v) } }
        val signedHeaders = headers.keys.joinToString(";")
        val canonical = listOf(
            method,
            url.encodedPath,
            query.toSortedMap().entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" },
            headers.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" },
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
        val region = config.signingRegion
        val scope = "$day/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        var k = hmac(("AWS4" + config.secretAccessKey).toByteArray(), day)
        k = hmac(k, region)
        k = hmac(k, "s3")
        k = hmac(k, "aws4_request")
        val signature = hmac(k, toSign).joinToString("") { "%02x".format(it) }
        val request = Request.Builder().url(url).method(method, body ?: if (method == "PUT" || method == "POST") ByteArray(0).toRequestBody() else null)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=${config.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
            .apply { extra.forEach { (k2, v) -> header(k2, v) } }
            .build()
        val res = http.newCall(request).execute()
        if (res.isSuccessful || (allow404 && res.code == 404)) return res
        val text = res.use { it.body?.string().orEmpty() }
        val code = Regex("<Code>([^<]*)</Code>").find(text)?.groupValues?.get(1)
        val message = Regex("<Message>([^<]*)</Message>").find(text)?.groupValues?.get(1)
        throw S3Exception(res.code, code, "S3 ${res.code}${code?.let { " $it" }.orEmpty()}${message?.let { ": $it" }.orEmpty()}")
    }

    private fun parse(res: Response) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
        .parse(res.body?.byteStream() ?: throw IOException("empty S3 response"))

    private fun text(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        return if (nodes.length == 0) null else nodes.item(0).textContent
    }

    companion object {
        /**
         * The folders under the app's prefix, one per use. A new use takes a new name
         * here and never reads or deletes in another's.
         */
        const val OUTPUTS = "outputs"
        /** Git repositories for agents (docs/adr/git-repos.md). */
        const val GIT = "git"

        const val DEFAULT_REGION = "us-east-1"
        /** Written and deleted by [check]; never left behind. */
        const val CHECK_KEY = ".poorharness-check"
        /** A use's folder name. */
        private val SPACE = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        /** RFC 3986 unreserved characters stay; everything else is %XX of its UTF-8 bytes. */
        fun encode(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) {
                    sb.append(c.toChar())
                } else {
                    sb.append('%').append("%02X".format(c))
                }
            }
            return sb.toString()
        }

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

        private fun format(pattern: String, date: Date): String =
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

        private fun parseTime(s: String?): Long {
            if (s == null) return 0
            for (p in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
                runCatching { return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time }
            }
            return 0
        }

        private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    /** One use's folder in the bucket; keys passed in and out are relative to it. */
    class Space(private val s3: S3Storage, val base: String) {
        fun put(rel: String, file: File) = s3.put(base + rel, file)
        fun put(rel: String, bytes: ByteArray) = s3.put(base + rel, bytes)
        fun get(rel: String): InputStream? = s3.get(base + rel)
        fun delete(rel: String) = s3.delete(base + rel)
        fun list(sub: String = ""): List<Item> = s3.list(base + sub).map { it.copy(key = it.key.removePrefix(base)) }
        fun deleteAll(sub: String = "") = s3.deleteAll(s3.list(base + sub).map { it.key })
    }
}
