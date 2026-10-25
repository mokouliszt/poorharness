package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.bridge.arr
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.bridge.obj
import dev.mokouliszt.poordsh.bridge.objects
import dev.mokouliszt.poordsh.bridge.str
import org.json.JSONArray
import org.json.JSONObject

/**
 * opencode's question tool (docs/adr/questions.md) in the UI's shape: the
 * `question.asked` request, and the tool's part in a chat's history.
 */
object OpencodeQuestions {
    /**
     * The tool's call as asked and answered: "asking" while it waits, "answered",
     * or "dismissed" (the user stopped instead of answering).
     */
    fun part(id: String, state: JSONObject): JSONObject = jsonOf(
        "id" to id,
        "kind" to "question",
        "status" to when (state.optString("status")) {
            "completed" -> "answered"
            "error" -> "dismissed"
            else -> "asking"
        },
        "questions" to list(state.obj("input")?.arr("questions") ?: JSONArray(), state.obj("metadata")?.optJSONArray("answers")),
    )

    /** [answers]: one list of labels (or typed text) per question, once replied. */
    fun list(questions: JSONArray, answers: JSONArray?): JSONArray = JSONArray(
        questions.objects().mapIndexed { i, q ->
            jsonOf(
                "header" to q.optString("header"),
                "question" to q.optString("question"),
                "options" to JSONArray(q.arr("options").objects().map {
                    jsonOf("label" to it.optString("label"), "description" to it.str("description"))
                }),
                "multiple" to q.optBoolean("multiple", false),
                // opencode lets the user type an answer of their own unless the model says not to.
                "custom" to q.optBoolean("custom", true),
                "answer" to answers?.optJSONArray(i),
            )
        },
    )
}
