package dev.mokouliszt.poordsh.git

import dev.mokouliszt.poordsh.runtime.LogBuffer
import dev.mokouliszt.poordsh.storage.S3Storage
import dev.mokouliszt.poordsh.tensorlake.SandboxManager
import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * docs/adr/git-repos.md against a real Tensorlake sandbox and S3-compatible bucket:
 * a repository created, pushed to in the sandbox, saved, opened in a second sandbox.
 * With `git-remote-s3` on PATH, it is also cloned the way a PC would. Runs with
 * TENSORLAKE_API_KEY and S3_ENDPOINT, S3_BUCKET, S3_KEY_ID, S3_SECRET; uses its own
 * sandboxes ("poordsh-test-…") and bucket prefix, removed at the end.
 */
class GitReposLiveTest {
    @Test
    fun sandboxToBucketAndBack() = runBlocking {
        val key = System.getenv("TENSORLAKE_API_KEY")
        val endpoint = System.getenv("S3_ENDPOINT")
        assumeTrue(!key.isNullOrBlank() && !endpoint.isNullOrBlank())
        val api = TensorlakeApi({ key })
        val mgr = SandboxManager(api, LogBuffer("test"))
        val chats = listOf("c_${UUID.randomUUID().toString().replace("-", "").take(10)}", "c_${UUID.randomUUID().toString().replace("-", "").take(10)}")
        val boxes = chats.associateWith { SandboxManager.nameFor(it).replace("poordsh-c-", "poordsh-test-") }
        val prefix = "poorharness-test-" + UUID.randomUUID().toString().take(8)
        val config = S3Storage.Config(endpoint!!, System.getenv("S3_BUCKET"), System.getenv("S3_KEY_ID"), System.getenv("S3_SECRET"), prefix = prefix)
        val s3 = S3Storage(config)
        val git = GitRepos(
            bucket = { GitBucket.of(s3.space(S3Storage.GIT)) },
            shell = { chat, block -> mgr.use(boxes.getValue(chat), "t") { block(CloudShell(api, it.sandbox)) } },
            staging = Files.createTempDirectory("git").toFile(),
            stateFile = File.createTempFile("git-chats", ".json").apply { delete() },
            log = { println(it) },
        )
        val (one, two) = chats
        suspend fun sh(chat: String, cmd: String): String {
            val sb = mgr.ensure(boxes.getValue(chat)).sandbox
            val r = api.run(sb, "bash", listOf("-c", cmd), timeoutSecs = 120)
            check(r.exitCode == 0) { "$cmd: ${r.stderr}" }
            return r.stdout.trim()
        }
        try {
            assertTrue(git.call(one, "create", "notes").startsWith("Ready: /tmp/git/notes.git"))
            sh(one, "git clone -q /tmp/git/notes.git /tmp/workspace/notes && cd /tmp/workspace/notes && echo 'first' > a.md && git add a.md && git commit -qm first && git push -q origin HEAD:main")
            git.save(one)
            val sha = sh(one, "git -C /tmp/git/notes.git rev-parse main")
            assertTrue(s3.space(S3Storage.GIT).list("notes/").any { it.key == "notes/refs/heads/main/$sha.bundle" })
            assertEquals("refs/heads/main", s3.space(S3Storage.GIT).get("notes/HEAD")!!.use { String(it.readBytes()) })

            // A second chat, in its own sandbox, sees it (the free plan runs one sandbox at a time).
            api.delete(boxes.getValue(one))
            val gone = System.currentTimeMillis() + 90_000
            while (api.list("running").any { it.name == boxes.getValue(one) } && System.currentTimeMillis() < gone) kotlinx.coroutines.delay(1_000)
            assertTrue(git.call(two, "list", null).contains("- notes: main"))
            git.call(two, "open", "notes")
            assertEquals("first", sh(two, "git clone -q /tmp/git/notes.git /tmp/workspace/notes && cat /tmp/workspace/notes/a.md"))

            // A PC with git-remote-s3 (awslabs) clones the same repository.
            if (onPath("git-remote-s3")) {
                val out = Files.createTempDirectory("pc").toFile()
                val p = ProcessBuilder("git", "clone", "-q", "s3://${config.bucket}/$prefix/${S3Storage.GIT}/notes", File(out, "notes").path).apply {
                    environment().putAll(
                        mapOf(
                            "AWS_ENDPOINT_URL" to endpoint, "AWS_ACCESS_KEY_ID" to config.accessKeyId, "AWS_SECRET_ACCESS_KEY" to config.secretAccessKey,
                            "AWS_DEFAULT_REGION" to config.signingRegion, "AWS_REQUEST_CHECKSUM_CALCULATION" to "when_required",
                            "AWS_RESPONSE_CHECKSUM_VALIDATION" to "when_required", "AWS_CA_BUNDLE" to (System.getenv("AWS_CA_BUNDLE") ?: ""),
                        ).filterValues { it.isNotEmpty() },
                    )
                    redirectErrorStream(true)
                }.start()
                val log = p.inputStream.bufferedReader().readText()
                p.waitFor(120, TimeUnit.SECONDS)
                assertEquals("git-remote-s3 clone: $log", 0, p.exitValue())
                assertEquals("first", File(out, "notes/a.md").readText().trim())
                println("cloned with git-remote-s3")
            }
        } finally {
            boxes.values.forEach { runCatching { api.delete(it) } }
            runCatching { s3.deleteAll(s3.list(config.base).map { it.key }) }
        }
    }

    private fun onPath(cmd: String) = System.getenv("PATH").orEmpty().split(':').any { File(it, cmd).canExecute() }
}
