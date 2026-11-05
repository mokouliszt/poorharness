package dev.mokouliszt.poordsh.modal

import org.json.JSONObject
import java.io.File

/** What [ModalSandboxes] and [ModalSandbox] need of [ModalAgent]; tests stand in for Modal with their own. */
interface ModalClient {
    /** Runs [op] in the helper and returns its reply; a refusal is a [ModalAgent.ModalException]. */
    suspend fun call(op: String, args: JSONObject = JSONObject(), timeoutMs: Long = ModalAgent.CALL_TIMEOUT_MS): JSONObject

    /** Whether sandboxes are up, so the helper should stay. */
    var keepUp: () -> Boolean

    /** A host file the helper sees at a guest path, for copying files in and out of sandboxes. */
    fun ioFile(name: String): Pair<File, String>
}
