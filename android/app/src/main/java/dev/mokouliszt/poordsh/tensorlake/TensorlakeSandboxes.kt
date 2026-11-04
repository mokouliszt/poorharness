package dev.mokouliszt.poordsh.tensorlake

import dev.mokouliszt.poordsh.sandbox.CloudSandboxes
import dev.mokouliszt.poordsh.sandbox.RemoteSandbox
import java.io.File
import java.io.InputStream

/** A running Tensorlake sandbox as the app reaches it ([RemoteSandbox]). */
class TensorlakeSandbox(private val api: TensorlakeApi, val sandbox: TensorlakeApi.Sandbox) : RemoteSandbox {
    override val id: String get() = sandbox.id

    override fun run(command: String, args: List<String>, cwd: String?, timeoutSecs: Int): RemoteSandbox.RunResult {
        val r = api.run(sandbox, command, args, cwd = cwd, timeoutSecs = timeoutSecs)
        return RemoteSandbox.RunResult(r.exitCode, r.stdout, r.stderr)
    }

    override fun read(path: String): InputStream? = api.read(sandbox, path)

    override fun write(path: String, bytes: ByteArray) = api.write(sandbox, path, bytes)

    override fun upload(path: String, file: File, progress: ((Long) -> Unit)?) = api.upload(sandbox, path, file, progress)

    override fun listDir(path: String): List<RemoteSandbox.Entry> = try {
        api.listDir(sandbox, path).map { RemoteSandbox.Entry(it.name, it.isDir, it.size, it.modifiedAt) }
    } catch (e: TensorlakeApi.ApiException) {
        if (e.code == 404) emptyList() else throw e
    }

    override fun freeBytes(path: String): Long? = api.freeBytes(sandbox, path)
}

/** [SandboxManager]'s sandboxes by chat id ([SandboxManager.nameFor]). */
class TensorlakeSandboxes(private val api: TensorlakeApi, val manager: SandboxManager) : CloudSandboxes {
    override fun acquire(chatId: String, token: String) = manager.acquire(SandboxManager.nameFor(chatId), token)

    override fun release(chatId: String, token: String) = manager.release(SandboxManager.nameFor(chatId), token)

    override suspend fun ensure(chatId: String): CloudSandboxes.Ready {
        val ready = manager.ensure(SandboxManager.nameFor(chatId))
        return CloudSandboxes.Ready(TensorlakeSandbox(api, ready.sandbox), ready.fresh)
    }

    override suspend fun exists(chatId: String) = manager.exists(SandboxManager.nameFor(chatId))

    override suspend fun delete(chatId: String) = manager.delete(SandboxManager.nameFor(chatId))

    override fun suspendAllNow() = manager.suspendAllNow()
}
