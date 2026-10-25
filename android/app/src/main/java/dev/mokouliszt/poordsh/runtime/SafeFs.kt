package dev.mokouliszt.poordsh.runtime

import android.system.Os
import android.system.OsConstants
import java.io.File

/** File operations that never follow symlinks (the rootfs is full of them). */
object SafeFs {

    fun isSymlink(f: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(f.path).st_mode)
    } catch (_: Exception) {
        false
    }

    private fun isRealDirectory(f: File): Boolean = try {
        OsConstants.S_ISDIR(Os.lstat(f.path).st_mode)
    } catch (_: Exception) {
        false
    }

    /** Deletes [root] and everything below it; symlinks are removed, never followed. */
    fun deleteTree(root: File) {
        if (!root.exists() && !isSymlink(root)) return
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (f, visited) = stack.removeLast()
            if (isRealDirectory(f)) {
                if (!visited) {
                    // Make sure we may list and modify it.
                    try { Os.chmod(f.path, 0b111_000_000) } catch (_: Exception) { }
                    stack.addLast(f to true)
                    f.listFiles()?.forEach { stack.addLast(it to false) }
                } else {
                    f.delete()
                }
            } else {
                f.delete()
            }
        }
    }

    fun freeBytes(dir: File): Long = try {
        android.os.StatFs(dir.path).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }
}
