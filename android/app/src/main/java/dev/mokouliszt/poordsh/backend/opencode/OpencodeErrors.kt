package dev.mokouliszt.poordsh.backend.opencode

/** What a chat shows for an error opencode reports on a session. */
object OpencodeErrors {
    /** A code the web UI words in the person's language (docs/bridge-protocol.md, "Errors"). */
    const val MODEL_GONE = "model_gone"

    /** Verified with opencode 1.19.0. If the wording changes, the chat shows opencode's own. */
    private const val MODEL_NOT_FOUND = "Model not found:"
    private const val STACK_TRACE = "\n    at "

    /**
     * opencode sends some errors with their stack trace. One for a model it does not
     * offer (the provider retired it after the picker last listed it) becomes
     * [MODEL_GONE]: the fix is picking another model, not its id list.
     */
    fun forChat(message: String): String =
        if (MODEL_NOT_FOUND in message) MODEL_GONE else message.substringBefore(STACK_TRACE)
}
