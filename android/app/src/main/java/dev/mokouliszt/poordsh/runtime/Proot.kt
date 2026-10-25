package dev.mokouliszt.poordsh.runtime

import android.system.Os
import dev.mokouliszt.poordsh.plugins.PluginStore
import dev.mokouliszt.poordsh.skills.SkillSync
import java.io.File
import java.util.UUID

/**
 * Builds the PRoot command line that runs a program inside the Ubuntu rootfs.
 *
 * The runner and its loader are exec'd from nativeLibraryDir (the only
 * app-owned place Android 10+ allows exec from); PRoot then maps guest
 * binaries itself, so nothing inside the rootfs needs exec permission from
 * SELinux. Secrets never appear on the command line: they are written to a
 * 0600 file under /tmp that the guest shell sources and deletes before exec.
 */
class Proot(private val paths: RuntimePaths, private val guestEnv: GuestEnvironment) {

    data class Spec(
        val command: List<String>,
        val workdir: String = "/root",
        val env: Map<String, String> = emptyMap(),
        val secrets: Map<String, String> = emptyMap(),
        val disableSeccomp: Boolean = false,
        /** When set, run in a per-chat jail (own home/tmp/workspace) instead of the app's home. */
        val jail: Jail? = null,
    )

    /**
     * A chat's on-device sandbox: its own home, tmp and workspace bound into a
     * clean copy of the OS image, with none of the app's home or secrets in
     * reach. The OS image itself is shared and writable (like a container's base
     * layer); damage to it is undone by "Reinstall Ubuntu".
     */
    data class Jail(val home: File, val tmp: File, val workspace: File)

    fun processBuilder(spec: Spec): ProcessBuilder {
        val argv = mutableListOf(
            paths.prootBinary.path,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-0",
            "-r", paths.rootfs.path,
            "-w", spec.workdir,
        )
        for ((src, dst) in binds(spec)) {
            argv += "-b"
            argv += if (src == dst) src else "$src:$dst"
        }
        argv += listOf("/usr/bin/env", "-i")
        val vars = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "TMPDIR" to "/tmp",
            "PATH" to GUEST_PATH,
        )
        vars.putAll(spec.env)
        vars.forEach { (k, v) -> argv += "$k=$v" }

        if (spec.secrets.isEmpty()) {
            argv += spec.command
        } else {
            val name = ".env-" + UUID.randomUUID().toString()
            // Lands at /tmp/$name in the guest; for a jail /tmp is the jail's own tmp.
            val hostFile = File(spec.jail?.tmp ?: paths.tmp, name)
            hostFile.writeText(spec.secrets.entries.joinToString("\n", postfix = "\n") { (k, v) ->
                "$k=${shellQuote(v)}"
            })
            try { Os.chmod(hostFile.path, 0b110_000_000) } catch (_: Exception) { }
            argv += listOf("/bin/sh", "-c", SOURCE_AND_EXEC, "/tmp/$name")
            argv += spec.command
        }

        return ProcessBuilder(argv).apply {
            directory(paths.base)
            val env = environment()
            env.clear()
            env["PATH"] = "/system/bin:/system/xbin"
            env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
            env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
            env["HOME"] = paths.base.path
            env["TMPDIR"] = paths.prootTmp.path
            env["PROOT_TMP_DIR"] = paths.prootTmp.path
            env["PROOT_LOADER"] = paths.prootLoader.path
            if (spec.disableSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        }
    }

    private fun binds(spec: Spec): List<Pair<String, String>> {
        val common = listOf(
            "/dev" to "/dev",
            "/proc" to "/proc",
            "/sys" to "/sys",
            "/dev/urandom" to "/dev/random",
            "/proc/self/fd" to "/dev/fd",
            "/proc/self/fd/0" to "/dev/stdin",
            "/proc/self/fd/1" to "/dev/stdout",
            "/proc/self/fd/2" to "/dev/stderr",
            paths.tools.path to "/opt/poordsh/tools",
        )
        val jail = spec.jail
        val home = if (jail != null) {
            // A chat's own home/tmp/workspace; no app home, so nothing of the phone leaks in.
            val shm = File(jail.tmp, ".shm").apply { mkdirs() }
            listOf(
                jail.home.path to "/root",
                jail.tmp.path to "/tmp",
                jail.workspace.path to "/tmp/workspace",
                shm.path to "/dev/shm",
            )
        } else {
            val shm = File(paths.tmp, ".shm").apply { mkdirs() }
            // The user's skills and plugins, where opencode's config looks for them (read in place).
            for ((store, at) in listOf(paths.skills to SkillSync.ROOT, paths.plugins to PluginStore.ROOT)) {
                store.mkdirs()
                File(paths.tmp, at.removePrefix("/tmp/")).mkdirs()
            }
            listOf(
                paths.home.path to "/root",
                paths.tmp.path to "/tmp",
                shm.path to "/dev/shm",
                paths.skills.path to SkillSync.ROOT,
                paths.plugins.path to PluginStore.ROOT,
            )
        }
        return common + home + guestEnv.fakeProcBinds()
    }

    companion object {
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val SOURCE_AND_EXEC = "set -a; . \"\$0\"; set +a; rm -f \"\$0\"; exec \"\$@\""

        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
