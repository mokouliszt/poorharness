package dev.mokouliszt.poordsh.outputs

import dev.mokouliszt.poordsh.chat.ChatStore
import dev.mokouliszt.poordsh.runtime.LogBuffer
import dev.mokouliszt.poordsh.storage.S3Storage
import dev.mokouliszt.poordsh.tensorlake.SandboxManager
import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * A Tensorlake sandbox's outputs folder handed over into an S3-compatible bucket
 * (docs/adr/outputs.md). Runs with TENSORLAKE_API_KEY and S3_ENDPOINT, S3_BUCKET,
 * S3_KEY_ID, S3_SECRET; uses its own sandbox ("poordsh-test-…") and bucket prefix,
 * both removed at the end.
 */
class OutputsLiveTest {
    @Test
    fun sandboxToBucket() = runBlocking {
        val key = System.getenv("TENSORLAKE_API_KEY")
        val endpoint = System.getenv("S3_ENDPOINT")
        assumeTrue(!key.isNullOrBlank() && !endpoint.isNullOrBlank())
        val api = TensorlakeApi({ key })
        val mgr = SandboxManager(api, LogBuffer("test"))
        val box = SandboxManager.nameFor("test_" + UUID.randomUUID().toString().replace("-", "").take(12))
        val prefix = "poorharness-test-" + UUID.randomUUID().toString().take(8)
        val s3 = S3Storage(S3Storage.Config(endpoint!!, System.getenv("S3_BUCKET"), System.getenv("S3_KEY_ID"), System.getenv("S3_SECRET"), prefix = prefix))
        val bucket = OutputShelf.S3(s3.space(S3Storage.OUTPUTS))
        val store = ChatStore(Files.createTempDirectory("chats").toFile())
        val chat = store.create()
        val svc = OutputService(
            device = OutputShelf.Device(Files.createTempDirectory("device").toFile()),
            s3 = { bucket },
            destination = { OutputShelf.S3 },
            staging = Files.createTempDirectory("staging").toFile(),
            delivered = store::outputSignatures,
            record = store::recordOutput,
        )
        try {
            val sb = mgr.ensure(box).sandbox
            val dir = Outputs.DIR
            api.write(sb, "$dir/report.md", "# Report\nFIG-31\n".toByteArray())
            api.write(sb, "$dir/data.csv", "a,b\n1,2\n".toByteArray())
            api.write(sb, "$dir/pics/a.txt", "A".toByteArray())
            api.write(sb, "$dir/pics/sub/b.txt", "B".toByteArray())
            api.write(sb, "$dir/.scratch", "x".toByteArray())
            val source = CloudOutputs(api, sb)

            val first = svc.collect(chat, source)
            assertEquals(listOf("data.csv", "pics.zip", "report.md"), first.map { it.name })
            assertTrue(first.all { it.shelf == OutputShelf.S3 && it.error == null })
            assertEquals(setOf("data.csv", "pics.zip", "report.md"), bucket.list().filter { it.chatId == chat }.map { it.name }.toSet())
            assertEquals("# Report\nFIG-31\n", bucket.open(chat, "report.md")!!.use { it.readBytes().decodeToString() })
            val zipped = ZipInputStream(bucket.open(chat, "pics.zip")!!).use { z ->
                generateSequence { z.nextEntry }.map { it.name }.toList().sorted()
            }
            assertEquals(listOf("pics/a.txt", "pics/sub/b.txt"), zipped)

            // Nothing changed: nothing again. A changed file and a changed folder go again.
            assertTrue(svc.collect(chat, source).isEmpty())
            Thread.sleep(1100)
            api.write(sb, "$dir/report.md", "# Report v2\n".toByteArray())
            api.write(sb, "$dir/pics/c.txt", "C".toByteArray())
            assertEquals(listOf("pics.zip", "report.md"), svc.collect(chat, source).map { it.name })
            assertEquals("# Report v2\n", bucket.open(chat, "report.md")!!.use { it.readBytes().decodeToString() })

            bucket.deleteAll()
            assertTrue(bucket.list().isEmpty())
        } finally {
            runCatching { api.delete(box) }
            runCatching { s3.deleteAll(s3.list("$prefix/").map { it.key }) }
        }
    }
}
