package dev.mokouliszt.poordsh.runtime

import java.io.File

/**
 * Locates the opencode binary to run on the phone.
 *
 * The APK ships one version inside the rootfs image
 * (/opt/poordsh/<tool>/<version>/). In-app updates install newer versions
 * under [RuntimePaths.tools] (/opt/poordsh/tools/<tool>/<version>/ in the guest),
 * which survive image replacement. The newest available version wins.
 */
class Tools(private val paths: RuntimePaths, private val bundled: () -> Map<String, String>) {

    enum class Tool(val id: String, val binary: String) {
        OPENCODE("opencode", "opencode")
    }

    data class Located(val version: String, val guestPath: String, val bundled: Boolean)

    fun locate(tool: Tool): Located {
        val candidates = mutableListOf<Located>()
        bundled()[tool.id]?.let { v ->
            candidates += Located(v, "/opt/poordsh/${tool.id}/$v/${tool.binary}", true)
        }
        File(paths.tools, tool.id).listFiles()?.forEach { dir ->
            if (File(dir, tool.binary).isFile && File(dir, ".complete").isFile) {
                candidates += Located(dir.name, "/opt/poordsh/tools/${tool.id}/${dir.name}/${tool.binary}", false)
            }
        }
        return candidates.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
            ?: throw IllegalStateException("${tool.id} is missing from the runtime")
    }

    /** Removes in-app updates so the bundled version is used again. */
    fun resetToBundled(tool: Tool) {
        SafeFs.deleteTree(File(paths.tools, tool.id))
    }

    companion object {
        /** Numeric-aware comparison: 1.10.0 > 1.9.3, 0.162.1 > 0.162.0. */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-', '+').map { it.toIntOrNull() ?: -1 }
            val pb = b.split('.', '-', '+').map { it.toIntOrNull() ?: -1 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }
}
