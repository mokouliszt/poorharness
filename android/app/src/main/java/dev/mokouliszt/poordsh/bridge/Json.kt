package dev.mokouliszt.poordsh.bridge

import org.json.JSONArray
import org.json.JSONObject

/** Small helpers over org.json so call sites stay readable. */

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
    for ((k, v) in pairs) put(k, v.toJsonValue())
}

fun jsonArrayOf(items: Iterable<Any?>): JSONArray = JSONArray().apply {
    for (item in items) put(item.toJsonValue())
}

/**
 * Converts Kotlin values to org.json values. org.json rejects NaN/Infinity with an
 * exception, so non-finite numbers become null instead of failing the whole payload.
 */
fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long -> this
    is Double -> if (isFinite()) this else JSONObject.NULL
    is Float -> if (isFinite()) toDouble() else JSONObject.NULL
    is Number -> toLong()
    is Map<*, *> -> JSONObject().also { obj -> forEach { (k, v) -> obj.put(k.toString(), v.toJsonValue()) } }
    is Iterable<*> -> jsonArrayOf(this)
    is Array<*> -> jsonArrayOf(this.asIterable())
    else -> toString()
}

fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

fun JSONObject.long(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { i ->
    if (isNull(i)) null else optString(i)
}

/** JSON text that is also safe to embed in a JavaScript source string. */
fun JSONObject.toJsLiteral(): String =
    toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

/** Caps long tool output so a single event cannot flood the WebView bridge. */
fun String.clip(max: Int = 16_000): String =
    if (length <= max) this else take(max / 2) + "\n…\n" + takeLast(max / 2)
