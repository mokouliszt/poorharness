package dev.mokouliszt.poordsh.modal

import dev.mokouliszt.poordsh.modal.ModalAgent.ModalException
import dev.mokouliszt.poordsh.phone.PhoneOps
import org.json.JSONArray
import org.json.JSONObject

/**
 * opencode's sandbox tools for a chat in Modal ("modal:<chat>" in the plugin's
 * map), served by the loopback bridge like a device jail's: the same operations
 * and replies as [dev.mokouliszt.poordsh.phone.PhoneAgent], done by [ModalAgent],
 * so the Modal token never reaches opencode.
 */
class ModalOps(private val sandboxes: ModalSandboxes, private val agent: ModalClient) : PhoneOps {
    override fun acquire(name: String, token: String) = sandboxes.acquire(name, token)

    override fun release(name: String, token: String) = sandboxes.release(name, token)

    override suspend fun op(name: String, request: JSONObject): JSONObject = try {
        val sb = sandboxes.sandboxFor(name).id
        when (request.optString("op")) {
            "run" -> {
                val timeout = request.optInt("timeout", 120)
                val r = agent.call(
                    "run",
                    JSONObject().put("sandbox", sb).put("command", request.optString("command"))
                        .put("cwd", request.optString("cwd", ModalSandboxes.WORKSPACE)).put("timeout", timeout),
                    (timeout + 60) * 1000L,
                )
                JSONObject().put("exitCode", r.opt("exitCode")).put("stdout", r.optString("stdout")).put("stderr", r.optString("stderr"))
            }
            "read" -> {
                val r = agent.call("read", JSONObject().put("sandbox", sb).put("path", request.optString("path")))
                if (r.optBoolean("missing")) JSONObject().put("error", "${request.optString("path")}: no such file")
                else JSONObject().put("ok", true).put("dataB64", r.optString("dataB64"))
            }
            "write" -> {
                agent.call("write", JSONObject().put("sandbox", sb).put("path", request.optString("path")).put("dataB64", request.optString("dataB64")))
                JSONObject().put("ok", true)
            }
            "list" -> {
                val entries = agent.call("ls", JSONObject().put("sandbox", sb).put("path", request.optString("path"))).optJSONArray("entries") ?: JSONArray()
                JSONObject().put(
                    "entries",
                    JSONArray((0 until entries.length()).map { entries.getJSONObject(it) }.sortedBy { it.optString("name") }.map {
                        JSONObject().put("name", it.optString("name")).put("is_dir", it.optBoolean("is_dir")).put("size", it.optLong("size"))
                    }),
                )
            }
            else -> JSONObject().put("error", "unknown op")
        }
    } catch (e: ModalException) {
        // Gone, stopped by Modal or refused: check it again on the next call.
        sandboxes.forget(name)
        if (e.kind == "usage") {
            sandboxes.markSpent()
            JSONObject().put("error", SPENT)
        } else {
            JSONObject().put("error", e.message ?: "Modal refused this")
        }
    }

    companion object {
        private const val SPENT =
            "Modal refused this: this month's budget is used up. " +
                "Tell the user to send the message again; PoorHarness then moves this chat to another sandbox."
    }
}
