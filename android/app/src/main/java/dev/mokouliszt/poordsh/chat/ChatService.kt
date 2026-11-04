package dev.mokouliszt.poordsh.chat

import dev.mokouliszt.poordsh.attachments.AttachmentNote
import dev.mokouliszt.poordsh.attachments.AttachmentService
import dev.mokouliszt.poordsh.backend.AgentBackend
import dev.mokouliszt.poordsh.backend.EventBus
import dev.mokouliszt.poordsh.backend.Ids
import dev.mokouliszt.poordsh.backend.ModelRef
import dev.mokouliszt.poordsh.backend.Place
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.bridge.objects
import dev.mokouliszt.poordsh.bridge.str
import dev.mokouliszt.poordsh.engine.EngineManager
import dev.mokouliszt.poordsh.git.CloudShell
import dev.mokouliszt.poordsh.git.GitRepos
import dev.mokouliszt.poordsh.git.PhoneShell
import dev.mokouliszt.poordsh.git.SandboxShell
import dev.mokouliszt.poordsh.phone.PhoneSandboxManager
import dev.mokouliszt.poordsh.outputs.CloudOutputs
import dev.mokouliszt.poordsh.outputs.OutputService
import dev.mokouliszt.poordsh.outputs.Outputs
import dev.mokouliszt.poordsh.outputs.PhoneOutputs
import dev.mokouliszt.poordsh.plugins.PluginStore
import dev.mokouliszt.poordsh.runtime.Logs
import dev.mokouliszt.poordsh.sandbox.CloudSandboxes
import dev.mokouliszt.poordsh.sandbox.RemoteSandbox
import dev.mokouliszt.poordsh.skills.FolderSync
import dev.mokouliszt.poordsh.skills.SkillStore
import dev.mokouliszt.poordsh.skills.SkillSync
import dev.mokouliszt.poordsh.tensorlake.SandboxManager
import dev.mokouliszt.poordsh.tensorlake.TensorlakeSandboxes
import dev.mokouliszt.poordsh.upstash.BoxApi
import dev.mokouliszt.poordsh.upstash.BoxSandboxes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Chats as the UI sees them. Turns engine events (keyed by engine session) into
 * UI events keyed by chat, and copies each finished turn into [ChatStore].
 *
 * Each chat works in its own sandbox ([Place]): an Upstash box, a Tensorlake
 * sandbox or a device jail. A chat whose last session belongs to an engine this
 * build no longer has (Codex, before 0.11) goes on in opencode with its history.
 */
class ChatService(
    private val store: ChatStore,
    private val engines: EngineManager,
    private val tensorlake: TensorlakeSandboxes,
    private val boxes: BoxSandboxes,
    private val phoneSandboxes: PhoneSandboxManager,
    private val hasTensorlakeKey: () -> Boolean,
    private val hasBoxKey: () -> Boolean,
    private val skills: SkillStore,
    private val plugins: PluginStore,
    private val outputs: OutputService,
) {
    /** Set after construction (it asks this service where a chat's files go). */
    lateinit var attachments: AttachmentService

    /** Set after construction (it reaches sandboxes through [withShell]). */
    var git: GitRepos? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _ui = MutableSharedFlow<JSONObject>(extraBufferCapacity = 1024)

    /** Everything the WebView should receive. */
    val uiEvents: SharedFlow<JSONObject> = _ui

    private val busy = ConcurrentHashMap.newKeySet<String>()
    /** Chats whose message is waiting for its sandbox or engine to start. */
    private val sending = ConcurrentHashMap.newKeySet<String>()
    /** Chats stopped by the user while waiting for that start. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val turnSeq = ConcurrentHashMap<String, AtomicLong>()
    /** chat id -> background subagent sessions still working. */
    private val subagents = ConcurrentHashMap<String, MutableSet<String>>()
    private val copyLocks = ConcurrentHashMap<String, Mutex>()
    /** "<what> <chat id>" -> "<sandbox id> <digest>" last synced in this process (skills, plugins). */
    private val foldersSynced = ConcurrentHashMap<String, String>()
    /** Where a workspace waits while it moves out of Box; set by the app. */
    var moveStaging: File = File(System.getProperty("java.io.tmpdir"), "poordsh-move")

    init {
        engines.onStarted = { afterStart(it) }
        scope.launch { EventBus.events.collect { route(it) } }
        scope.launch { deletePendingSandboxes() }
        // A box the app left awake when it was killed is paused now, not by Box an hour later.
        if (hasBoxKey()) boxes.pauseStrays()
        // Making room in Box (archiving another chat's box) can take a minute; the chat says so.
        boxes.onStage = { chatId, s -> stage(chatId, s ?: "sandbox") }
    }

    fun emitUi(type: String, payload: JSONObject = JSONObject()) {
        payload.put("type", type)
        _ui.tryEmit(payload)
    }

    fun list(): JSONArray = JSONArray().apply {
        store.list().forEach { put(it.put("busy", it.getString("id") in busy)) }
    }

    fun create(): JSONObject = metaOf(store.create())

    fun exists(chatId: String) = store.exists(chatId)

    fun title(chatId: String) = store.title(chatId)

    /** Where a chat runs; the Files bridge reads cloud sandboxes or the device jail accordingly. */
    fun placeOf(chatId: String): Place = store.place(chatId)

    /** Where a chat's files will be: its fixed place once it has run, otherwise where it would start. */
    fun placeFor(chatId: String?): Place =
        if (chatId != null && hasRun(chatId)) store.place(chatId) else newPlace()

    /** The service that holds a cloud place's sandboxes; null for the device. */
    fun cloudOf(place: Place): CloudSandboxes? = when (place) {
        Place.BOX -> boxes
        Place.CLOUD -> tensorlake
        Place.PHONE -> null
    }

    /** Whether the key a place needs is set (the device needs none). */
    fun hasKeyFor(place: Place): Boolean = when (place) {
        Place.BOX -> hasBoxKey()
        Place.CLOUD -> hasTensorlakeKey()
        Place.PHONE -> true
    }

    /** Where a new chat starts (docs/adr/box-first.md): Upstash Box, then Tensorlake, then the device. */
    private fun newPlace(): Place = if (hasBoxKey() && boxes.allowsNewChats()) Place.BOX else fallbackPlace()

    /** Where a chat goes when Box is not an option. */
    private fun fallbackPlace(): Place = if (hasTensorlakeKey()) Place.CLOUD else Place.PHONE

    fun hasRun(chatId: String) = store.exists(chatId) && store.messages(chatId).length() > 0

    suspend fun messages(chatId: String): JSONArray {
        // Mid-turn, the engine has newer messages than the copy.
        if (chatId in busy) store.currentSegment(chatId)?.let { copyTurn(chatId, it.sessionId) }
        return store.messagesWithOutputs(chatId)
    }

    suspend fun send(chatId: String, text: String, model: ModelRef, modelName: String?, files: List<String> = emptyList()) {
        if (!store.exists(chatId)) throw IOException("chat not found")
        if (text.isBlank() && files.isEmpty()) throw IOException("empty message")
        // The place is fixed on the first message (newPlace); Box's use this month is
        // read first, so a nearly spent allowance sends the chat elsewhere.
        if (store.messages(chatId).length() == 0) {
            if (hasBoxKey()) runCatching { boxes.usage() }.onFailure { Logs.box.add("usage not read: ${it.message}") }
            store.setPlace(chatId, newPlace())
        }
        var place = store.place(chatId)
        if (place == Place.CLOUD && !hasTensorlakeKey()) throw IOException("tensorlake_key_missing")
        if (place == Place.BOX && !hasBoxKey()) throw IOException("box_key_missing")
        val engine = model.backend
        // Recorded before anything starts, so a chat whose start fails still has a name.
        if (store.title(chatId).isBlank()) {
            val first = text.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: attachments.nameOf(files.first())
            store.setTitle(chatId, first.take(60))
        }
        store.setModel(chatId, "${model.backend}:${model.model}", model.effort)
        store.touch(chatId)
        emitUi("sessions.upsert", jsonOf("session" to metaOf(chatId)))
        val token = "send:$chatId"
        sending.add(chatId)
        sbAcquire(chatId, place, token)
        try {
            stage(chatId, "sandbox")
            // Cloud: create, restore or wake the box or sandbox (fresh => its files are gone).
            // Phone: a device jail whose files persist on disk, so never a reset.
            var cloud: RemoteSandbox? = null
            var reset = false
            if (place == Place.BOX) {
                try {
                    val ready = boxes.ensure(chatId)
                    cloud = ready.sandbox
                    reset = ready.fresh && store.messages(chatId).length() > 0
                } catch (e: BoxApi.UsageLimit) {
                    // Box refuses this chat for the month: it goes on in the next place.
                    val to = fallbackPlace()
                    sbRelease(chatId, place, token)
                    sbAcquire(chatId, to, token)
                    reset = !moveOutOfBox(chatId, to)
                    place = to
                } catch (e: BoxSandboxes.Full) {
                    // Ten boxes and none to archive: a new chat starts in the next place instead.
                    if (store.messages(chatId).length() > 0) throw e
                    val to = fallbackPlace()
                    Logs.box.add("$chatId: no room in Upstash Box; starting in ${to.id}")
                    sbRelease(chatId, place, token)
                    sbAcquire(chatId, to, token)
                    store.setPlace(chatId, to)
                    place = to
                }
            }
            if (place == Place.PHONE) {
                phoneSandboxes.dirs(chatId)
            } else if (place == Place.CLOUD) {
                val ready = tensorlake.ensure(chatId)
                cloud = ready.sandbox
                // A chat just moved out of Box had its sandbox made, and its files copied in, by the move.
                if (ready.fresh && store.messages(chatId).length() > 0) reset = true
            }
            val skillSet = skills.snapshot()
            syncFolders(chatId, place, cloud, "skills", SkillSync.folders, skillSet, skills::file)
            val pluginSet = plugins.state()
            syncFolders(chatId, place, cloud, "plugins", PluginStore.sync, pluginSet.snapshot, plugins::file)
            makeOutputsFolder(chatId, place, cloud)
            val delivered = if (files.isEmpty()) emptyList() else {
                stage(chatId, "upload")
                attachments.deliver(chatId, place, cloud, files) { p -> stage(chatId, "upload", p) }
            }
            if (cancelled.remove(chatId)) return
            engines.use(engine, token) { backend ->
                if (cancelled.remove(chatId)) return@use // stopped while the engine was starting
                var seg = store.currentSegment(chatId)
                var context: String? = null
                if (seg == null || seg.engine != engine || reset) {
                    // The earlier conversation goes along: from a fresh sandbox, or from a Codex chat.
                    context = Handoff.build(store.messages(chatId), reset = reset)
                    val created = backend.createSession(model, null, chatId, place)
                    seg = ChatStore.Segment(engine, created.getString("id"), modelName)
                    store.addSegment(chatId, seg)
                } else {
                    backend.bindSandbox(seg.sessionId, chatId, place)
                }
                val sent = attachments.sent(delivered, engines.modelInputs("${model.backend}:${model.model}"))
                val note = sent.takeIf { it.isNotEmpty() }?.let { AttachmentNote.build(it) }
                markBusy(chatId)
                emitUi("sessions.upsert", jsonOf("session" to metaOf(chatId)))
                try {
                    backend.send(seg.sessionId, text, model, context, sent, note)
                } catch (e: Exception) {
                    busy.remove(chatId)
                    emitUi("sessions.upsert", jsonOf("session" to metaOf(chatId)))
                    throw e
                }
            }
            attachments.finish(files)
        } finally {
            stage(chatId, null)
            sbRelease(chatId, place, token)
            sending.remove(chatId)
            cancelled.remove(chatId)
        }
    }

    /**
     * Brings the chat's sandbox up to the installed skills or plugins ([what]).
     * Cheap when nothing changed (one small read, skipped altogether when this
     * process already synced the same sandbox); a failure leaves the turn to run
     * with what is there.
     */
    private suspend fun syncFolders(
        chatId: String,
        place: Place,
        cloud: RemoteSandbox?,
        what: String,
        sync: FolderSync,
        snapshot: FolderSync.Snapshot,
        file: (String, String) -> File,
    ) {
        val key = "${cloud?.id ?: "phone"} ${snapshot.digest}"
        if (foldersSynced["$what $chatId"] == key) return
        runCatching {
            withContext(Dispatchers.IO) {
                val target = if (cloud == null) sync.PhoneTarget(phoneSandboxes.dirs(chatId)) else sync.CloudTarget(cloud)
                if (sync.sync(snapshot, file, target)) Logs.app.add("$chatId: $what synced (${snapshot.folders.size})")
            }
        }.onSuccess { foldersSynced["$what $chatId"] = key }
            .onFailure { Logs.app.add("$chatId: $what not synced (${it.message})") }
    }

    private fun sbAcquire(chatId: String, place: Place, token: String) =
        cloudOf(place)?.acquire(chatId, token) ?: phoneSandboxes.acquire(chatId, token)

    private fun sbRelease(chatId: String, place: Place, token: String) =
        cloudOf(place)?.release(chatId, token) ?: phoneSandboxes.release(chatId, token)

    /**
     * Moves a chat Box refuses for usage to [to] (docs/adr/box-first.md): its
     * workspace is copied over when Box still lets the files be read, and the chat's
     * place changes for good. True if the files came along; otherwise the chat goes
     * on in an empty sandbox and the box stays until the chat is deleted.
     */
    private suspend fun moveOutOfBox(chatId: String, to: Place): Boolean {
        stage(chatId, "move")
        Logs.box.add("$chatId: Upstash Box refused it for usage; moving to ${to.id}")
        val staging = File(moveStaging, chatId).apply { deleteRecursively(); mkdirs() }
        try {
            if (!boxes.downloadWorkspace(chatId, staging)) {
                store.setPlace(chatId, to)
                return false
            }
            withContext(Dispatchers.IO) {
                if (to == Place.PHONE) {
                    staging.copyRecursively(phoneSandboxes.dirs(chatId).workspace, overwrite = true)
                } else {
                    val target = tensorlake.ensure(chatId).sandbox
                    val zip = File(moveStaging, "$chatId.zip")
                    try {
                        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
                            staging.walkTopDown().filter { it.isFile }.forEach { f ->
                                out.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(staging).invariantSeparatorsPath))
                                f.inputStream().use { it.copyTo(out) }
                                out.closeEntry()
                            }
                        }
                        val remote = "/tmp/poordsh-move.zip"
                        target.upload(remote, zip)
                        val r = target.run("python3", listOf("-c", UNZIP, remote, SandboxManager.WORKSPACE), timeoutSecs = 600)
                        if (r.exitCode != 0) throw IOException("could not unpack: ${r.stderr.trim().takeLast(200)}")
                    } finally {
                        zip.delete()
                    }
                }
            }
            store.setPlace(chatId, to)
            // The files are across: the box can go and free its slot.
            runCatching { boxes.delete(chatId) }
            Logs.box.add("$chatId: workspace moved to ${to.id}")
            return true
        } catch (e: Exception) {
            Logs.box.add("$chatId: workspace not moved (${e.message})")
            store.setPlace(chatId, to)
            return false
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun abort(chatId: String) {
        if (chatId in sending && chatId !in busy) {
            // Still waiting for the sandbox or engine to start: the message is dropped instead.
            cancelled.add(chatId)
            emitUi("chat.status", jsonOf("sessionId" to chatId, "status" to "idle"))
            return
        }
        val seg = store.currentSegment(chatId) ?: return
        engines.runningBackend(seg.engine)?.abort(seg.sessionId)
    }

    suspend fun delete(chatId: String) {
        val place = store.place(chatId)
        val wasBusy = busy.remove(chatId)
        foldersSynced.keys.removeAll { it.endsWith(" $chatId") }
        git?.forget(chatId)
        subagents.remove(chatId)?.forEach { sbRelease(chatId, place, "turn:$it") }
        for (seg in store.delete(chatId)) {
            // A Codex session (before 0.11) has nothing left to delete: it lived in the sandbox.
            if (seg.engine !in engines.backends) continue
            engines.forgetTurn(seg.engine, seg.sessionId)
            sbRelease(chatId, place, "turn:${seg.sessionId}")
            val running = engines.runningBackend(seg.engine)
            if (wasBusy && running != null) runCatching { running.abort(seg.sessionId) }
            if (running == null || runCatching { running.deleteSession(seg.sessionId) }.isFailure) {
                store.addPendingDelete(seg.engine, seg.sessionId)
            }
        }
        // The sandbox (and the files in it) goes with the chat; so does a box it moved out of.
        scope.launch {
            if (place == Place.PHONE) {
                runCatching { phoneSandboxes.delete(chatId) }
                    .onFailure { Logs.app.add("$chatId: jail delete failed (${it.message})") }
            }
            if (place == Place.CLOUD && hasTensorlakeKey()) deleteIn(Place.CLOUD, chatId)
            if (hasBoxKey()) deleteIn(Place.BOX, chatId)
        }
    }

    private suspend fun deleteIn(place: Place, chatId: String) {
        val cloud = cloudOf(place) ?: return
        runCatching { cloud.delete(chatId) }.onFailure {
            Logs.app.add("$chatId: ${place.id} sandbox delete failed (${it.message}); will retry")
            // Tensorlake's list holds sandbox names, as it has since 0.2; Box's holds chat ids.
            if (place == Place.BOX) store.addPendingDelete(BOX_PENDING, chatId)
            else store.addPendingDelete(TENSORLAKE_PENDING, SandboxManager.nameFor(chatId))
        }
    }

    private suspend fun deletePendingSandboxes() {
        if (hasTensorlakeKey()) {
            for (name in store.takePendingDeletes(TENSORLAKE_PENDING)) {
                runCatching { tensorlake.manager.delete(name) }.onFailure { store.addPendingDelete(TENSORLAKE_PENDING, name) }
            }
        }
        if (hasBoxKey()) {
            for (chatId in store.takePendingDeletes(BOX_PENDING)) {
                runCatching { boxes.delete(chatId) }.onFailure { store.addPendingDelete(BOX_PENDING, chatId) }
            }
        }
    }

    private fun stage(chatId: String, stage: String?, progress: Double? = null) =
        emitUi("chat.stage", jsonOf("sessionId" to chatId, "stage" to stage, "progress" to progress))

    // --- engine events -----------------------------------------------------------

    private fun route(e: JSONObject) {
        when (e.optString("type")) {
            "sessions.upsert" -> e.optJSONObject("session")?.let { adoptTitle(it.optString("id"), it.optString("title")) }
            "sessions.rename" -> adoptTitle(e.optString("sessionId"), e.optString("title"))
            "sessions.remove" -> Unit // the app decides what is deleted
            "chat.stage" -> e.str("chatId")?.let { stage(it, e.str("stage")) }
            "backend.resync" -> scope.launch {
                busy.toList().forEach { chatId ->
                    store.currentSegment(chatId)?.takeIf { it.engine == e.optString("backend") }
                        ?.let { copyTurn(chatId, it.sessionId) }
                }
                _ui.tryEmit(e)
            }
            else -> {
                val sid = e.str("sessionId")
                if (sid == null) {
                    _ui.tryEmit(e)
                    return
                }
                val chatId = store.chatOfSession(sid) ?: run {
                    // A subagent's session reports under its own id; find the chat that owns it.
                    val owner = engines.backends[Ids.split(sid).first]?.rootSession(sid)?.let { store.chatOfSession(it) }
                    if (owner != null) onSubagentEvent(owner, sid, e)
                    return // otherwise a session this app did not create
                }
                // Events are shared with other collectors; never mutate them.
                val out = JSONObject(e.toString()).put("sessionId", chatId)
                // Live messages carry their segment like stored ones, so the UI can mark engine switches.
                if (out.optString("type") == "chat.message") {
                    store.segmentOf(sid)?.let { seg ->
                        out.optJSONObject("message")?.put("segment", seg.sessionId)?.put("engine", seg.engine)
                            ?.put("segmentModel", seg.model)
                    }
                    // Files handed over at the end of a turn stay under its answer.
                    out.optJSONObject("message")?.let { store.withOutputs(chatId, it) }
                }
                if (e.optString("type") == "chat.status") onStatus(chatId, sid, out) else _ui.tryEmit(out)
            }
        }
    }

    private fun onStatus(chatId: String, sid: String, out: JSONObject) {
        val place = store.place(chatId)
        if (out.optString("status") == "busy") {
            sbAcquire(chatId, place, "turn:$sid")
            markBusy(chatId)
            _ui.tryEmit(out)
            return
        }
        val seq = turnSeq[chatId]?.get()
        scope.launch {
            copyTurn(chatId, sid)
            // While the turn still holds the sandbox: what the agent put in its outputs folder,
            // and what it pushed to the repositories it opened.
            collectOutputs(chatId, sid, place)
            saveRepos(chatId)
            sbRelease(chatId, place, "turn:$sid")
            // A new turn may have started while copying; its idle will come later.
            if (seq != null && turnSeq[chatId]?.get() != seq) return@launch
            busy.remove(chatId)
            _ui.tryEmit(out)
            if (store.exists(chatId)) emitUi("sessions.upsert", jsonOf("session" to metaOf(chatId)))
        }
    }

    /** Writes back the chat's repositories ([GitRepos.save]); a failure leaves them to the next turn. */
    private suspend fun saveRepos(chatId: String) {
        val g = git ?: return
        if (!g.has(chatId) || !g.available) return
        stage(chatId, "repos")
        try {
            runCatching { g.save(chatId) }.onFailure { Logs.app.add("$chatId: repositories not saved (${it.message})") }
        } finally {
            stage(chatId, null)
        }
    }

    /** Runs [block] on [chatId]'s sandbox, holding it up meanwhile: its box, its Tensorlake sandbox or its device jail. */
    suspend fun withShell(chatId: String, block: suspend (SandboxShell) -> Unit) {
        if (!store.exists(chatId)) throw IOException("chat not found")
        val place = store.place(chatId)
        val token = "git:" + java.util.UUID.randomUUID()
        sbAcquire(chatId, place, token)
        try {
            withContext(Dispatchers.IO) {
                val cloud = cloudOf(place)
                if (cloud == null) block(PhoneShell(phoneSandboxes, chatId, phoneSandboxes.dirs(chatId)))
                else block(CloudShell(cloud.ensure(chatId).sandbox))
            }
        } finally {
            sbRelease(chatId, place, token)
        }
    }

    /** The chat whose sandbox the opencode plugin names: "phone:<chat>", "box:<chat>", or a Tensorlake sandbox name. */
    fun chatOfSandbox(sandbox: String): String? = when {
        sandbox.startsWith("phone:") -> sandbox.removePrefix("phone:").takeIf { store.exists(it) }
        sandbox.startsWith("box:") -> sandbox.removePrefix("box:").takeIf { store.exists(it) }
        else -> store.list().map { it.getString("id") }.firstOrNull { SandboxManager.nameFor(it) == sandbox }
    }

    /** The outputs folder the engines are told about, made once per sandbox in this process. */
    private suspend fun makeOutputsFolder(chatId: String, place: Place, cloud: RemoteSandbox?) {
        val key = "outputs-dir $chatId"
        val where = cloud?.id ?: "phone"
        if (foldersSynced[key] == where) return
        runCatching {
            withContext(Dispatchers.IO) {
                if (place == Place.PHONE) {
                    File(phoneSandboxes.dirs(chatId).workspace, Outputs.DIR.removePrefix("${SandboxManager.WORKSPACE}/")).mkdirs()
                } else {
                    cloud!!.run("mkdir", listOf("-p", Outputs.DIR))
                }
            }
        }.onSuccess { foldersSynced[key] = where }
    }

    /**
     * Hands over what is new in the chat's outputs folder ([Outputs]) and shows it
     * under the turn's last answer. A failure leaves the chat as it was.
     */
    private suspend fun collectOutputs(chatId: String, sid: String, place: Place) {
        runCatching {
            withContext(Dispatchers.IO) {
                val cloud = cloudOf(place)
                val source = if (cloud == null) {
                    PhoneOutputs(File(phoneSandboxes.dirs(chatId).workspace, Outputs.DIR.removePrefix("${SandboxManager.WORKSPACE}/")))
                } else {
                    if (!hasKeyFor(place)) return@withContext
                    CloudOutputs(cloud.ensure(chatId).sandbox)
                }
                val handed = try {
                    outputs.collect(chatId, source) { stage(chatId, "outputs") }
                } finally {
                    stage(chatId, null)
                }
                if (handed.isEmpty()) return@withContext
                Logs.app.add("$chatId: handed over ${handed.size} output(s)")
                val messageId = store.messages(chatId).objects()
                    .lastOrNull { it.optString("role") == "assistant" && it.optString("segment") == sid }?.optString("id").orEmpty()
                val files = handed.map {
                    jsonOf("name" to it.name, "size" to it.size, "shelf" to it.shelf, "chatId" to chatId, "error" to it.error)
                }
                store.addDelivery(chatId, messageId, files)?.let { message ->
                    store.segmentOf(sid)?.let { seg -> message.put("segment", seg.sessionId).put("engine", seg.engine).put("segmentModel", seg.model) }
                    emitUi("chat.message", jsonOf("sessionId" to chatId, "message" to message))
                }
            }
        }.onFailure { Logs.app.add("$chatId: outputs not handed over (${it.message})") }
    }

    /**
     * A background subagent of [chatId]. Its own messages stay out of the chat (the
     * parent's task tool reports them); its approvals are the chat's to answer; while
     * it works it keeps the chat's sandbox up, and the UI shows how many are working.
     */
    private fun onSubagentEvent(chatId: String, sid: String, e: JSONObject) {
        when (e.optString("type")) {
            "approval.request", "question.request" -> _ui.tryEmit(JSONObject(e.toString()).put("sessionId", chatId))
            "chat.status" -> {
                val set = subagents.getOrPut(chatId) { ConcurrentHashMap.newKeySet() }
                val place = store.place(chatId)
                val changed = if (e.optString("status") == "busy") {
                    set.add(sid).also { if (it) sbAcquire(chatId, place, "turn:$sid") }
                } else {
                    set.remove(sid).also { removed ->
                        if (!removed) return@also
                        // A background subagent that ends after the chat's turn may have pushed too.
                        scope.launch {
                            if (chatId !in busy) saveRepos(chatId)
                            sbRelease(chatId, place, "turn:$sid")
                        }
                    }
                }
                if (changed) emitUi("chat.background", jsonOf("sessionId" to chatId, "count" to set.size))
            }
        }
    }

    private fun markBusy(chatId: String) {
        busy.add(chatId)
        turnSeq.getOrPut(chatId) { AtomicLong() }.incrementAndGet()
    }

    /** Copies the engine's messages for a segment into the store. */
    private suspend fun copyTurn(chatId: String, sid: String) {
        copyLocks.getOrPut(chatId) { Mutex() }.withLock {
            val backend = engines.runningBackend(Ids.split(sid).first) ?: return
            val msgs = withTimeoutOrNull(10_000) { runCatching { backend.messages(sid) }.getOrNull() } ?: return
            store.replaceSegmentMessages(chatId, sid, msgs)
        }
    }

    /** opencode names sessions itself; that name is better than the first line of the prompt. */
    private fun adoptTitle(sid: String, title: String) {
        val chatId = store.chatOfSession(sid) ?: return
        if (title.isBlank() || title.startsWith("New session") || !store.isFirstSegment(sid)) return
        if (store.title(chatId) == title) return
        store.setTitle(chatId, title)
        emitUi("sessions.upsert", jsonOf("session" to metaOf(chatId)))
    }

    private fun metaOf(chatId: String): JSONObject =
        (store.meta(chatId) ?: JSONObject().put("id", chatId)).put("busy", chatId in busy)

    // --- after an engine starts ----------------------------------------------------

    private suspend fun afterStart(b: AgentBackend) {
        store.takePendingDeletes(b.id).forEach { runCatching { b.deleteSession(it) } }
        if (store.markImported(b.id)) importExisting(b)
    }

    /** Chats from before 0.1.2 lived only in the engines; bring them into the store once. */
    private suspend fun importExisting(b: AgentBackend) {
        val sessions = runCatching { b.sessions() }.getOrNull() ?: return
        var imported = 0
        for (s in sessions.objects()) {
            val sid = s.optString("id")
            if (sid.isEmpty() || store.chatOfSession(sid) != null) continue
            val now = System.currentTimeMillis()
            val id = store.create(s.optString("title"), s.optLong("createdAt", now), s.optLong("updatedAt", now))
            store.addSegment(id, ChatStore.Segment(b.id, sid, null))
            runCatching { b.messages(sid) }.getOrNull()?.let { store.replaceSegmentMessages(id, sid, it, touch = false) }
            imported++
        }
        if (imported > 0) {
            Logs.app.add("imported $imported ${b.id} chat(s)")
            emitUi("sessions.changed")
        }
    }

    companion object {
        /** Pending-delete lists for sandboxes whose deletion failed. */
        private const val TENSORLAKE_PENDING = "tensorlake"
        private const val BOX_PENDING = "box"

        /** `<zip> <dir>`: unpacks a moved workspace (python3, which every sandbox has). */
        private const val UNZIP = "import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2]); import os; os.remove(sys.argv[1])"
    }
}
