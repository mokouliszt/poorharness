package dev.mokouliszt.poordsh.git

import dev.mokouliszt.poordsh.storage.S3Storage
import java.io.File
import java.io.InputStream

/**
 * Git repositories in the user's bucket (docs/adr/git-repos.md), laid out as
 * git-remote-s3 (awslabs/git-remote-s3) lays out a remote, so the same
 * repositories can be cloned and pushed from a PC with it:
 *
 * - `<repo>/HEAD`: the default branch, as a ref name ("refs/heads/main");
 * - `<repo>/<ref>/<sha>.bundle`: one bundle per branch or tag, made with
 *   `git bundle create <file> <ref>` (the ref's whole history);
 * - `<repo>/<ref>/LOCK#.lock`: git-remote-s3's lock while it pushes that ref.
 *
 * Paths here are relative to the bucket's `git` folder ([S3Storage.GIT]).
 * Plain JVM code, tested on the build host.
 */
class GitBucket(private val objects: Objects) {
    /** The few object calls this needs; [of] adapts a bucket folder. */
    interface Objects {
        fun list(sub: String = ""): List<S3Storage.Item>
        fun get(rel: String): InputStream?
        fun put(rel: String, file: File)
        fun put(rel: String, bytes: ByteArray)
        fun delete(rel: String)
    }

    data class Ref(val name: String, val sha: String, val key: String, val size: Long, val modifiedAt: Long)

    /**
     * One repository. [refs] holds one bundle per ref, the newest when a ref has
     * several (a push from elsewhere that did not finish); [locked] are refs that
     * git-remote-s3 is pushing right now.
     */
    data class Repo(val name: String, val head: String?, val refs: Map<String, Ref>, val locked: Set<String>) {
        val size: Long get() = refs.values.sumOf { it.size }
        val modifiedAt: Long get() = refs.values.maxOfOrNull { it.modifiedAt } ?: 0L
        val branches: List<String> get() = refs.keys.filter { it.startsWith("refs/heads/") }.map { it.removePrefix("refs/heads/") }.sorted()
    }

    fun repos(now: Long = System.currentTimeMillis()): List<Repo> =
        parse(objects.list(), now).sortedBy { it.name.lowercase() }

    fun repo(name: String, now: Long = System.currentTimeMillis()): Repo? =
        parse(objects.list("${checkName(name)}/"), now).firstOrNull { it.name == name }

    fun download(ref: Ref, to: File) {
        to.parentFile?.mkdirs()
        val input = objects.get(ref.key) ?: throw java.io.IOException("${ref.key} is gone")
        input.use { src -> to.outputStream().use { src.copyTo(it) } }
    }

    /** Stores [file] as [ref]'s bundle at [sha], then drops the bundles it replaces. */
    fun upload(repo: String, ref: String, sha: String, file: File, replaces: Collection<Ref>) {
        objects.put("${checkName(repo)}/${checkRef(ref)}/$sha.bundle", file)
        replaces.filter { it.sha != sha }.forEach { objects.delete(it.key) }
    }

    /** Sets the default branch; git-remote-s3 reads it to know what a clone checks out. */
    fun setHead(repo: String, ref: String) = objects.put("${checkName(repo)}/HEAD", checkRef(ref).toByteArray())

    /** Every object of [repo], bundles, HEAD and anything git-remote-s3 put there (LFS objects). */
    fun delete(repo: String) {
        objects.list("${checkName(repo)}/").forEach { objects.delete(it.key) }
    }

    private fun parse(items: List<S3Storage.Item>, now: Long): List<Repo> {
        val byRepo = items.groupBy { it.key.substringBefore('/', "") }.filterKeys { NAME.matches(it) }
        return byRepo.map { (name, list) ->
            var head: String? = null
            val bundles = mutableMapOf<String, MutableList<Ref>>()
            val locked = mutableSetOf<String>()
            for (item in list) {
                val rel = item.key.removePrefix("$name/")
                when {
                    rel == "HEAD" -> head = runCatching { objects.get(item.key)?.use { String(it.readBytes()).trim() } }.getOrNull()
                    !rel.startsWith("refs/") -> Unit
                    rel.endsWith("/$LOCK") ->
                        if (now - item.modifiedAt < LOCK_TTL_MS) locked += rel.removeSuffix("/$LOCK")
                    else -> {
                        val file = rel.substringAfterLast('/')
                        val ref = rel.substringBeforeLast('/')
                        val sha = file.removeSuffix(".bundle")
                        if (file.endsWith(".bundle") && SHA.matches(sha)) {
                            bundles.getOrPut(ref) { mutableListOf() } += Ref(ref, sha, item.key, item.size, item.modifiedAt)
                        }
                    }
                }
            }
            Repo(name, head?.takeIf { it.startsWith("refs/") }, bundles.mapValues { (_, b) -> b.maxBy { it.modifiedAt } }, locked)
        }.filter { it.head != null || it.refs.isNotEmpty() }
    }

    /** All bundles of [ref] in [repo], so an upload can replace every one of them. */
    fun bundlesOf(repo: String, ref: String): List<Ref> =
        objects.list("${checkName(repo)}/${checkRef(ref)}/").mapNotNull { item ->
            val sha = item.key.substringAfterLast('/').removeSuffix(".bundle")
            if (item.key.endsWith(".bundle") && SHA.matches(sha)) Ref(ref, sha, item.key, item.size, item.modifiedAt) else null
        }

    companion object {
        fun of(space: S3Storage.Space) = GitBucket(object : Objects {
            override fun list(sub: String) = space.list(sub)
            override fun get(rel: String) = space.get(rel)
            override fun put(rel: String, file: File) = space.put(rel, file)
            override fun put(rel: String, bytes: ByteArray) = space.put(rel, bytes)
            override fun delete(rel: String) = space.delete(rel)
        })

        /** Letters, digits, `.`, `_` and `-`; not a dot name and not ending in `.git`. */
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val SHA = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
        private val REF = Regex("^refs/(heads|tags)/[A-Za-z0-9_./-]{1,200}$")
        const val LOCK = "LOCK#.lock"
        /** git-remote-s3's default lock lifetime. */
        const val LOCK_TTL_MS = 60_000L

        fun validName(name: String) = NAME.matches(name) && !name.endsWith(".git") && !name.endsWith(".lock") && ".." !in name

        fun checkName(name: String): String {
            require(validName(name)) { "bad repository name" }
            return name
        }

        fun checkRef(ref: String): String {
            require(REF.matches(ref) && ".." !in ref && "//" !in ref && !ref.endsWith("/") && !ref.endsWith(".lock")) { "bad ref $ref" }
            return ref
        }
    }
}
