package dev.mokouliszt.poordsh.backend.opencode

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Reads each gateway's live catalog, the list of models NIM serves and Venice's models
 * (FREE_GATEWAYS_LIVE=1; no key needed), and writes the config block the app would
 * use to build/<id>-provider-live.json, for an
 * end-to-end run against a real opencode (docs/maintenance.md).
 */
class FreeGatewaysLiveTest {
    @Test
    fun liveCatalogsHaveFreeAgentModels() {
        assumeTrue(System.getenv("FREE_GATEWAYS_LIVE") == "1")
        for (g in FreeGateway.ALL) {
            val models = FreeGateway.FreeModels(g, File(createTempDirectory().toFile(), "free.json")).load()
            assertTrue("${g.id}: no free models parsed", !models.isNullOrEmpty())
            File("build/${g.id}-provider-live.json").apply { parentFile?.mkdirs(); writeText(g.providerConfig(models!!).toString(2)) }
        }
        val served = NvidiaNim.catalog(File(createTempDirectory().toFile(), "served.json")).load()
        assertTrue("nvidia: no models parsed", !served.isNullOrEmpty())
        File("build/nvidia-provider-live.json").writeText(NvidiaNim.providerConfig(served!!).toString(2))
        val venice = Venice.catalog(File(createTempDirectory().toFile(), "venice.json")).load()
        assertTrue("venice: no models parsed", !venice.isNullOrEmpty())
        File("build/venice-provider-live.json").writeText(Venice.providerConfig(venice!!).toString(2))
    }
}
