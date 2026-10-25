package dev.mokouliszt.poordsh.backend

import dev.mokouliszt.poordsh.bridge.jsonOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent engine: opencode, on the phone, its tools in the chat's sandbox (a
 * Tensorlake sandbox or a device jail). Kept as an interface so the chat layer
 * does not depend on opencode's HTTP API.
 *
 * Everything crossing to the UI is normalized into the shapes documented in
 * docs/bridge-protocol.md, so the UI does not care which engine produced it.
 */
interface AgentBackend {
    val id: String
    val state: StateFlow<BackendState>

    suspend fun start()
    suspend fun stop()

    /** Models this engine can run right now, in the normalized model shape. */
    suspend fun models(discover: Boolean = false): JSONArray
    suspend fun sessions(): JSONArray

    /** A new engine session for chat [chatId]; its work happens in that chat's sandbox ([place]). */
    suspend fun createSession(model: ModelRef, title: String?, chatId: String, place: Place): JSONObject

    /** Points an existing session at a chat's sandbox (before each prompt; cheap and idempotent). */
    suspend fun bindSandbox(sessionId: String, chatId: String, place: Place) {}

    /**
     * The top-level session a subagent's session belongs to, or null if [sessionId]
     * is not a known subagent. Subagents (opencode's background tasks) report under
     * their own session ids; the chat that owns them is found through this.
     */
    fun rootSession(sessionId: String): String? = null
    suspend fun messages(sessionId: String): JSONArray
    /**
     * Sends [text]. [context] is the earlier conversation when a chat starts a new
     * session (a fresh sandbox, or a chat from Codex before 0.11); it reaches the
     * model but is never shown (see [ContextTag]).
     * [files] are already in the chat's sandbox; [note] (an
     * [dev.mokouliszt.poordsh.attachments.AttachmentNote]) tells the model where.
     */
    suspend fun send(
        sessionId: String,
        text: String,
        model: ModelRef,
        context: String? = null,
        files: List<SentFile> = emptyList(),
        note: String? = null,
    )

    suspend fun abort(sessionId: String)
    suspend fun deleteSession(sessionId: String)

    /** [decision]: "once", "session" or "deny". */
    suspend fun reply(requestId: String, decision: String)

    /**
     * Answers a question the agent asked (docs/adr/questions.md): one list of chosen
     * labels or typed text per question, in order; null dismisses it, which ends the turn.
     */
    suspend fun answer(requestId: String, answers: List<List<String>>?)

    /** Whether the engine should restart itself after a crash or hang (false while idle). */
    fun setRecoveryPolicy(shouldRecover: () -> Boolean) {}
}

/**
 * Where a chat's work runs. Decided on the chat's first message and kept for its
 * life: with a Tensorlake key, [CLOUD] (a Tensorlake sandbox); without one,
 * [PHONE] (a per-chat PRoot jail on the device). Every provider in a chat shares
 * its one place, so switching model mid-chat keeps the files.
 */
enum class Place {
    CLOUD, PHONE;

    val id get() = name.lowercase()

    companion object {
        fun of(id: String?) = if (id == PHONE.id) PHONE else CLOUD
    }
}

/**
 * A file sent with a message, already in the chat's sandbox at [path]. [inline],
 * when set, is how it also reaches the model directly (an image or a document
 * the model takes).
 */
data class SentFile(val name: String, val path: String, val size: Long, val mime: String, val inline: Inline? = null) {
    /** [file]: the bytes to embed. */
    data class Inline(val mime: String, val file: java.io.File)
}

/** Marks the hidden conversation context written by chat/Handoff.kt. */
object ContextTag {
    const val OPEN = "<poordsh-context>"
    const val CLOSE = "</poordsh-context>"
    fun matches(text: String) = text.startsWith(OPEN)
}

data class BackendState(val phase: Phase, val detail: String? = null) {
    enum class Phase { STOPPED, STARTING, READY, FAILED }

    fun toJson() = jsonOf("phase" to phase.name.lowercase(), "detail" to detail)
}

/** A model choice from the UI: "<backend>:<model id>" plus an optional reasoning level. */
data class ModelRef(val backend: String, val model: String, val effort: String?) {
    /** For opencode, [model] is "<provider>/<model>". */
    val provider: String get() = model.substringBefore('/', "")
    val modelOnly: String get() = model.substringAfter('/')

    companion object {
        fun parse(id: String, effort: String?): ModelRef {
            val backend = id.substringBefore(':')
            return ModelRef(backend, id.substringAfter(':'), effort?.takeIf { it.isNotBlank() })
        }
    }
}

/** Events pushed to the UI. Dropped when no UI is attached; the UI re-reads state on attach. */
object EventBus {
    private val flow = MutableSharedFlow<JSONObject>(extraBufferCapacity = 1024)
    val events: SharedFlow<JSONObject> = flow

    fun emit(type: String, payload: JSONObject = JSONObject()) {
        payload.put("type", type)
        flow.tryEmit(payload)
    }
}

/** Composite ids keep sessions from both engines in one list: "<backend>:<raw id>". */
object Ids {
    fun session(backend: String, raw: String) = "$backend:$raw"
    fun split(id: String): Pair<String, String> = id.substringBefore(':') to id.substringAfter(':')
}
