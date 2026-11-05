package dev.mokouliszt.poordsh.modal

import dev.mokouliszt.poordsh.sandbox.RemoteSandbox
import dev.mokouliszt.poordsh.sandbox.SandboxRunner
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Base64
import java.util.UUID

/**
 * A running Modal sandbox as the app reaches it ([RemoteSandbox]), through
 * [ModalAgent]. Files pass through a folder both the app and the helper see.
 */
class ModalSandbox(private val agent: ModalClient, override val id: String) : RemoteSandbox {
    private fun call(op: String, args: JSONObject, timeoutMs: Long = 20 * 60_000L) =
        runBlocking { agent.call(op, args.put("sandbox", id), timeoutMs) }

    override fun run(command: String, args: List<String>, cwd: String?, timeoutSecs: Int): RemoteSandbox.RunResult {
        val r = call(
            "run",
            JSONObject().put("command", SandboxRunner.join(command, args)).put("cwd", cwd ?: "/").put("timeout", timeoutSecs),
            (timeoutSecs + 60) * 1000L,
        )
        return RemoteSandbox.RunResult(if (r.isNull("exitCode")) null else r.optInt("exitCode"), r.optString("stdout"), r.optString("stderr"))
    }

    override fun read(path: String): InputStream? {
        val (host, guest) = agent.ioFile(UUID.randomUUID().toString())
        host.parentFile?.mkdirs()
        val r = call("read", JSONObject().put("path", path).put("to", guest))
        if (r.optBoolean("missing")) return null
        // The copy is the caller's to read; it goes when the stream is closed.
        return object : FileInputStream(host) {
            override fun close() {
                super.close()
                host.delete()
            }
        }
    }

    override fun write(path: String, bytes: ByteArray) {
        call("write", JSONObject().put("path", path).put("dataB64", Base64.getEncoder().encodeToString(bytes)))
    }

    override fun upload(path: String, file: File, progress: ((Long) -> Unit)?) {
        val (host, guest) = agent.ioFile(UUID.randomUUID().toString())
        host.parentFile?.mkdirs()
        try {
            file.copyTo(host, overwrite = true)
            call("write", JSONObject().put("path", path).put("from", guest))
            progress?.invoke(file.length())
        } finally {
            host.delete()
        }
    }

    override fun listDir(path: String): List<RemoteSandbox.Entry> {
        val a = call("ls", JSONObject().put("path", path)).optJSONArray("entries") ?: return emptyList()
        return (0 until a.length()).map {
            val e = a.getJSONObject(it)
            RemoteSandbox.Entry(
                e.getString("name"), e.optBoolean("is_dir"),
                if (e.optBoolean("is_dir")) null else e.optLong("size"),
                if (e.has("modifiedAt")) e.optLong("modifiedAt") else null,
            )
        }
    }
}
