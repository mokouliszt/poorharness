package dev.mokouliszt.poordsh.chat

import dev.mokouliszt.poordsh.backend.ContextTag
import dev.mokouliszt.poordsh.bridge.jsonOf
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffTest {
    private fun text(role: String, vararg texts: String) = jsonOf(
        "role" to role,
        "parts" to JSONArray().apply { texts.forEach { put(jsonOf("kind" to "text", "text" to it)) } },
    )

    @Test fun nothingToCarry() {
        assertNull(Handoff.build(JSONArray()))
        assertNull(Handoff.build(JSONArray().put(text("assistant", "  "))))
    }

    @Test fun wrapsTurnsAndSkipsEarlierHandoffs() {
        val tool = jsonOf(
            "role" to "assistant",
            "parts" to JSONArray()
                .put(jsonOf("kind" to "tool", "tool" to "bash", "title" to "pytest -q"))
                .put(jsonOf("kind" to "diff", "files" to JSONArray().put(jsonOf("path" to "a.py")))),
        )
        val out = Handoff.build(
            JSONArray()
                .put(text("user", "${ContextTag.OPEN}old context${ContextTag.CLOSE}", "fix the test"))
                .put(tool)
                .put(text("assistant", "done")),
        )!!
        assertTrue(out.startsWith(ContextTag.OPEN))
        assertTrue(out.endsWith(ContextTag.CLOSE))
        assertTrue(ContextTag.matches(out))
        assertFalse(out.contains("old context"))
        assertTrue(out.contains("User: fix the test"))
        assertTrue(out.contains("[bash] pytest -q"))
        assertTrue(out.contains("[changed] a.py"))
        assertTrue(out.contains("Assistant: done"))
    }

    @Test fun longChatsKeepTheStartAndTheLatestTurns() {
        val msgs = JSONArray().put(text("user", "FIRST"))
        repeat(400) { i -> msgs.put(text(if (i % 2 == 0) "assistant" else "user", "turn $i " + "x".repeat(200))) }
        val out = Handoff.build(msgs)!!
        assertTrue(out.length < 26_000)
        assertTrue(out.contains("User: FIRST"))
        assertTrue(out.contains("[earlier messages omitted]"))
        assertTrue(out.contains("turn 399 "))
        assertFalse(out.contains("turn 1 "))
        // Order is kept: the opening message comes before the latest one.
        assertTrue(out.indexOf("FIRST") < out.indexOf("turn 399 "))
        assertEquals(1, Regex("""\[earlier messages omitted]""").findAll(out).count())
    }
}
