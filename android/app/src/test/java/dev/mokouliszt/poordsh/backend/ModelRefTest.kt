package dev.mokouliszt.poordsh.backend

import org.junit.Assert.assertEquals
import org.junit.Test

/** Model ids may contain ':' themselves (Infron's free variants end in ":free"); only the first one splits. */
class ModelRefTest {
    @Test
    fun keepsColonsInsideTheModelId() {
        val m = ModelRef.parse("opencode:infron/deepseek/deepseek-v4.1-flash:free", null)
        assertEquals("opencode", m.backend)
        assertEquals("infron", m.provider)
        assertEquals("deepseek/deepseek-v4.1-flash:free", m.modelOnly)
        assertEquals("infron" to "deepseek/deepseek-v4.1-flash:free", Ids.split("infron:deepseek/deepseek-v4.1-flash:free"))
    }
}
