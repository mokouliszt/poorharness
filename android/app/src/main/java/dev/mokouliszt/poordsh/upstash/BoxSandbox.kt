package dev.mokouliszt.poordsh.upstash

import dev.mokouliszt.poordsh.sandbox.RemoteSandbox
import java.io.File
import java.io.InputStream

/** A box as the app reaches it ([RemoteSandbox]). Box wakes a paused box on any of these calls. */
class BoxSandbox(private val api: BoxApi, override val id: String) : RemoteSandbox {
    override fun run(command: String, args: List<String>, cwd: String?, timeoutSecs: Int): RemoteSandbox.RunResult {
        val r = BoxRunner.run(api, id, BoxRunner.join(command, args), cwd ?: "/", timeoutSecs)
        return RemoteSandbox.RunResult(r.exitCode, r.stdout, r.stderr)
    }

    override fun read(path: String): InputStream? = api.read(id, path)

    override fun write(path: String, bytes: ByteArray) = api.write(id, path, bytes)

    override fun upload(path: String, file: File, progress: ((Long) -> Unit)?) = api.upload(id, path, file, progress)

    override fun listDir(path: String): List<RemoteSandbox.Entry> =
        api.listDir(id, path).map { RemoteSandbox.Entry(it.name, it.isDir, if (it.isDir) null else it.size, it.modifiedAt) }
}
