package dev.mokouliszt.poordsh.upstash

import dev.mokouliszt.poordsh.phone.PhoneOps
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * opencode's sandbox tools for a chat in Upstash Box ("box:<chat>" in the
 * plugin's map), served by the loopback bridge like a device jail's: the same
 * operations and the same replies as [dev.mokouliszt.poordsh.phone.PhoneAgent],
 * done through Box's API from the app, so the Box key never reaches opencode.
 */
class BoxOps(private val boxes: BoxSandboxes, private val api: BoxApi) : PhoneOps {
    override fun acquire(name: String, token: String) = boxes.acquire(name, token)

    override fun release(name: String, token: String) = boxes.release(name, token)

    override suspend fun op(name: String, request: JSONObject): JSONObject {
        val box = try {
            boxes.sandboxFor(name).id
        } catch (e: BoxApi.UsageLimit) {
            return JSONObject().put("error", SPENT)
        }
        return try {
            when (request.optString("op")) {
                "run" -> {
                    val r = BoxRunner.run(
                        api, box, request.optString("command"), request.optString("cwd", BoxSandboxes.WORKSPACE),
                        request.optInt("timeout", 120),
                    )
                    JSONObject().put("exitCode", r.exitCode).put("stdout", r.stdout).put("stderr", r.stderr)
                }
                "read" -> {
                    val path = request.optString("path")
                    val bytes = api.read(box, path)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_READ) return JSONObject().put("error", "$path is too large to read (over $MAX_READ bytes)")
                        }
                        out.toByteArray()
                    } ?: return JSONObject().put("error", "$path: no such file")
                    JSONObject().put("ok", true).put("dataB64", Base64.getEncoder().encodeToString(bytes))
                }
                "write" -> {
                    val data = Base64.getDecoder().decode(request.optString("dataB64"))
                    api.write(box, request.optString("path"), data)
                    JSONObject().put("ok", true).put("bytes", data.size)
                }
                "list" -> JSONObject().put(
                    "entries",
                    JSONArray(
                        api.listDir(box, request.optString("path")).sortedBy { it.name }.map {
                            JSONObject().put("name", it.name).put("is_dir", it.isDir).put("size", it.size)
                        },
                    ),
                )
                else -> JSONObject().put("error", "unknown op")
            }
        } catch (e: BoxApi.UsageLimit) {
            boxes.markSpent()
            boxes.forget(name)
            JSONObject().put("error", SPENT)
        } catch (e: BoxApi.ApiException) {
            // Gone or broken under us: check it again on the next call.
            boxes.forget(name)
            throw e
        }
    }

    companion object {
        private const val MAX_READ = 32 shl 20
        private const val SPENT =
            "Upstash Box refused this: its free CPU for the month is used up. " +
                "Tell the user to send the message again; PoorHarness then moves this chat to another sandbox."
    }
}
