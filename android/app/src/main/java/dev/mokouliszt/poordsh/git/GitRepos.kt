package dev.mokouliszt.poordsh.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Git for agents, kept in the user's bucket (docs/adr/git-repos.md). The bucket
 * key never reaches a sandbox: the app moves bundles between the bucket and the
 * chat's sandbox, and git runs in the sandbox.
 *
 * - [open] / [create] (the agent's `repo` tool): the repository becomes a bare
 *   repository at `/tmp/git/<name>.git` in the chat's sandbox, a plain remote to
 *   clone from and push to. Force pushes and branch deletions are refused there.
 * - [save] (when a turn ends): every branch and tag that moved on is written back
 *   as a new bundle. A branch that the bucket moved on meanwhile, in a way the
 *   sandbox's history does not contain, is not overwritten: the sandbox's commits
 *   go to `<branch>-<chat id>` instead, so nothing is lost.
 *
 * [bucket] is null while no bucket is connected; [shell] runs [block] with the
 * chat's sandbox held up. Which chats have opened a repository is kept in
 * [stateFile], so a restart between opening and saving still saves.
 */
class GitRepos(
    private val bucket: () -> GitBucket?,
    private val shell: suspend (chatId: String, block: suspend (SandboxShell) -> Unit) -> Unit,
    private val staging: File,
    private val stateFile: File,
    private val log: (String) -> Unit,
    private val root: String = ROOT,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val chats: MutableSet<String> = load()

    val available get() = bucket() != null

    /** Whether [chatId] has opened a repository, so its turns end with a [save]. */
    fun has(chatId: String) = chatId in chats

    /** The agent's tool call: [action] "list", "open" or "create". Returns what the model reads. */
    suspend fun call(chatId: String, action: String, name: String?): String = withContext(Dispatchers.IO) {
        val b = bucket() ?: return@withContext "No storage is connected, so there are no repositories."
        when (action) {
            "list" -> list(b)
            "open", "create" -> {
                val n = name?.trim().orEmpty()
                if (!GitBucket.validName(n)) return@withContext "Not a repository name: use letters, digits, '.', '_' and '-' (up to 64), not ending in .git."
                var text = ""
                shell(chatId) { sh -> text = if (action == "open") open(b, sh, chatId, n) else create(b, sh, chatId, n) }
                text
            }
            else -> "Unknown action $action. Use list, open or create."
        }
    }

    private fun list(b: GitBucket): String {
        val repos = b.repos()
        if (repos.isEmpty()) return "No repositories yet. Create one with action create."
        return "Repositories:\n" + repos.joinToString("\n") { r ->
            "- ${r.name}: " + (r.branches.joinToString(", ").ifEmpty { "no branches yet" }) +
                (r.head?.removePrefix("refs/heads/")?.let { " (default $it)" } ?: "")
        }
    }

    private suspend fun open(b: GitBucket, sh: SandboxShell, chatId: String, name: String): String = lock(name) {
        val repo = b.repo(name) ?: return@lock "No repository named $name. " + list(b)
        if (repo.locked.isNotEmpty()) log("git $name: ${repo.locked} being pushed from elsewhere; reading what is there")
        val args = mutableListOf(name, repo.head ?: DEFAULT_HEAD)
        val dir = File(staging, UUID.randomUUID().toString())
        try {
            for (ref in repo.refs.values) {
                val local = File(dir, "${ref.sha}.bundle")
                b.download(ref, local)
                sh.upload(local, "$root/.incoming/${ref.sha}.bundle")
                args += listOf(ref.name, ref.sha)
            }
        } finally {
            dir.deleteRecursively()
        }
        val r = sh.run(script(OPEN), args)
        if (r.exitCode != 0) throw IOException("could not open $name: ${(r.stderr + r.stdout).trim().takeLast(300)}")
        remember(chatId)
        log("git $name: opened in $chatId (${repo.refs.size} refs)")
        val kept = r.stdout.lineSequence().filter { it.startsWith("kept ") }.map { it.removePrefix("kept ") }.toList()
        ready(name, repo.branches, kept)
    }

    private suspend fun create(b: GitBucket, sh: SandboxShell, chatId: String, name: String): String = lock(name) {
        if (b.repo(name) != null) return@lock "$name already exists. Open it with action open."
        val r = sh.run(script(OPEN), listOf(name, DEFAULT_HEAD))
        if (r.exitCode != 0) throw IOException("could not create $name: ${(r.stderr + r.stdout).trim().takeLast(300)}")
        // Listed from now on, though it holds nothing until the first push is saved.
        b.setHead(name, DEFAULT_HEAD)
        remember(chatId)
        log("git $name: created in $chatId")
        ready(name, emptyList(), emptyList())
    }

    private fun ready(name: String, branches: List<String>, kept: List<String>): String = buildString {
        append("Ready: $root/$name.git, a bare repository")
        append(if (branches.isEmpty()) " with no commits yet." else " with ${branches.joinToString(", ")}.")
        append("\nClone it, for example: git clone $root/$name.git /tmp/workspace/$name")
        append("\nWhat you push there is saved when your turn ends. Force pushes and deleting branches are refused.")
        if (kept.isNotEmpty()) append("\nYour earlier unsaved commits on ${kept.joinToString(", ")} were kept; they are saved when this turn ends.")
    }

    /**
     * Writes back what moved on in the repositories of [chatId]'s sandbox. Called
     * when a turn ends, while it still holds the sandbox. Quiet when there is nothing.
     */
    suspend fun save(chatId: String) = withContext(Dispatchers.IO) {
        if (chatId !in chats) return@withContext
        val b = bucket() ?: return@withContext
        shell(chatId) { sh ->
            val report = sh.run(script(REPORT))
            if (report.exitCode != 0) throw IOException("could not read repositories: ${report.stderr.trim().takeLast(300)}")
            for ((name, local) in parseReport(report.stdout)) {
                runCatching { saveRepo(b, sh, chatId, name, local) }
                    .onFailure { log("git $name: not saved (${it.message})") }
            }
        }
    }

    private data class Local(val head: String?, val refs: Map<String, String>)

    private suspend fun saveRepo(b: GitBucket, sh: SandboxShell, chatId: String, name: String, local: Local) = lock(name) {
        if (!GitBucket.validName(name)) return@lock
        var stored = b.repo(name)
        // A ref being pushed from elsewhere is left for a moment (git-remote-s3 holds it for under a minute).
        val busy = local.refs.keys.filter { stored?.locked?.contains(it) == true }
        if (busy.isNotEmpty()) {
            val until = System.currentTimeMillis() + GitBucket.LOCK_TTL_MS
            while (System.currentTimeMillis() < until && b.repo(name)?.locked?.any { it in busy } == true) kotlinx.coroutines.delay(2_000)
            stored = b.repo(name)
        }
        val changed = local.refs.filter { (ref, sha) -> runCatching { GitBucket.checkRef(ref) }.isSuccess && stored?.refs?.get(ref)?.sha != sha }
        if (changed.isEmpty()) return@lock
        // The bucket's commits must be in the sandbox to tell a fast-forward from a fork.
        val theirs = changed.keys.mapNotNull { stored?.refs?.get(it) }
        if (theirs.isNotEmpty()) {
            val probe = sh.run(script(HAS), listOf(name) + theirs.map { it.sha })
            val missing = probe.stdout.lineSequence().filter { it.startsWith("missing ") }.map { it.removePrefix("missing ") }.toSet()
            val dir = File(staging, UUID.randomUUID().toString())
            try {
                for (ref in theirs.filter { it.sha in missing }) {
                    val file = File(dir, "${ref.sha}.bundle")
                    b.download(ref, file)
                    sh.upload(file, "$root/.incoming/${ref.sha}.bundle")
                }
            } finally {
                dir.deleteRecursively()
            }
            if (missing.isNotEmpty()) {
                val fetch = sh.run(script(FETCH_THEIRS), listOf(name) + theirs.filter { it.sha in missing }.flatMap { listOf(it.name, it.sha) })
                if (fetch.exitCode != 0) throw IOException("could not read the stored history: ${fetch.stderr.trim().takeLast(300)}")
            }
        }
        // Per ref: "<ref> <local sha> <stored sha or ->" -> what to write.
        val plan = sh.run(script(PLAN), listOf(name, chatId) + changed.flatMap { (ref, sha) -> listOf(ref, sha, stored?.refs?.get(ref)?.sha ?: "-") })
        if (plan.exitCode != 0) throw IOException("could not compare with the stored history: ${plan.stderr.trim().takeLast(300)}")
        val writes = plan.stdout.lineSequence().mapNotNull { line ->
            val p = line.trim().split(' ')
            if (p.size == 3 && p[0] == "write") p[1] to p[2] else null
        }.filter { (ref, sha) -> stored?.refs?.get(ref)?.sha != sha }.toList()
        for ((ref, sha) in writes) {
            val bundled = sh.run(script(BUNDLE), listOf(name, ref, sha))
            if (bundled.exitCode != 0) throw IOException("could not bundle $ref: ${bundled.stderr.trim().takeLast(300)}")
            val file = File(staging, UUID.randomUUID().toString() + ".bundle")
            try {
                sh.download("$root/.out/$sha.bundle", file)
                b.upload(name, ref, sha, file, b.bundlesOf(name, ref))
            } finally {
                file.delete()
                sh.run("rm -f \"$root/.out/$sha.bundle\"")
            }
            log("git $name: saved $ref at ${sha.take(8)} from $chatId")
        }
        // The default branch must be one that exists, or a clone checks out nothing.
        val branches = (stored?.refs?.keys.orEmpty() + writes.map { it.first }).filter { it.startsWith("refs/heads/") }
        val head = stored?.head ?: local.head ?: DEFAULT_HEAD
        if (branches.isNotEmpty() && head !in branches) b.setHead(name, if (DEFAULT_HEAD in branches) DEFAULT_HEAD else branches.min())
        else if (stored?.head == null && writes.isNotEmpty()) b.setHead(name, head)
        plan.stdout.lineSequence().filter { line -> line.startsWith("forked ") && writes.any { line.endsWith(" ${it.first}") } }
            .forEach { log("git $name: ${it.removePrefix("forked ")}") }
    }

    /** A chat is gone: nothing of it is left to save. */
    fun forget(chatId: String) {
        synchronized(chats) { if (chats.remove(chatId)) store() }
    }

    private fun remember(chatId: String) {
        synchronized(chats) { if (chats.add(chatId)) store() }
    }

    private fun load(): MutableSet<String> {
        val set = ConcurrentHashMap.newKeySet<String>()
        runCatching { JSONArray(stateFile.readText()) }.getOrNull()?.let { a -> (0 until a.length()).forEach { set += a.optString(it) } }
        return set
    }

    private fun store() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(JSONArray(chats.toList().sorted()).toString())
            if (!tmp.renameTo(stateFile)) stateFile.writeText(tmp.readText())
        }
    }

    private suspend fun <T> lock(name: String, block: suspend () -> T): T = locks.getOrPut(name) { Mutex() }.withLock { block() }

    private fun script(body: String) = "set -eu\nroot='$root'\n" + body.trimIndent()

    private fun parseReport(text: String): Map<String, Local> {
        val heads = mutableMapOf<String, String?>()
        val refs = mutableMapOf<String, MutableMap<String, String>>()
        for (line in text.lineSequence()) {
            val p = line.trim().split(' ')
            when {
                p.size == 3 && p[0] == "repo" -> heads[p[1]] = p[2].takeIf { it.startsWith("refs/") }
                p.size == 4 && p[0] == "ref" -> refs.getOrPut(p[1]) { mutableMapOf() }[p[2]] = p[3]
            }
        }
        return heads.keys.associateWith { Local(heads[it], refs[it].orEmpty()) }
    }

    companion object {
        /** Where the repositories are in every sandbox. */
        const val ROOT = "/tmp/git"
        const val DEFAULT_HEAD = "refs/heads/main"

        /** What opencode is told when a bucket is connected (with [dev.mokouliszt.poordsh.outputs.Outputs.INSTRUCTIONS]). */
        val INSTRUCTIONS = """
            Git repositories: the user keeps git repositories in their own storage. Use the repo tool to list them, open one, or create one;
            it then is a bare repository at $ROOT/<name>.git to clone from and push to. What you push there is saved when your turn ends.
            Force pushes and deleting branches are refused there; to start over, use a new branch.
        """.trimIndent()

        /**
         * Args: name, HEAD ref, then pairs of ref and sha whose bundles are in .incoming.
         * A ref the sandbox already has ahead of (or apart from) the bucket stays as it is
         * ("kept <branch>"): those are commits not saved yet.
         */
        private val OPEN = """
            name="${'$'}1"; head="${'$'}2"; shift 2
            repo="${'$'}root/${'$'}name.git"
            mkdir -p "${'$'}root/.incoming"
            [ -d "${'$'}repo" ] || git init -q --bare "${'$'}repo"
            git -C "${'$'}repo" config receive.denyNonFastForwards true
            git -C "${'$'}repo" config receive.denyDeletes true
            git -C "${'$'}repo" config core.logAllRefUpdates true
            git config --global user.name >/dev/null 2>&1 || git config --global user.name "PoorHarness"
            git config --global user.email >/dev/null 2>&1 || git config --global user.email "agent@poorharness.invalid"
            while [ ${'$'}# -ge 2 ]; do
              ref="${'$'}1"; sha="${'$'}2"; shift 2
              b="${'$'}root/.incoming/${'$'}sha.bundle"
              cur="${'$'}(git -C "${'$'}repo" rev-parse -q --verify "${'$'}ref" || true)"
              if [ "${'$'}cur" != "${'$'}sha" ]; then
                git -C "${'$'}repo" fetch -q "${'$'}b" "${'$'}ref:${'$'}ref" 2>/dev/null || echo "kept ${'$'}{ref#refs/heads/}"
              fi
              rm -f "${'$'}b"
            done
            git -C "${'$'}repo" symbolic-ref HEAD "${'$'}head"
        """

        /** Prints "repo <name> <HEAD>" and "ref <name> <ref> <sha>" for every repository here. */
        private val REPORT = """
            [ -d "${'$'}root" ] || exit 0
            for repo in "${'$'}root"/*.git; do
              [ -d "${'$'}repo" ] || continue
              name="${'$'}(basename "${'$'}repo" .git)"
              echo "repo ${'$'}name ${'$'}(git -C "${'$'}repo" symbolic-ref -q HEAD || echo -)"
              git -C "${'$'}repo" for-each-ref --format="ref ${'$'}name %(refname) %(objectname)" refs/heads refs/tags
            done
        """

        /** Args: name, shas. Prints "missing <sha>" for commits the repository lacks. */
        private val HAS = """
            repo="${'$'}root/${'$'}1.git"; shift
            for sha in "${'$'}@"; do git -C "${'$'}repo" cat-file -e "${'$'}sha^{commit}" 2>/dev/null || echo "missing ${'$'}sha"; done
        """

        /** Args: name, pairs of ref and sha whose bundles are in .incoming; kept under refs/poorharness/stored. */
        private val FETCH_THEIRS = """
            repo="${'$'}root/${'$'}1.git"; shift
            while [ ${'$'}# -ge 2 ]; do
              b="${'$'}root/.incoming/${'$'}2.bundle"
              git -C "${'$'}repo" fetch -q "${'$'}b" "+${'$'}1:refs/poorharness/stored/${'$'}1"
              rm -f "${'$'}b"; shift 2
            done
        """

        /**
         * Args: name, chat id, triples of ref, local sha, stored sha ("-" for none).
         * Prints "write <ref> <sha>" for each bundle to store: the ref itself when it
         * is new or a fast-forward of the stored one; "<branch>-<chat id>" when the
         * two histories forked (the stored branch stays). Nothing when the bucket is ahead.
         */
        private val PLAN = """
            repo="${'$'}root/${'$'}1.git"; chat="${'$'}2"; shift 2
            while [ ${'$'}# -ge 3 ]; do
              ref="${'$'}1"; ours="${'$'}2"; theirs="${'$'}3"; shift 3
              if [ "${'$'}theirs" = "-" ] || git -C "${'$'}repo" merge-base --is-ancestor "${'$'}theirs" "${'$'}ours"; then
                echo "write ${'$'}ref ${'$'}ours"
              elif git -C "${'$'}repo" merge-base --is-ancestor "${'$'}ours" "${'$'}theirs"; then
                :
              else
                fork="${'$'}ref-${'$'}chat"
                git -C "${'$'}repo" update-ref "${'$'}fork" "${'$'}ours"
                echo "write ${'$'}fork ${'$'}ours"
                echo "forked ${'$'}ref moved on elsewhere; this chat's commits are on ${'$'}fork"
              fi
            done
        """

        /** Args: name, ref, sha. Writes .out/<sha>.bundle with the ref's whole history. */
        private val BUNDLE = """
            repo="${'$'}root/${'$'}1.git"
            mkdir -p "${'$'}root/.out"
            git -C "${'$'}repo" bundle create -q "${'$'}root/.out/${'$'}3.bundle" "${'$'}2"
        """
    }
}
