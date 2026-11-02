package dev.mokouliszt.poordsh.backend.opencode

import org.junit.Assert.assertEquals
import org.junit.Test

class OpencodeErrorsTest {
    @Test
    fun aModelOpencodeDoesNotOfferBecomesACode() {
        // As opencode 1.19.0 sends it, once plain and once with the stack.
        val plain = "Model not found: orcarouter/orca/retired-free. Did you mean: orca/flash-free?"
        val traced = "ProviderModelNotFoundError: $plain\n    at <anonymous> (/\$bunfs/root/chunk.js:2:64699)\n    at SessionPrompt.getModel (/\$bunfs/root/chunk.js:50:11031)"
        assertEquals(OpencodeErrors.MODEL_GONE, OpencodeErrors.forChat(plain))
        assertEquals(OpencodeErrors.MODEL_GONE, OpencodeErrors.forChat(traced))
    }

    @Test
    fun otherErrorsLoseOnlyTheirStack() {
        assertEquals("TypeError: nope", OpencodeErrors.forChat("TypeError: nope\n    at x (y.js:1:1)"))
        assertEquals("rate limited\nretry later", OpencodeErrors.forChat("rate limited\nretry later"))
    }
}
