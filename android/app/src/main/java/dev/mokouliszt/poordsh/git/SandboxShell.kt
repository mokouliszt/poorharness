package dev.mokouliszt.poordsh.git

import dev.mokouliszt.poordsh.phone.PhoneOps
import dev.mokouliszt.poordsh.runtime.Proot
import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A chat's sandbox as [GitRepos] uses it: bash scripts, and files in and out. */
interface SandboxShell {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    /** Runs bash [script] with [args] ($1, $2, …). */
    suspend fun run(script: String, args: List<String> = emptyList(), timeoutSecs: Int = 300): Result

    suspend fun upload(local: File, path: String)

    suspend fun download(path: String, local: File)
}

/** A Tensorlake sandbox, through its process and file API. */
class CloudShell(private val api: TensorlakeApi, private val sandbox: TensorlakeApi.Sandbox) : SandboxShell {
    override suspend fun run(script: String, args: List<String>, timeoutSecs: Int): SandboxShell.Result {
        val r = api.run(sandbox, "bash", listOf("-c", script, "repo") + args, timeoutSecs = timeoutSecs)
        return SandboxShell.Result(r.exitCode ?: -1, r.stdout, r.stderr)
    }

    override suspend fun upload(local: File, path: String) = api.upload(sandbox, path, local)

    override suspend fun download(path: String, local: File) {
        val input = api.read(sandbox, path) ?: throw IOException("$path is not in the sandbox")
        local.parentFile?.mkdirs()
        input.use { src -> local.outputStream().use { src.copyTo(it) } }
    }
}

/**
 * A device jail. Its `/tmp` is a folder on the phone ([Proot.Jail.tmp]), so files
 * are copied directly; scripts run through the jail's agent ([PhoneOps]).
 */
class PhoneShell(private val ops: PhoneOps, private val chatId: String, private val jail: Proot.Jail) : SandboxShell {
    override suspend fun run(script: String, args: List<String>, timeoutSecs: Int): SandboxShell.Result {
        val file = File(jail.tmp, "git/.poorharness/run-${System.nanoTime()}.sh")
        file.parentFile?.mkdirs()
        file.writeText(script)
        try {
            val command = (listOf("bash", "/tmp/" + file.relativeTo(jail.tmp).path) + args).joinToString(" ") { quote(it) }
            val r = ops.op(chatId, JSONObject().put("op", "run").put("command", command).put("cwd", "/tmp").put("timeout", timeoutSecs))
            return SandboxShell.Result(r.optInt("exitCode", -1), r.optString("stdout"), r.optString("stderr"))
        } finally {
            file.delete()
        }
    }

    override suspend fun upload(local: File, path: String) {
        val target = hostPath(path)
        target.parentFile?.mkdirs()
        local.copyTo(target, overwrite = true)
    }

    override suspend fun download(path: String, local: File) {
        val source = hostPath(path)
        if (!source.isFile) throw IOException("$path is not in the jail")
        local.parentFile?.mkdirs()
        source.copyTo(local, overwrite = true)
    }

    /** Only paths under the jail's /tmp, which is where [GitRepos] works. */
    private fun hostPath(path: String): File {
        require(path.startsWith("/tmp/") && ".." !in path) { "outside the jail's /tmp: $path" }
        return File(jail.tmp, path.removePrefix("/tmp/"))
    }

    companion object {
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
