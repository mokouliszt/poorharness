package dev.mokouliszt.poordsh.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** docs/adr/s3-storage.md. The live test runs with S3_ENDPOINT, S3_BUCKET, S3_KEY_ID and S3_SECRET set. */
class S3StorageTest {
    @Test
    fun regionComesFromTheEndpoint() {
        assertEquals("us-east-005", S3Storage.Config.regionOf("https://s3.us-east-005.backblazeb2.com"))
        assertEquals("eu-central-1", S3Storage.Config.regionOf("https://s3.eu-central-1.amazonaws.com"))
        assertEquals("eu-central-1", S3Storage.Config.regionOf("https://s3.dualstack.eu-central-1.amazonaws.com"))
        assertEquals("us-east-1", S3Storage.Config.regionOf("https://s3.amazonaws.com"))
        assertEquals("us-east-1", S3Storage.Config.regionOf("https://abc123.r2.cloudflarestorage.com"))
        assertEquals("auto", S3Storage.Config("https://x.r2.cloudflarestorage.com", "b", "k", "s", region = "auto").signingRegion)
    }

    @Test
    fun keysAreEncodedTheS3Way() {
        assertEquals("a-b_c.d~e", S3Storage.encode("a-b_c.d~e"))
        assertEquals("report%20v2%2B%28final%29.pdf", S3Storage.encode("report v2+(final).pdf"))
        assertEquals("%E5%A0%B1%E5%91%8A.md", S3Storage.encode("報告.md"))
    }

    @Test
    fun everyUseHasItsOwnFolder() {
        val s3 = S3Storage(S3Storage.Config("https://s3.example.com", "b", "k", "s", prefix = "/apps/poorharness/"))
        assertEquals("apps/poorharness/outputs/", s3.space("outputs").base)
        assertEquals("outputs/", S3Storage(S3Storage.Config("https://s3.example.com", "b", "k", "s")).space("outputs").base)
        assertTrue(runCatching { s3.space("../x") }.isFailure)
    }

    @Test
    fun liveRoundTrip() {
        val endpoint = System.getenv("S3_ENDPOINT")
        val bucket = System.getenv("S3_BUCKET")
        val id = System.getenv("S3_KEY_ID")
        val secret = System.getenv("S3_SECRET")
        assumeTrue(!endpoint.isNullOrBlank() && !bucket.isNullOrBlank() && !id.isNullOrBlank() && !secret.isNullOrBlank())
        // Its own prefix, removed at the end: nothing else in the bucket is touched.
        val prefix = "poorharness-test-" + UUID.randomUUID().toString().take(8)
        val s3 = S3Storage(S3Storage.Config(endpoint!!, bucket!!, id!!, secret!!, prefix = prefix))
        try {
            s3.check()
            val space = s3.space("outputs")
            val file = File.createTempFile("out", ".txt").apply { writeText("PERSIMMON-12\n"); deleteOnExit() }
            val names = listOf("c_test/報告 v2+(final).txt", "c_test/plain.txt", "c_other/a.txt")
            names.forEach { space.put(it, file) }
            assertEquals("PERSIMMON-12\n", space.get(names[0])!!.use { it.readBytes().decodeToString() })
            assertNull(space.get("c_test/missing.txt"))
            val listed = space.list()
            assertEquals(names.sorted(), listed.map { it.key }.sorted())
            assertTrue(listed.all { it.size == 13L && it.modifiedAt > 0 })
            assertEquals(2, space.list("c_test/").size)
            space.delete(names[1])
            assertEquals(names.sorted() - names[1], space.list().map { it.key }.sorted())
            space.deleteAll()
            assertTrue(space.list().isEmpty())
        } finally {
            runCatching { s3.deleteAll(s3.list("$prefix/").map { it.key }) }
        }
    }
}
