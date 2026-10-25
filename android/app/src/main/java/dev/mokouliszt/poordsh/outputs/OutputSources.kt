package dev.mokouliszt.poordsh.outputs

import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reads a chat's outputs folder in a device jail, whose files are on the phone. */
class PhoneOutputs(private val dir: File) : Outputs.Source {
    override fun list(): List<Outputs.Entry> = dir.listFiles().orEmpty().mapNotNull { f ->
        when {
            f.isFile -> Outputs.Entry(f.name, false, f.length(), Outputs.fileSignature(f.length(), f.lastModified()))
            f.isDirectory -> Outputs.Entry(f.name, true, 0, folderSignature(f))
            else -> null
        }
    }

    override fun fetch(entry: Outputs.Entry, to: File) {
        val f = File(dir, entry.name)
        if (!entry.isDir) {
            Outputs.copyLimited(f.inputStream(), to, Long.MAX_VALUE)
            return
        }
        ZipOutputStream(to.outputStream().buffered()).use { zip ->
            f.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry("${entry.name}/${file.relativeTo(f).invariantSeparatorsPath}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun folderSignature(d: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        d.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
            md.update("${it.relativeTo(d).invariantSeparatorsPath}\u0000${it.length()}\u0000${it.lastModified()}\n".toByteArray())
        }
        return "dir:" + md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Reads a chat's outputs folder in its Tensorlake sandbox: files through the file
 * API; a folder is signed and zipped in the sandbox (python3, as the Files screen's
 * zip does) and the zip fetched.
 */
class CloudOutputs(private val api: TensorlakeApi, private val sb: TensorlakeApi.Sandbox) : Outputs.Source {
    override fun list(): List<Outputs.Entry> {
        val entries = try {
            api.listDir(sb, Outputs.DIR)
        } catch (e: TensorlakeApi.ApiException) {
            if (e.code == 404) return emptyList() else throw e
        }
        val dirs = entries.filter { it.isDir }.map { it.name }
        val sigs = if (dirs.isEmpty()) emptyMap() else folderSignatures(dirs)
        return entries.mapNotNull { e ->
            if (e.isDir) sigs[e.name]?.let { Outputs.Entry(e.name, true, 0, it) }
            else Outputs.Entry(e.name, false, e.size ?: 0, Outputs.fileSignature(e.size ?: 0, e.modifiedAt ?: 0))
        }
    }

    override fun fetch(entry: Outputs.Entry, to: File) {
        if (!entry.isDir) {
            val input = api.read(sb, "${Outputs.DIR}/${entry.name}") ?: throw IOException("output_missing")
            Outputs.copyLimited(input, to, Long.MAX_VALUE)
            return
        }
        val zip = "/tmp/poordsh-outputs/${UUID.randomUUID()}.zip"
        try {
            val r = api.run(sb, "python3", listOf("-c", SCRIPT, "zip", "${Outputs.DIR}/${entry.name}", zip), timeoutSecs = 600)
            if (r.exitCode != 0) throw IOException("could not zip ${entry.name}: ${r.stderr.trim().takeLast(200)}")
            val input = api.read(sb, zip) ?: throw IOException("output_missing")
            Outputs.copyLimited(input, to, Long.MAX_VALUE)
        } finally {
            runCatching { api.run(sb, "rm", listOf("-f", "--", zip)) }
        }
    }

    private fun folderSignatures(names: List<String>): Map<String, String> {
        val r = api.run(sb, "python3", listOf("-c", SCRIPT, "sig", Outputs.DIR) + names, timeoutSecs = 120)
        if (r.exitCode != 0) throw IOException("could not read the outputs folder: ${r.stderr.trim().takeLast(200)}")
        return r.stdout.lines().filter { '\t' in it }.associate { it.substringBefore('\t') to "dir:" + it.substringAfter('\t') }
    }

    companion object {
        /** `sig <outputs> <name>…`: a digest per folder (paths, sizes, times); `zip <folder> <out>`: the folder as one zip. */
        private val SCRIPT = """
            import hashlib, os, sys, zipfile
            def files(d):
                for root, dirs, names in os.walk(d):
                    dirs.sort()
                    for n in sorted(names):
                        p = os.path.join(root, n)
                        if os.path.isfile(p) and not os.path.islink(p):
                            yield p
            if sys.argv[1] == "sig":
                for name in sys.argv[3:]:
                    d = os.path.join(sys.argv[2], name)
                    h = hashlib.sha256()
                    for p in files(d):
                        st = os.stat(p)
                        h.update(("%s\0%d\0%d\n" % (os.path.relpath(p, d), st.st_size, int(st.st_mtime * 1000))).encode())
                    print(name + "\t" + h.hexdigest())
            else:
                d, out = sys.argv[2], sys.argv[3]
                os.makedirs(os.path.dirname(out), exist_ok=True)
                top = os.path.basename(d.rstrip("/"))
                with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
                    for p in files(d):
                        z.write(p, os.path.join(top, os.path.relpath(p, d)))
        """.trimIndent()
    }
}
