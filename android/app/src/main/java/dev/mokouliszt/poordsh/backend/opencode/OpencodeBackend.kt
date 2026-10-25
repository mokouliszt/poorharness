package dev.mokouliszt.poordsh.backend.opencode

import dev.mokouliszt.poordsh.attachments.AttachmentNote
import dev.mokouliszt.poordsh.backend.AgentBackend
import dev.mokouliszt.poordsh.backend.BackendState
import dev.mokouliszt.poordsh.backend.BackendState.Phase
import dev.mokouliszt.poordsh.backend.ContextTag
import dev.mokouliszt.poordsh.backend.EngineOptions
import dev.mokouliszt.poordsh.backend.EventBus
import dev.mokouliszt.poordsh.backend.Ids
import dev.mokouliszt.poordsh.backend.ModelRef
import dev.mokouliszt.poordsh.backend.Place
import dev.mokouliszt.poordsh.backend.SentFile
import dev.mokouliszt.poordsh.phone.PhoneSandboxServer
import dev.mokouliszt.poordsh.bridge.arr
import dev.mokouliszt.poordsh.bridge.clip
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.bridge.long
import dev.mokouliszt.poordsh.bridge.obj
import dev.mokouliszt.poordsh.bridge.objects
import dev.mokouliszt.poordsh.bridge.str
import dev.mokouliszt.poordsh.plugins.PluginStore
import dev.mokouliszt.poordsh.runtime.GuestProcess
import dev.mokouliszt.poordsh.runtime.Logs
import dev.mokouliszt.poordsh.runtime.Proot
import dev.mokouliszt.poordsh.runtime.RuntimePaths
import dev.mokouliszt.poordsh.runtime.Tools
import dev.mokouliszt.poordsh.secrets.SecretStore
import dev.mokouliszt.poordsh.settings.Settings
import dev.mokouliszt.poordsh.tensorlake.SandboxManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.io.IOException
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * opencode, run as `opencode serve` on a loopback port inside the guest.
 *
 * Its tools never touch the phone: the official tensorlake-opencode plugin
 * provides bash, file and search tools that run in a Tensorlake sandbox, and
 * PoorHarness's plugin ([SANDBOX_PLUGIN]) routes them to the chat's sandbox (or
 * its device jail), adds the approval step, and refuses to run anything when
 * the sandbox tools are missing. Every other tool is denied unless listed in
 * [OpencodeConfig]. opencode itself stays on the phone so OpenCode Zen's free
 * tier sees the phone's own address. Web search is always offered.
 *
 * The server is protected with a random per-launch password (HTTP basic auth),
 * so other apps on the device cannot drive it even though loopback is shared.
 * Models come from opencode's own catalog (models.dev), so new models show up
 * without an app update.
 */
class OpencodeBackend(
    private val paths: RuntimePaths,
    private val proot: Proot,
    private val tools: Tools,
    private val settings: Settings,
    private val secrets: SecretStore,
    private val tensorlakePluginVersion: () -> String?,
    private val sandboxPlugin: () -> ByteArray,
    /** The script the plugin runs in the sandbox for webfetch and websearch (assets/opencode/poordsh-web.py). */
    private val webScript: () -> ByteArray = { ByteArray(0) },
    private val options: () -> Set<String>,
    private val phone: PhoneSandboxServer,
    /** Installed skill names ([dev.mokouliszt.poordsh.skills.SkillStore]); read at each start. */
    private val skills: () -> List<String> = { emptyList() },
    /** Installed plugins ([PluginStore]); what opencode can use of them is read at each start ([OpencodePlugins]). */
    private val plugins: () -> PluginStore.State? = { null },
    /** A bucket is connected, so the repo tool is offered (docs/adr/git-repos.md); read at each start. */
    private val gitRepos: () -> Boolean = { false },
) : AgentBackend {

    override val id = ID
    private val _state = MutableStateFlow(BackendState(Phase.STOPPED))
    override val state: StateFlow<BackendState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: GuestProcess? = null
    @Volatile private var port = 0
    @Volatile private var password = ""
    private var events: EventSource? = null
    private var watchdog: Job? = null
    private var monitor: Job? = null
    private val recoverLock = Mutex()
    // A restart (recover) and a start from EngineManager can overlap; only one may launch a server.
    private val startLock = Mutex()
    private val recoveries = ArrayDeque<Long>()
    @Volatile private var shouldRecover: () -> Boolean = { true }
    /** Whether this launch offers the repo tool; the instructions say so only then. */
    @Volatile private var gitOffered = false
    private val permissionSessions = ConcurrentHashMap<String, String>()
    /** Raw question request id -> raw session id, for questions not yet answered. */
    private val questionSessions = ConcurrentHashMap<String, String>()
    /** Rewrites the config with this run's settings; set at each start. */
    @Volatile private var writeConfig: (() -> Unit)? = null
    /** Tool-name prefixes of this run's plugin MCP servers, so their approvals read as tool calls. */
    @Volatile private var mcpPrefixes: List<String> = emptyList()
    private val freeLists = FreeGateway.ALL.associate { g ->
        g.id to FreeGateway.FreeModels(g, paths.hostPathOf(freeListPath(g.id)), log = { Logs.opencode.add(it) })
    }
    private val nimServed = NvidiaNim.catalog(paths.hostPathOf(NIM_SERVED), log = { Logs.opencode.add(it) })
    private val veniceModels = Venice.catalog(paths.hostPathOf(VENICE_MODELS), log = { Logs.opencode.add(it) })
    /** `provider/model` of the offered models that cannot call tools; they run as the plain [OpencodeConfig.CHAT_AGENT]. */
    @Volatile private var chatOnly: Set<String> = emptySet()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    /** Raw subagent session id -> raw parent id, learned from session.created/updated. */
    private val parents = ConcurrentHashMap<String, String>()

    // Loopback connections are cheap, so none is ever reused: a pooled socket that
    // went stale while the user was typing would otherwise fail the next request.
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", Credentials.basic("opencode", password))
                    .build(),
            )
        }
        .build()
    private val probe = http.newBuilder().readTimeout(HEALTH_TIMEOUT_S, TimeUnit.SECONDS).build()
    // The first request after a start waits for plugins, which the first start downloads.
    private val pluginLoad = http.newBuilder().readTimeout(15, TimeUnit.MINUTES).build()
    // opencode sends server.heartbeat every 10 s; this much silence means the stream is dead.
    private val sseClient = http.newBuilder().readTimeout(SSE_SILENCE_S, TimeUnit.SECONDS).build()

    // --- lifecycle ---------------------------------------------------------

    override suspend fun start() = startLock.withLock { startServer() }

    private suspend fun startServer() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) {
            if (_state.value.phase == Phase.READY) return@withContext
            // Up but not usable (it failed its checks): replaced, never left running beside a new one.
            watchdog?.cancel()
            monitor?.cancel()
            events?.cancel()
            process?.stop()
        }
        _state.value = BackendState(Phase.STARTING)
        val bin = tools.locate(Tools.Tool.OPENCODE)
        port = freePort()
        password = randomHex(24)

        val enabled = options()
        val git = gitRepos()
        gitOffered = git
        // With a key, chats use Tensorlake sandboxes; without one, device jails. A
        // placeholder key keeps the sandbox tools from refusing on the "no credentials"
        // path, since a phone chat's tools are routed to the device, not to Tensorlake.
        val tensorlakeKey = secrets.get(SecretStore.TENSORLAKE_API_KEY)
        val bridge = phone.ensureStarted()
        val plugin = tensorlakePluginVersion()?.let { "tensorlake-opencode@$it" }
        paths.hostPathOf(SANDBOX_PLUGIN).apply { parentFile?.mkdirs(); writeBytes(sandboxPlugin()) }
        paths.hostPathOf(WEB_SCRIPT).writeBytes(webScript())
        // The app's plugin comes last: its tools (tensorlake-opencode's, routed) are the ones used.
        val skillNames = skills()
        val fromPlugins = OpencodePlugins(runCatching { plugins()?.plugins.orEmpty() }
            .onFailure { Logs.opencode.add("plugins not read: ${it.message}") }.getOrDefault(emptyList()).map { it.manifest })
        mcpPrefixes = fromPlugins.mcpServers.map { "${it}_" }
        val jsPlugins = listOfNotNull(plugin, "file://$SANDBOX_PLUGIN")
        writeConfig = {
            OpencodeConfig.write(
                paths, settings.approval, jsPlugins, enabled, skillNames, providerBlocks(), fromPlugins, settings.systemPrompt,
                subagentModel(), git,
                // After providerBlocks(), which learns which offered models cannot call tools.
                chatAgent = chatOnly.isNotEmpty(),
            )
        }
        writeConfig?.invoke()
        paths.emptyHost.mkdirs()
        val launch = randomHex(8)
        val status = paths.hostPathOf(STATUS).apply { delete() }

        val secretEnv = buildMap {
            put("OPENCODE_SERVER_PASSWORD", password)
            put("TENSORLAKE_API_KEY", tensorlakeKey ?: PHONE_KEY_PLACEHOLDER)
            put("POORDSH_PHONE_TOKEN", bridge.token)
        }
        val builder = proot.processBuilder(
            Proot.Spec(
                command = listOf(bin.guestPath, "serve", "--port", port.toString(), "--hostname", "127.0.0.1", "--print-logs"),
                workdir = paths.emptyGuest,
                env = buildMap {
                    put("OPENCODE_CONFIG", OpencodeConfig.GUEST_PATH)
                    put("OPENCODE_DISABLE_AUTOUPDATE", "1")
                    // Web search always on: Parallel (and Exa as a fallback; Parallel
                    // wins when both are set), neither needing a key.
                    put("OPENCODE_ENABLE_PARALLEL", "1")
                    put("OPENCODE_ENABLE_EXA", "1")
                    // opencode 1.19 offers the question tool to `serve` already; set so a new default keeps it.
                    put("OPENCODE_ENABLE_QUESTION_TOOL", "1")
                    // Subagents, unless switched off, keep working in the background.
                    if (EngineOptions.SUBAGENTS in enabled) put("OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS", "1")
                    if (EngineOptions.EXPERIMENTAL_MODELS in enabled) put("OPENCODE_ENABLE_EXPERIMENTAL_MODELS", "1")
                    putAll(TENSORLAKE_SANDBOX_SIZE)
                    put("POORDSH_SANDBOX_MAP", SANDBOX_MAP)
                    put("POORDSH_TENSORLAKE_PLUGIN", plugin.orEmpty())
                    put("POORDSH_STATUS", STATUS)
                    put("POORDSH_LAUNCH", launch)
                    put("POORDSH_PHONE_URL", bridge.url)
                    // Tools offered beyond the defaults: the batch tool when its option is on,
                    // the skill tool when skills are installed (it reads them on the phone),
                    // the repo tool when a bucket is connected, and the tools of plugins'
                    // remote MCP servers.
                    val withSkills = skillNames.isNotEmpty() || fromPlugins.skillNames.isNotEmpty()
                    listOfNotNull(
                        "batch".takeIf { EngineOptions.BATCH_TOOL in enabled },
                        "skill".takeIf { withSkills },
                        REPO_TOOL.takeIf { git },
                    ).takeIf { it.isNotEmpty() }?.let { put("POORDSH_EXTRA_TOOLS", it.joinToString(",")) }
                    if (fromPlugins.mcpServers.isNotEmpty()) put("POORDSH_MCP_SERVERS", fromPlugins.mcpServers.joinToString(","))
                },
                secrets = secretEnv,
                disableSeccomp = settings.disableSeccomp,
            ),
        )
        Logs.opencode.add("opencode ${bin.version} on 127.0.0.1:$port (tensorlake key=${tensorlakeKey != null})")
        val launchedAt = System.currentTimeMillis()
        val failuresBefore = catalogFailures()
        val p = GuestProcess.start("opencode", builder, Logs.opencode, captureStdout = true)
        process = p

        // Bun under PRoot on a phone can take a while on first start.
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) break
            if (probeHealth()) break
            delay(500)
        }
        if (!p.isAlive || !probeHealth()) {
            val code = p.exitCode()
            p.stop()
            process = null
            val detail = "opencode did not start" + (code?.let { " (exit $it)" } ?: "")
            _state.value = BackendState(Phase.FAILED, detail)
            throw IOException(detail)
        }
        // The first provider read fixes the model list for this run: let a catalog fetch land first.
        val catalogDir = File(paths.home, ".cache/opencode")
        if (ModelCatalog.fetching(catalogDir, launchedAt)) {
            val fresh = ModelCatalog.awaitFetch(catalogDir, launchedAt, failed = { catalogFailures() > failuresBefore }, alive = { p.isAlive })
            Logs.opencode.add(if (fresh) "model catalog refreshed" else "model catalog not refreshed; using the cached one")
        }
        try {
            checkSandboxTools(launch, status)
        } catch (e: Exception) {
            p.stop()
            process = null
            _state.value = BackendState(Phase.FAILED, "tensorlake_plugin_unavailable")
            throw e
        }
        connectEvents()
        watch(p)
        monitor(p)
        _state.value = BackendState(Phase.READY, bin.version)
    }

    private fun catalogFailures() = Logs.opencode.snapshot().count { ModelCatalog.FETCH_FAILED in it }

    /**
     * `provider.*` config blocks: for each gateway with a stored key, only its free
     * models are defined and allowed. With a key but no list at all (offline on the
     * first start), the whitelist is empty and none of its models is offered. With a
     * NIM key, only models NIM still serves are allowed; with no list of those,
     * models.dev's stays.
     */
    /**
     * The model picked for subagents, when its provider can run here: Zen's free
     * models need no key, any other provider needs its key. Otherwise subagents
     * stay on the turn's model rather than fail.
     */
    private fun subagentModel(): ModelRef? {
        val id = settings.subagentModel ?: return null
        val ref = ModelRef.parse(id, settings.subagentEffort)
        if (ref.backend != ID || ref.provider.isEmpty()) return null
        if (ref.provider != "opencode" && ref.provider !in OpencodeConfig.storedCredentials(paths)) {
            Logs.opencode.add("subagent model ${ref.model}: no key for ${ref.provider}; subagents use the turn's model")
            return null
        }
        return ref
    }

    private fun providerBlocks(): Map<String, JSONObject> {
        val stored = OpencodeConfig.storedCredentials(paths)
        val blocks = FreeGateway.ALL.filter { it.id in stored }
            .associate { g -> g.id to g.providerConfig(freeLists.getValue(g.id).load().orEmpty()) }
            .toMutableMap()
        if (NvidiaNim.PROVIDER in stored) nimServed.load()?.let { blocks[NvidiaNim.PROVIDER] = NvidiaNim.providerConfig(it) }
        chatOnly = emptySet()
        if (Venice.PROVIDER in stored) veniceModels.load()?.let {
            blocks[Venice.PROVIDER] = Venice.providerConfig(it)
            chatOnly = Venice.chatOnly(it)
        }
        return blocks
    }

    /**
     * Loads opencode's plugins (on the first start this downloads tensorlake-opencode,
     * which can take minutes on a phone) and confirms PoorHarness's plugin put the
     * sandbox tools in place. Without them nothing may run: their stand-ins refuse.
     */
    private suspend fun checkSandboxTools(launch: String, status: File) {
        val slow = scope.launch {
            delay(5_000)
            _state.value = BackendState(Phase.STARTING, "plugin")
        }
        try {
            pluginLoad.newCall(Request.Builder().url(url("/config/providers")).build()).execute().use {
                if (!it.isSuccessful) throw IOException("opencode answered ${it.code} while loading its plugins")
            }
        } finally {
            slow.cancel()
        }
        val st = runCatching { JSONObject(status.readText()) }.getOrNull()
        if (st?.optString("launch") == launch && st.optBoolean("ready")) return
        val why = st?.str("error") ?: "PoorHarness's opencode plugin did not run"
        Logs.opencode.add("sandbox tools unavailable: $why")
        throw IOException("tensorlake_plugin_unavailable")
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        watchdog?.cancel()
        monitor?.cancel()
        events?.cancel()
        events = null
        process?.stop()
        process = null
        endTurns(null)
        _state.value = BackendState(Phase.STOPPED)
    }

    /**
     * The server is going away: running turns end now (with [error] shown in
     * their chats, if given) and unanswered approvals are withdrawn.
     */
    private fun endTurns(error: String?) {
        val ended = busy.toList()
        busy.clear()
        for (sid in ended) {
            if (error != null) EventBus.emit("chat.error", jsonOf("sessionId" to sid, "message" to error))
            EventBus.emit("chat.status", jsonOf("sessionId" to sid, "status" to "idle"))
        }
        val asked = permissionSessions.keys.toList()
        permissionSessions.clear()
        asked.forEach { EventBus.emit("approval.resolved", jsonOf("requestId" to Ids.session(ID, it))) }
        val questions = questionSessions.keys.toList()
        questionSessions.clear()
        questions.forEach { EventBus.emit("question.resolved", jsonOf("requestId" to Ids.session(ID, it))) }
    }

    private fun watch(p: GuestProcess) {
        watchdog?.cancel()
        watchdog = scope.launch {
            val code = p.waitFor()
            if (!isActive) return@launch
            Logs.opencode.add("opencode exited with $code")
            // Android may kill child processes (phantom process killer, low memory).
            scope.launch { recover("exited with $code") }
        }
    }

    /** Polls the server while it is up; two missed checks in a row trigger [recover]. */
    private fun monitor(p: GuestProcess) {
        monitor?.cancel()
        monitor = scope.launch {
            var misses = 0
            while (isActive && p.isAlive) {
                delay(HEALTH_INTERVAL_MS)
                val started = System.currentTimeMillis()
                val ok = probeHealth()
                val took = System.currentTimeMillis() - started
                if (took > 3_000) Logs.opencode.add("health check took ${took} ms")
                if (ok) {
                    misses = 0
                } else if (++misses >= 2) {
                    scope.launch { recover("stopped responding") }
                    return@launch
                } else {
                    Logs.opencode.add("health check failed")
                }
            }
        }
    }

    /**
     * Restarts opencode after it died or stopped answering. Sessions live on disk,
     * so chats survive; the UI is told to reload. Limited to a few restarts per
     * window so a server that cannot start does not loop forever.
     */
    private suspend fun recover(reason: String) = recoverLock.withLock {
        if (_state.value.phase == Phase.STOPPED) return@withLock
        if (process?.isAlive == true && probeHealth()) return@withLock
        if (!shouldRecover()) {
            // Nothing is waiting on it: stay stopped until the next prompt starts it.
            Logs.opencode.add("opencode $reason while idle; stopping")
            stop()
            return@withLock
        }
        val now = System.currentTimeMillis()
        while (recoveries.isNotEmpty() && now - recoveries.first() > RECOVERY_WINDOW_MS) recoveries.removeFirst()
        if (recoveries.size >= MAX_RECOVERIES) {
            Logs.opencode.add("opencode $reason; not restarting again (${MAX_RECOVERIES}x in ${RECOVERY_WINDOW_MS / 60_000} min)")
            failed("opencode $reason")
            return@withLock
        }
        recoveries.addLast(now)
        Logs.opencode.add("opencode $reason; restarting")
        endTurns("opencode $reason and was restarted. Send again to continue.")
        stop()
        _state.value = BackendState(Phase.STARTING)
        EventBus.emit("backend.state", jsonOf("backend" to ID, "state" to _state.value.toJson()))
        try {
            start()
            EventBus.emit("backend.state", jsonOf("backend" to ID, "state" to _state.value.toJson()))
            EventBus.emit("backend.resync", jsonOf("backend" to ID))
        } catch (e: Exception) {
            failed(e.message ?: "opencode did not start")
        }
    }

    private fun failed(detail: String) {
        watchdog?.cancel()
        monitor?.cancel()
        events?.cancel()
        process?.stop()
        process = null
        endTurns(detail)
        _state.value = BackendState(Phase.FAILED, detail)
        EventBus.emit("backend.state", jsonOf("backend" to ID, "state" to _state.value.toJson()))
    }

    private fun probeHealth(): Boolean = try {
        if (port == 0) false
        else probe.newCall(Request.Builder().url(url("/global/health")).build()).execute().use { res ->
            res.isSuccessful && JSONObject(res.body?.string().orEmpty().ifEmpty { "{}" }).optBoolean("healthy", false)
        }
    } catch (_: Exception) {
        false
    }

    // --- events ------------------------------------------------------------

    private fun connectEvents() {
        val request = Request.Builder()
            .url(url("/event"))
            .header("Accept", "text/event-stream")
            .build()
        events = EventSources.createFactory(sseClient).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    handleEvent(JSONObject(data))
                } catch (e: Exception) {
                    Logs.opencode.add("event error: ${e.message}")
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (process?.isAlive != true) return
                Logs.opencode.add("event stream dropped: ${t?.message ?: response?.code}")
                scope.launch {
                    delay(1500)
                    if (!probeHealth()) {
                        recover("stopped sending events")
                        return@launch
                    }
                    if (process?.isAlive == true) {
                        connectEvents()
                        EventBus.emit("backend.resync", jsonOf("backend" to ID))
                    }
                }
            }
        })
    }

    internal fun handleEvent(e: JSONObject) {
        val type = e.optString("type")
        val p = e.optJSONObject("properties") ?: JSONObject()
        when (type) {
            "message.updated" -> {
                val info = p.obj("info") ?: return
                val sid = Ids.session(ID, info.optString("sessionID"))
                EventBus.emit("chat.message", jsonOf("sessionId" to sid, "message" to messageInfo(info)))
            }
            "message.part.updated" -> {
                val part = p.obj("part") ?: return
                val normalized = normalizePart(part) ?: return
                EventBus.emit(
                    "chat.part",
                    jsonOf(
                        "sessionId" to Ids.session(ID, part.optString("sessionID")),
                        "messageId" to part.optString("messageID"),
                        "part" to normalized,
                    ),
                )
            }
            "message.part.delta" -> {
                if (p.optString("field") != "text") return
                EventBus.emit(
                    "chat.delta",
                    jsonOf(
                        "sessionId" to Ids.session(ID, p.optString("sessionID")),
                        "messageId" to p.optString("messageID"),
                        "partId" to p.optString("partID"),
                        "field" to "text",
                        "delta" to p.optString("delta"),
                    ),
                )
            }
            "message.part.removed" -> EventBus.emit(
                "chat.partRemoved",
                jsonOf(
                    "sessionId" to Ids.session(ID, p.optString("sessionID")),
                    "messageId" to p.optString("messageID"),
                    "partId" to p.optString("partID"),
                ),
            )
            "session.status" -> {
                val status = p.obj("status")?.optString("type") ?: return
                val sid = Ids.session(ID, p.optString("sessionID"))
                if (status == "idle") busy.remove(sid) else busy.add(sid)
                if (status == "idle") dropQuestions(p.optString("sessionID"))
                EventBus.emit(
                    "chat.status",
                    jsonOf(
                        "sessionId" to sid,
                        "status" to if (status == "idle") "idle" else "busy",
                        "detail" to if (status == "retry") p.obj("status")?.str("message") else null,
                        // When opencode tries again (epoch ms), e.g. after a provider's Retry-After.
                        "retryAt" to if (status == "retry") p.obj("status")?.optLong("next")?.takeIf { it > 0 } else null,
                    ),
                )
            }
            "session.idle" -> {
                val sid = Ids.session(ID, p.optString("sessionID"))
                busy.remove(sid)
                dropQuestions(p.optString("sessionID"))
                EventBus.emit("chat.status", jsonOf("sessionId" to sid, "status" to "idle"))
            }
            "session.error" -> {
                val raw = p.str("sessionID") ?: return
                val err = p.obj("error")
                if (err?.str("name") == "MessageAbortedError") return
                val message = err?.obj("data")?.str("message") ?: err?.str("name") ?: "error"
                EventBus.emit("chat.error", jsonOf("sessionId" to Ids.session(ID, raw), "message" to message))
            }
            "session.created", "session.updated" -> {
                val info = p.obj("info") ?: return
                // A subagent's session: remembered so its events can be traced to the chat.
                info.str("parentID")?.let { parents[info.optString("id")] = it; return }
                EventBus.emit("sessions.upsert", jsonOf("session" to sessionInfo(info)))
            }
            "session.deleted" -> {
                val info = p.obj("info") ?: return
                EventBus.emit("sessions.remove", jsonOf("sessionId" to Ids.session(ID, info.optString("id"))))
            }
            "todo.updated" -> {
                val items = p.arr("todos").objects().map {
                    jsonOf("text" to it.optString("content"), "status" to it.optString("status"))
                }
                EventBus.emit(
                    "chat.todo",
                    jsonOf("sessionId" to Ids.session(ID, p.optString("sessionID")), "items" to items),
                )
            }
            "permission.asked", "permission.updated" -> {
                val requestId = p.optString("id")
                val raw = p.optString("sessionID")
                if (requestId.isEmpty() || raw.isEmpty()) return
                permissionSessions[requestId] = raw
                val patterns = p.optJSONArray("patterns") ?: p.optJSONArray("pattern") ?: JSONArray()
                val asked = p.str("permission") ?: p.str("type") ?: "other"
                val kind = if (asked == REPO_TOOL || mcpPrefixes.any { asked.startsWith(it) }) "tool" else asked
                EventBus.emit(
                    "approval.request",
                    jsonOf(
                        "requestId" to Ids.session(ID, requestId),
                        "sessionId" to Ids.session(ID, raw),
                        "kind" to kind,
                        // bash requests carry the full command line in metadata; the repo tool, a title.
                        "title" to (p.obj("metadata")?.let { it.str("command") ?: it.str("title") } ?: p.str("title") ?: asked),
                        "detail" to (0 until patterns.length()).joinToString("\n") { patterns.optString(it) },
                    ),
                )
            }
            "permission.replied" -> {
                val requestId = p.str("requestID") ?: p.str("permissionID") ?: p.optString("id")
                permissionSessions.remove(requestId)
                EventBus.emit("approval.resolved", jsonOf("requestId" to Ids.session(ID, requestId)))
            }
            "question.asked" -> {
                val requestId = p.optString("id")
                val raw = p.optString("sessionID")
                if (requestId.isEmpty() || raw.isEmpty()) return
                questionSessions[requestId] = raw
                EventBus.emit(
                    "question.request",
                    jsonOf(
                        "requestId" to Ids.session(ID, requestId),
                        "sessionId" to Ids.session(ID, raw),
                        "questions" to OpencodeQuestions.list(p.arr("questions"), null),
                    ),
                )
            }
            "question.replied", "question.rejected" -> {
                val requestId = p.str("requestID") ?: return
                questionSessions.remove(requestId)
                EventBus.emit("question.resolved", jsonOf("requestId" to Ids.session(ID, requestId)))
            }
        }
    }

    // --- normalization -----------------------------------------------------

    private fun messageInfo(info: JSONObject): JSONObject = jsonOf(
        "id" to info.optString("id"),
        "role" to info.optString("role"),
        "createdAt" to info.obj("time")?.long("created"),
        "model" to info.str("modelID"),
        "error" to info.obj("error")?.let { err ->
            if (err.str("name") == "MessageAbortedError") null
            else err.obj("data")?.str("message") ?: err.str("name")
        },
    )

    private fun sessionInfo(s: JSONObject): JSONObject {
        val time = s.obj("time")
        val raw = s.optString("id")
        return jsonOf(
            "id" to Ids.session(ID, raw),
            "backend" to ID,
            "title" to s.optString("title"),
            "createdAt" to time?.long("created"),
            "updatedAt" to (time?.long("updated") ?: time?.long("created")),
            "busy" to busy.contains(Ids.session(ID, raw)),
        )
    }

    /** Maps an opencode message part to the UI's part shape; null = not shown. */
    internal fun normalizePart(part: JSONObject): JSONObject? {
        val id = part.optString("id")
        return when (part.optString("type")) {
            "text" -> {
                val text = part.optString("text")
                AttachmentNote.parse(text)?.let { return AttachmentNote.part(id, it) }
                if (part.optBoolean("synthetic") || part.optBoolean("ignored") || ContextTag.matches(text)) null
                else jsonOf("id" to id, "kind" to "text", "text" to text)
            }
            "reasoning" -> jsonOf("id" to id, "kind" to "reasoning", "text" to part.optString("text"))
            "tool" -> {
                val st = part.obj("state") ?: JSONObject()
                if (part.optString("tool") == OpencodeConfig.QUESTION_TOOL) return OpencodeQuestions.part(id, st)
                val status = when (st.optString("status")) {
                    "completed" -> "completed"
                    "error" -> "error"
                    "running" -> "running"
                    else -> "pending"
                }
                val input = st.obj("input")
                val meta = st.obj("metadata")
                jsonOf(
                    "id" to id,
                    "kind" to "tool",
                    "tool" to part.optString("tool"),
                    // The Tensorlake plugin's bash reports "" as its title; fall back to the command.
                    "title" to (st.str("title")?.takeIf { it.isNotBlank() } ?: toolTitle(part.optString("tool"), input)),
                    "status" to status,
                    // A shell command is shown as itself, not as {"command": "..."}.
                    "input" to (input?.str("command") ?: input?.toString()?.replace("\\/", "/"))?.clip(4000),
                    "output" to (st.str("output") ?: st.str("error"))?.clip(),
                    "diff" to meta?.str("diff")?.clip(),
                )
            }
            "patch" -> jsonOf(
                "id" to id,
                "kind" to "notice",
                "text" to part.arr("files").let { f -> (0 until f.length()).joinToString(", ") { f.optString(it) } },
                "notice" to "files",
            )
            "retry" -> jsonOf("id" to id, "kind" to "notice", "notice" to "retry",
                "text" to (part.obj("error")?.obj("data")?.str("message") ?: ""))
            "compaction" -> jsonOf("id" to id, "kind" to "notice", "notice" to "compacted", "text" to "")
            "step-finish" -> {
                val tokens = part.obj("tokens")
                jsonOf(
                    "id" to id,
                    "kind" to "usage",
                    "input" to tokens?.long("input"),
                    "output" to tokens?.long("output"),
                    "reasoning" to tokens?.long("reasoning"),
                    "cost" to part.optDouble("cost", 0.0),
                )
            }
            else -> null // step-start, snapshot, file, agent, subtask: not shown
        }
    }

    private fun toolTitle(tool: String, input: JSONObject?): String {
        if (input == null) return tool
        val key = listOf("command", "filePath", "path", "pattern", "url", "query", "description")
            .firstOrNull { input.has(it) } ?: return tool
        return input.optString(key)
    }

    // --- API ---------------------------------------------------------------

    override suspend fun models(discover: Boolean): JSONArray = withContext(Dispatchers.IO) {
        // /config/providers lists only providers usable right now (the full
        // catalog behind /provider is several MB). Signed out, opencode itself
        // narrows "opencode" to its free models.
        val res = get("/config/providers")
        val defaults = res.obj("default") ?: JSONObject()
        val out = JSONArray()
        for (provider in res.arr("providers").objects()) {
            val pid = provider.optString("id")
            val models = provider.obj("models") ?: continue
            for (mid in models.keys()) {
                val m = models.getJSONObject(mid)
                if (m.optString("status") == "deprecated") continue
                // An agent needs tool calls; a model that cannot make them is left out
                // (many NVIDIA NIM models are chat-only).
                // Except one the app offers as plain chat ([chatOnly]).
                val chat = "$pid/$mid" in chatOnly
                if (!chat && m.obj("capabilities")?.has("toolcall") == true && !m.obj("capabilities")!!.optBoolean("toolcall")) continue
                val cost = m.obj("cost")
                // "Free" is OpenCode Zen's free tier; other providers (NIM is also $0) keep their own group.
                val free = pid == "opencode" && cost != null &&
                    cost.optDouble("input", 1.0) == 0.0 && cost.optDouble("output", 1.0) == 0.0
                val variants = m.obj("variants")?.keys()?.asSequence()?.toList().orEmpty()
                // What the model takes besides text (image, pdf, audio, video), for attachments.
                val inputs = m.obj("capabilities")?.obj("input")?.let { i -> i.keys().asSequence().filter { i.optBoolean(it) }.toList() }.orEmpty()
                out.put(
                    jsonOf(
                        "id" to "$ID:$pid/$mid",
                        "backend" to ID,
                        "provider" to pid,
                        "providerName" to provider.optString("name", pid),
                        "name" to m.optString("name", mid),
                        "free" to free,
                        "contextWindow" to m.obj("limit")?.long("context"),
                        "efforts" to variants,
                        "defaultEffort" to null,
                        "inputs" to inputs,
                        "chatOnly" to chat,
                        "isDefault" to (defaults.str(pid) == mid),
                        "available" to true,
                        "description" to null,
                    ),
                )
            }
        }
        out
    }

    override suspend fun sessions(): JSONArray = withContext(Dispatchers.IO) {
        val list = getArray("/session")
        JSONArray().apply {
            list.objects().filter { it.str("parentID") == null }.forEach { put(sessionInfo(it)) }
        }
    }

    override suspend fun createSession(model: ModelRef, title: String?, chatId: String, place: Place): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
        if (!title.isNullOrBlank()) body.put("title", title)
        val info = sessionInfo(post("/session", body))
        bindSandbox(info.getString("id"), chatId, place)
        info
    }

    /**
     * tensorlake-opencode names a session's sandbox itself; the mapping plugin turns
     * that into the chat's: a Tensorlake sandbox in the cloud, or "phone:<chat>" for
     * a device jail that PoorHarness's bridge serves.
     */
    override suspend fun bindSandbox(sessionId: String, chatId: String, place: Place) = withContext(Dispatchers.IO) {
        val value = if (place == Place.PHONE) "phone:$chatId" else SandboxManager.nameFor(chatId)
        editMap { it.put(SandboxManager.pluginName(Ids.split(sessionId).second), value) }
    }

    @Synchronized
    private fun editMap(change: (JSONObject) -> Unit) {
        val file = paths.hostPathOf(SANDBOX_MAP)
        val map = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        change(map)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(map.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    override suspend fun messages(sessionId: String): JSONArray = withContext(Dispatchers.IO) {
        val list = getArray("/session/${Ids.split(sessionId).second}/message")
        JSONArray().apply {
            for (m in list.objects()) {
                val info = m.obj("info") ?: continue
                val parts = JSONArray()
                m.arr("parts").objects().mapNotNull { normalizePart(it) }.forEach { parts.put(it) }
                put(messageInfo(info).put("parts", parts))
            }
        }
    }

    override suspend fun send(sessionId: String, text: String, model: ModelRef, context: String?, files: List<SentFile>, note: String?) {
        withContext(Dispatchers.IO) {
            val raw = Ids.split(sessionId).second
            val parts = JSONArray()
            // synthetic: the model reads it, opencode's own UIs (and ours) hide it.
            context?.let { parts.put(JSONObject().put("type", "text").put("text", it).put("synthetic", true)) }
            if (text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", text))
            // Where the attached files are in the sandbox; shown by the UI as chips. It is
            // the visible part of a message that has no text of its own.
            note?.let { parts.put(JSONObject().put("type", "text").put("text", it).put("synthetic", text.isNotBlank())) }
            // Files the model takes directly go in as data URLs: opencode runs on the
            // phone and cannot read the sandbox copy.
            for (f in files) {
                val bytes = f.inline?.file?.readBytes() ?: continue
                parts.put(
                    JSONObject().put("type", "file").put("mime", f.inline.mime).put("filename", f.name)
                        .put("url", "data:${f.inline.mime};base64," + Base64.getEncoder().encodeToString(bytes)),
                )
            }
            val body = JSONObject()
                .put("model", JSONObject().put("providerID", model.provider).put("modelID", model.modelOnly))
                .put("parts", parts)
            model.effort?.let { body.put("variant", it) }
            // A model that cannot call tools runs as the plain chat agent: opencode would send it tools.
            if ("${model.provider}/${model.modelOnly}" in chatOnly) body.put("agent", OpencodeConfig.CHAT_AGENT)
            busy.add(sessionId)
            EventBus.emit("chat.status", jsonOf("sessionId" to sessionId, "status" to "busy"))
            try {
                try {
                    post("/session/$raw/prompt_async", body)
                } catch (e: IOException) {
                    // A server that stopped answering is restarted and the prompt sent once more.
                    if (probeHealth()) throw e
                    Logs.opencode.add("prompt failed (${e.message}); restarting opencode")
                    busy.remove(sessionId) // sent again below, so not an interrupted turn
                    recover("did not accept a prompt")
                    if (_state.value.phase != Phase.READY) throw e
                    busy.add(sessionId)
                    EventBus.emit("chat.status", jsonOf("sessionId" to sessionId, "status" to "busy"))
                    post("/session/$raw/prompt_async", body)
                }
            } catch (e: Exception) {
                // The turn never started; nothing should wait for it.
                busy.remove(sessionId)
                EventBus.emit("chat.status", jsonOf("sessionId" to sessionId, "status" to "idle"))
                throw e
            }
        }
    }

    override fun rootSession(sessionId: String): String? {
        var cur = Ids.split(sessionId).second
        if (!parents.containsKey(cur)) return null
        repeat(16) { cur = parents[cur] ?: return Ids.session(ID, cur) }
        return Ids.session(ID, cur)
    }

    /** Stops the turn and any background subagents it started (they would otherwise wake it again). */
    override suspend fun abort(sessionId: String) {
        withContext(Dispatchers.IO) {
            post("/session/${Ids.split(sessionId).second}/abort", JSONObject())
            for (child in busy.toList()) {
                if (child != sessionId && rootSession(child) == sessionId) {
                    runCatching { post("/session/${Ids.split(child).second}/abort", JSONObject()) }
                }
            }
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            val raw = Ids.split(sessionId).second
            request("DELETE", "/session/$raw", null)
            editMap { it.remove(SandboxManager.pluginName(raw)) }
        }
    }

    override suspend fun reply(requestId: String, decision: String) {
        withContext(Dispatchers.IO) {
            val raw = Ids.split(requestId).second
            val session = permissionSessions[raw] ?: throw IOException("unknown request")
            val response = when (decision) { "session" -> "always"; "deny" -> "reject"; else -> "once" }
            post("/session/$session/permissions/$raw", JSONObject().put("response", response))
            permissionSessions.remove(raw)
            EventBus.emit("approval.resolved", jsonOf("requestId" to requestId))
        }
    }

    /**
     * A stopped turn leaves its question pending in opencode (1.19.0 sends nothing
     * for it), so the app withdraws it from the UI and clears it there.
     */
    private fun dropQuestions(rawSession: String) {
        val left = questionSessions.filterValues { it == rawSession }.keys
        for (raw in left) {
            questionSessions.remove(raw)
            EventBus.emit("question.resolved", jsonOf("requestId" to Ids.session(ID, raw)))
            scope.launch { runCatching { post("/question/$raw/reject", JSONObject()) } }
        }
    }

    override suspend fun answer(requestId: String, answers: List<List<String>>?) {
        withContext(Dispatchers.IO) {
            val raw = Ids.split(requestId).second
            if (answers == null) {
                post("/question/$raw/reject", JSONObject())
            } else {
                post("/question/$raw/reply", JSONObject().put("answers", JSONArray(answers.map { JSONArray(it) })))
            }
            questionSessions.remove(raw)
            EventBus.emit("question.resolved", jsonOf("requestId" to requestId))
        }
    }

    override fun setRecoveryPolicy(shouldRecover: () -> Boolean) {
        this.shouldRecover = shouldRecover
    }

    /**
     * A changed system prompt: opencode reads the instructions file at each prompt,
     * so it applies from the next one. Before opencode first started, the start writes it.
     */
    fun applySystemPrompt() {
        if (writeConfig == null) return
        runCatching { OpencodeConfig.writeInstructions(paths, settings.systemPrompt, gitOffered) }
            .onFailure { Logs.opencode.add("system prompt not written: ${it.message}") }
    }

    /**
     * Stores (or with null, removes) a provider API key in opencode's own auth store
     * (one of [OpencodeConfig.KEY_PROVIDERS]). A new or removed key only reaches
     * `/config/providers` after the instance is disposed, which also reads the config
     * file again; the catalog is refreshed by the caller afterwards.
     */
    suspend fun setKey(provider: String, key: String?) = withContext(Dispatchers.IO) {
        require(provider in OpencodeConfig.KEY_PROVIDERS) { "unknown provider: $provider" }
        if (key.isNullOrBlank()) request("DELETE", "/auth/$provider", null)
        else request("PUT", "/auth/$provider", JSONObject().put("type", "api").put("key", key.trim()))
        // A gateway's free models, and the models NIM serves, live in the app's config,
        // so it is written again with the key.
        if (FreeGateway.byId(provider) != null || provider == NvidiaNim.PROVIDER || provider == Venice.PROVIDER) writeConfig?.invoke()
        // Rebuild provider state so the added/removed key is reflected in the model list.
        runCatching { post("/instance/dispose", JSONObject()) }
        EventBus.emit("models.changed", jsonOf("backend" to ID))
    }

    // --- HTTP helpers ------------------------------------------------------

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun get(path: String): JSONObject = JSONObject(request("GET", path, null).ifEmpty { "{}" })

    private fun getArray(path: String): JSONArray = JSONArray(request("GET", path, null).ifEmpty { "[]" })

    private fun post(path: String, body: JSONObject): JSONObject =
        request("POST", path, body).let { if (it.startsWith("{")) JSONObject(it) else JSONObject() }

    private fun request(method: String, path: String, body: JSONObject?): String {
        if (port == 0) throw IOException("opencode is not running")
        val rb = body?.toString()?.toRequestBody(JSON)
            ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON)
        val req = Request.Builder().url(url(path)).method(method, rb).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("opencode $method $path: ${res.code} ${text.take(300)}")
            return text
        }
    }

    companion object {
        const val ID = "opencode"
        // Lets tensorlake-opencode's "no credentials" guard pass when a chat's tools are
        // served by the device bridge instead of Tensorlake; it is never sent anywhere.
        private const val PHONE_KEY_PLACEHOLDER = "poordsh-phone"
        private val JSON = "application/json".toMediaType()

        private const val HEALTH_INTERVAL_MS = 15_000L
        private const val HEALTH_TIMEOUT_S = 8L
        private const val SSE_SILENCE_S = 35L
        private const val MAX_RECOVERIES = 3
        private const val RECOVERY_WINDOW_MS = 10 * 60_000L

        // The plugin defaults to 2 vCPU / 4 GB, which Tensorlake's free plan
        // (1 vCPU / 1 GB / 10 GB disk) rejects. Verified 2026-10 with plugin 0.3.1.
        /** Guest paths: the session → chat sandbox map, PoorHarness's plugin, and what it reports. */
        const val SANDBOX_MAP = "/root/.config/poordsh/sandboxes.json"
        /** The plugin's tool for the user's git repositories (assets/opencode/poordsh-sandboxes.js). */
        const val REPO_TOOL = "repo"
        const val SANDBOX_PLUGIN = "/root/.config/poordsh/poordsh-sandboxes.js"
        /** Sent by the plugin to the sandbox for webfetch and websearch; read from beside the plugin. */
        const val WEB_SCRIPT = "/root/.config/poordsh/poordsh-web.py"
        const val STATUS = "/root/.config/poordsh/plugin-status.json"
        /** A gateway's last free-model list ([FreeGateway.FreeModels]). */
        fun freeListPath(gateway: String) = "/root/.config/poordsh/$gateway-free.json"
        /** The last list of Venice models on offer ([Venice]). */
        const val VENICE_MODELS = "/root/.config/poordsh/venice-models.json"
        /** The last list of models NIM serves ([NvidiaNim]). */
        const val NIM_SERVED = "/root/.config/poordsh/nvidia-served.json"

        private val TENSORLAKE_SANDBOX_SIZE = mapOf(
            "TENSORLAKE_CPUS" to "1",
            "TENSORLAKE_MEMORY_MB" to "1024",
            "TENSORLAKE_DISK_MB" to "10240",
        )

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        private fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
