package dev.mokouliszt.poordsh.git

import dev.mokouliszt.poordsh.storage.S3Storage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * docs/adr/git-repos.md, with real git on the build host: a folder stands in for
 * each chat's sandbox, a map for the bucket.
 */
class GitReposTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The bucket's `git` folder, in memory. */
    private class Memory : GitBucket.Objects {
        val objects = sortedMapOf<String, Pair<ByteArray, Long>>()
        var clock = 1_000L
        override fun list(sub: String) = objects.filterKeys { it.startsWith(sub) }.map { (k, v) -> S3Storage.Item(k, v.first.size.toLong(), v.second) }
        override fun get(rel: String): InputStream? = objects[rel]?.let { ByteArrayInputStream(it.first) }
        override fun put(rel: String, file: File) = put(rel, file.readBytes())
        override fun put(rel: String, bytes: ByteArray) { objects[rel] = bytes to clock++ }
        override fun delete(rel: String) { objects.remove(rel) }
    }

    /** A sandbox: bash with its own HOME, files by path. */
    private inner class Box(val root: File) : SandboxShell {
        val home = tmp.newFolder()
        override suspend fun run(script: String, args: List<String>, timeoutSecs: Int): SandboxShell.Result {
            val p = ProcessBuilder(listOf("bash", "-c", script, "repo") + args).apply { environment()["HOME"] = home.path }.start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor(60, TimeUnit.SECONDS)
            return SandboxShell.Result(p.exitValue(), out, err)
        }
        override suspend fun upload(local: File, path: String) { File(path).apply { parentFile?.mkdirs() }.let { local.copyTo(it, true) } }
        override suspend fun download(path: String, local: File) { File(path).copyTo(local, true) }

        /** What the agent does in the sandbox. */
        fun sh(cmd: String): String {
            val p = ProcessBuilder("bash", "-c", cmd).apply { environment()["HOME"] = home.path; redirectErrorStream(true) }.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(60, TimeUnit.SECONDS)
            check(p.exitValue() == 0) { "$cmd failed: $out" }
            return out.trim()
        }

        fun shFails(cmd: String): Boolean {
            val p = ProcessBuilder("bash", "-c", cmd).apply { environment()["HOME"] = home.path; redirectErrorStream(true) }.start()
            p.inputStream.readBytes()
            p.waitFor(60, TimeUnit.SECONDS)
            return p.exitValue() != 0
        }
    }

    private fun gitInstalled() = runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun repos(memory: Memory, boxes: Map<String, Box>, root: String) = GitRepos(
        bucket = { GitBucket(memory) },
        shell = { chat, block -> block(boxes.getValue(chat)) },
        staging = tmp.newFolder(),
        stateFile = File(tmp.root, "git-chats-$root.json"),
        log = { println(it) },
        root = root,
    )

    @Test
    fun repositoriesGoThroughTheBucket() = runBlocking {
        assumeTrue("git not installed", gitInstalled())
        val memory = Memory()
        val one = Box(tmp.newFolder("one"))
        val two = Box(tmp.newFolder("two"))
        // Each sandbox has its repositories at the same path; here each its own folder.
        val gitOne = repos(memory, mapOf("c_one" to one), one.root.path)
        val gitTwo = repos(memory, mapOf("c_two" to two), two.root.path)

        assertTrue(gitOne.call("c_one", "list", null).startsWith("No repositories"))
        assertTrue(gitOne.call("c_one", "create", "../x").startsWith("Not a repository name"))
        assertTrue(gitOne.call("c_one", "create", "demo").startsWith("Ready: ${one.root}/demo.git"))
        assertEquals("refs/heads/main", String(memory.objects.getValue("demo/HEAD").first))
        assertTrue(gitOne.call("c_one", "create", "demo").contains("already exists"))

        // The agent clones, commits and pushes; the turn ends.
        one.sh("git clone -q ${one.root}/demo.git ${one.root}/w && cd ${one.root}/w && echo a > a.txt && git add a.txt && git commit -qm a && git push -q origin HEAD:main")
        gitOne.save("c_one")
        val first = one.sh("git -C ${one.root}/demo.git rev-parse main")
        assertTrue(memory.objects.containsKey("demo/refs/heads/main/$first.bundle"))
        // The bundle is what git-remote-s3 would fetch: a clone of it has the commit.
        val bundle = File(tmp.root, "b.bundle").apply { writeBytes(memory.objects.getValue("demo/refs/heads/main/$first.bundle").first) }
        one.sh("git clone -q -b main $bundle ${tmp.root}/fromBundle")
        assertEquals("a", File(tmp.root, "fromBundle/a.txt").readText().trim())
        // Nothing new: nothing written.
        val before = memory.objects.keys.toList()
        gitOne.save("c_one")
        assertEquals(before, memory.objects.keys.toList())

        // Another chat opens it and moves main on; force pushes and deletions are refused.
        assertTrue(gitTwo.call("c_two", "list", null).contains("- demo: main (default main)"))
        assertTrue(gitTwo.call("c_two", "open", "demo").contains("with main"))
        two.sh("git clone -q ${two.root}/demo.git ${two.root}/w && cd ${two.root}/w && echo b > b.txt && git add b.txt && git commit -qm b && git push -q origin main")
        assertTrue(two.shFails("cd ${two.root}/w && git commit -q --amend -m b2 && git push -q --force origin main"))
        assertTrue(two.shFails("cd ${two.root}/w && git push -q origin :main"))
        two.sh("cd ${two.root}/w && git reset -q --hard origin/main && git tag v1 && git push -q origin v1")
        gitTwo.save("c_two")
        val second = two.sh("git -C ${two.root}/demo.git rev-parse main")
        assertTrue(memory.objects.containsKey("demo/refs/heads/main/$second.bundle"))
        assertFalse("the old bundle is replaced", memory.objects.containsKey("demo/refs/heads/main/$first.bundle"))
        assertTrue(memory.objects.keys.any { it.startsWith("demo/refs/tags/v1/") })

        // Meanwhile the first chat committed on the old main: its work goes to a branch of its own.
        one.sh("cd ${one.root}/w && echo c > c.txt && git add c.txt && git commit -qm c && git push -q origin main")
        gitOne.save("c_one")
        val mine = one.sh("git -C ${one.root}/demo.git rev-parse main")
        assertNotEquals(second, mine)
        assertTrue("main stays the other chat's", memory.objects.containsKey("demo/refs/heads/main/$second.bundle"))
        assertTrue(memory.objects.containsKey("demo/refs/heads/main-c_one/$mine.bundle"))
        // Saving again writes nothing more.
        val after = memory.objects.keys.toList()
        gitOne.save("c_one")
        assertEquals(after, memory.objects.keys.toList())

        // Opening again brings the other chat's main and keeps this chat's unsaved commits.
        one.sh("cd ${one.root}/w && echo d > d.txt && git add d.txt && git commit -qm d && git push -q origin main")
        val reopened = gitOne.call("c_one", "open", "demo")
        assertTrue(reopened, reopened.contains("earlier unsaved commits on main"))
        assertEquals(listOf("main", "main-c_one"), GitBucket(memory).repo("demo")!!.branches)

        gitOne.forget("c_one")
        gitOne.save("c_one") // a forgotten chat has nothing to save
        GitBucket(memory).delete("demo")
        assertTrue(memory.objects.isEmpty())
    }

    @Test
    fun theLayoutIsGitRemoteS3s() {
        val memory = Memory()
        val sha = "a".repeat(40)
        memory.put("r/HEAD", "refs/heads/main".toByteArray())
        memory.put("r/refs/heads/main/$sha.bundle", ByteArray(10))
        memory.put("r/refs/heads/feature/x/${"b".repeat(40)}.bundle", ByteArray(5))
        memory.put("r/refs/heads/main/LOCK#.lock", ByteArray(0))
        memory.put("r/lfs/abc", ByteArray(3))
        memory.put("junk.txt", ByteArray(1))
        val repo = GitBucket(memory).repos(now = memory.clock).single()
        assertEquals("r", repo.name)
        assertEquals("refs/heads/main", repo.head)
        assertEquals(listOf("feature/x", "main"), repo.branches)
        assertEquals(setOf("refs/heads/main"), repo.locked)
        assertEquals(15, repo.size)
        // An old lock is one left behind; git-remote-s3 clears it too.
        assertTrue(GitBucket(memory).repos(now = memory.clock + GitBucket.LOCK_TTL_MS + 1).single().locked.isEmpty())
        assertFalse(GitBucket.validName("x.git"))
        assertFalse(GitBucket.validName(".hidden"))
        assertTrue(GitBucket.validName("my-repo_2.0"))
    }
}
