package dev.mokouliszt.poordsh.runtime

import android.content.Context
import java.io.File

/**
 * Where the runtime lives on the device.
 *
 * The rootfs image is replaceable: a new app version can swap it wholesale.
 * Everything the user creates lives outside it and is bind-mounted in:
 *   home  -> /root               (opencode data and config)
 *   tools -> /opt/poordsh/tools   (opencode versions installed by in-app updates)
 *   tmp   -> /tmp
 */
class RuntimePaths(context: Context) {
    val base = File(context.filesDir, "runtime")
    val rootfs = File(base, "rootfs")
    val staging = File(base, "rootfs.staging")
    val retired = File(base, "rootfs.old")
    val marker = File(base, "rootfs.id")
    val home = File(base, "home")
    val tools = File(base, "tools")
    val tmp = File(base, "tmp")
    val fakeProc = File(base, "fakeproc")

    /** Per-chat on-device jails (one folder per chat) used when there is no Tensorlake key. */
    val sandboxes = File(context.filesDir, "sandboxes")

    /** The user's skills (one folder each); bound for opencode at SkillSync.ROOT. */
    val skills = File(context.filesDir, "skills")

    /** The user's plugins (one folder each); bound for opencode at PluginStore.ROOT. */
    val plugins = File(context.filesDir, "plugins")

    /** Files picked for a message, kept here until it is sent. */
    val attachments = File(context.filesDir, "attachments")

    /** PRoot's own scratch space; must be on a filesystem that allows sockets/fifos. */
    val prootTmp = File(context.cacheDir, "proot")

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val prootBinary = File(nativeDir, "libproot.so")
    val prootLoader = File(nativeDir, "libproot-loader.so")

    /**
     * opencode's directory on the phone. Agents work in the chat's Tensorlake
     * sandbox, so this stays empty: nothing on the phone becomes project context.
     */
    val emptyGuest = "/root/.poordsh/empty"
    val emptyHost = File(home, ".poordsh/empty")

    /** Maps a path inside the guest to the host file that backs it. */
    fun hostPathOf(guestPath: String): File = when {
        guestPath == "/root" || guestPath.startsWith("/root/") -> File(home, guestPath.removePrefix("/root"))
        guestPath.startsWith("/opt/poordsh/tools/") -> File(tools, guestPath.removePrefix("/opt/poordsh/tools/"))
        guestPath == "/tmp" || guestPath.startsWith("/tmp/") -> File(tmp, guestPath.removePrefix("/tmp"))
        else -> File(rootfs, guestPath.removePrefix("/"))
    }

    fun ensureDirs() {
        listOf(base, home, tools, tmp, fakeProc, prootTmp, emptyHost).forEach { it.mkdirs() }
    }
}
