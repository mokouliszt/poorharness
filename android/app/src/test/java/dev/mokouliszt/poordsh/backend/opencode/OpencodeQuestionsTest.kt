package dev.mokouliszt.poordsh.backend.opencode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/adr/questions.md, with the shapes opencode 1.19.0 sent. */
class OpencodeQuestionsTest {
    private val input = JSONObject(
        """{"questions":[
          {"header":"Color","question":"Which color do you prefer?","options":[{"label":"red","description":"The color red"},{"label":"blue","description":"The color blue"}]},
          {"header":"Toppings","question":"Which toppings?","multiple":true,"custom":false,"options":[{"label":"cheese","description":"Add cheese"},{"label":"ham","description":"Add ham"}]}
        ]}""",
    )

    @Test
    fun anAnsweredCallKeepsItsAnswers() {
        val state = JSONObject().put("status", "completed").put("input", input)
            .put("metadata", JSONObject("""{"answers":[["red"],["cheese","ham"]],"truncated":false}"""))
        val part = OpencodeQuestions.part("prt_1", state)
        assertEquals("question", part.getString("kind"))
        assertEquals("answered", part.getString("status"))
        val qs = part.getJSONArray("questions")
        assertEquals("Color", qs.getJSONObject(0).getString("header"))
        assertFalse(qs.getJSONObject(0).getBoolean("multiple"))
        assertTrue(qs.getJSONObject(0).getBoolean("custom"))
        assertEquals("red", qs.getJSONObject(0).getJSONArray("answer").getString(0))
        assertTrue(qs.getJSONObject(1).getBoolean("multiple"))
        assertFalse(qs.getJSONObject(1).getBoolean("custom"))
        assertEquals(2, qs.getJSONObject(1).getJSONArray("answer").length())
        assertEquals("The color blue", qs.getJSONObject(0).getJSONArray("options").getJSONObject(1).getString("description"))
    }

    @Test
    fun aDismissedOrWaitingCallHasNoAnswers() {
        val dismissed = OpencodeQuestions.part("p", JSONObject().put("status", "error").put("input", input).put("error", "The user dismissed this question"))
        assertEquals("dismissed", dismissed.getString("status"))
        assertTrue(dismissed.getJSONArray("questions").getJSONObject(0).isNull("answer"))
        assertEquals("asking", OpencodeQuestions.part("p", JSONObject().put("status", "running").put("input", input)).getString("status"))
        assertEquals("asking", OpencodeQuestions.part("p", JSONObject().put("status", "pending")).getString("status"))
    }
}
