package dev.mokouliszt.poordsh

import android.app.Application
import android.content.Context
import dev.mokouliszt.poordsh.attachments.AttachmentService
import dev.mokouliszt.poordsh.backend.opencode.OpencodeBackend
import dev.mokouliszt.poordsh.chat.ChatService
import dev.mokouliszt.poordsh.chat.ChatStore
import dev.mokouliszt.poordsh.engine.EngineManager
import dev.mokouliszt.poordsh.git.GitBucket
import dev.mokouliszt.poordsh.git.GitRepos
import dev.mokouliszt.poordsh.outputs.OutputService
import dev.mokouliszt.poordsh.outputs.OutputShelf
import dev.mokouliszt.poordsh.phone.PhoneSandboxManager
import dev.mokouliszt.poordsh.phone.PhoneSandboxServer
import dev.mokouliszt.poordsh.plugins.PluginStore
import dev.mokouliszt.poordsh.runtime.GuestEnvironment
import dev.mokouliszt.poordsh.runtime.Logs
import dev.mokouliszt.poordsh.runtime.Proot
import dev.mokouliszt.poordsh.runtime.RootfsInstaller
import dev.mokouliszt.poordsh.runtime.RuntimeManager
import dev.mokouliszt.poordsh.runtime.RuntimePaths
import dev.mokouliszt.poordsh.runtime.Tools
import dev.mokouliszt.poordsh.secrets.SecretStore
import dev.mokouliszt.poordsh.service.Keepalive
import dev.mokouliszt.poordsh.settings.Settings
import dev.mokouliszt.poordsh.skills.SkillStore
import dev.mokouliszt.poordsh.storage.S3Storage
import dev.mokouliszt.poordsh.tensorlake.SandboxManager
import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi
import dev.mokouliszt.poordsh.web.TinyFish
import dev.mokouliszt.poordsh.update.BinaryUpdater
import dev.mokouliszt.poordsh.update.EngineUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import java.io.File
import java.io.IOException

class PoorDshApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Wires the long-lived components. One instance per process. */
class AppGraph(context: Context) {
    val paths = RuntimePaths(context)
    val settings = Settings(context)
    val secrets = SecretStore(context)
    val installer = RootfsInstaller(context, paths)
    private val manifest by lazy { runCatching { installer.manifest() }.getOrNull() }
    val tools = Tools(paths) { manifest?.let { mapOf("opencode" to it.opencode) }.orEmpty() }
    val guestEnv = GuestEnvironment(context, paths)
    val proot = Proot(paths, guestEnv)
    val keepalive = Keepalive(context)

    fun tensorlakeKey(): String? = secrets.get(SecretStore.TENSORLAKE_API_KEY)
    fun hasTensorlakeKey() = tensorlakeKey() != null

    /** The user's S3-compatible bucket, when connected (docs/adr/s3-storage.md). */
    @Volatile private var s3Cache: Pair<String, S3Storage>? = null

    fun s3(): S3Storage? {
        val text = secrets.get(SecretStore.S3_STORAGE) ?: return null
        s3Cache?.takeIf { it.first == text }?.let { return it.second }
        val storage = runCatching { S3Storage(S3Storage.Config.fromJson(JSONObject(text))) }.getOrNull() ?: return null
        s3Cache = text to storage
        return storage
    }

    val tensorlake = TensorlakeApi(::tensorlakeKey)

    /** The webfetch/websearch fallback; its key is read at each call, so changing it needs no restart. */
    val tinyfish = TinyFish(apiKey = { secrets.get(SecretStore.TINYFISH_API_KEY) })
    val sandboxes = SandboxManager(tensorlake, Logs.tensorlake)
    val runtime = RuntimeManager(context, paths, installer, guestEnv, keepalive)

    // On-device fallback used when there is no Tensorlake key: one PRoot jail per chat.
    val phoneSandboxes = PhoneSandboxManager(paths, proot, runtime, Logs.tensorlake)
    val phoneBridge = PhoneSandboxServer(phoneSandboxes, Logs.tensorlake).also { bridge ->
        bridge.web = { kind, request ->
            when (kind) {
                "fetch" -> tinyfish.fetch(request.optString("url"), request.optString("format", "markdown"))
                "search" -> tinyfish.search(request.optString("query"), request.optInt("numResults", 8))
                else -> throw IOException("unknown web request $kind")
            }
        }
    }

    fun engineOptions(): Set<String> = settings.engineOptions

    /** The user's skills; opencode reads them on the phone, each chat's sandbox gets a copy. */
    val skills = SkillStore(paths.skills).apply { cleanUp() }

    /** The user's plugins, kept and copied like skills; each engine uses them its own way. */
    val plugins = PluginStore(paths.plugins).apply { cleanUp() }

    val opencode = OpencodeBackend(
        paths, proot, tools, settings, secrets,
        tensorlakePluginVersion = { manifest?.tensorlakePlugin?.takeIf { it.isNotBlank() } },
        sandboxPlugin = { context.assets.open("opencode/poordsh-sandboxes.js").use { it.readBytes() } },
        webScript = { context.assets.open("opencode/poordsh-web.py").use { it.readBytes() } },
        options = ::engineOptions,
        phone = phoneBridge,
        skills = { skills.names() },
        plugins = { plugins.state() },
        gitRepos = { s3() != null },
    )
    val engines = EngineManager(runtime, listOf(opencode), File(context.filesDir, "cache"), keepalive, BuildConfig.VERSION_NAME)
    val chatStore = ChatStore(File(context.filesDir, "chats"))

    /** Files agents hand over: in the Ubuntu home's `outputs` folder, or the bucket's `outputs` folder. */
    val outputs = OutputService(
        device = OutputShelf.Device(File(paths.home, "outputs")),
        s3 = { s3()?.let { OutputShelf.S3(it.space(S3Storage.OUTPUTS)) } },
        destination = { settings.outputsDestination },
        staging = File(context.cacheDir, "outputs"),
        delivered = chatStore::outputSignatures,
        record = chatStore::recordOutput,
        log = { Logs.app.add(it) },
    )
    val chats = ChatService(
        chatStore, engines, sandboxes, phoneSandboxes, ::hasTensorlakeKey, tensorlake, skills, plugins, outputs,
    )
    /** Git for agents, kept in the bucket's `git` folder (docs/adr/git-repos.md). */
    val git = GitRepos(
        bucket = { s3()?.let { GitBucket.of(it.space(S3Storage.GIT)) } },
        shell = chats::withShell,
        staging = File(context.cacheDir, "git"),
        stateFile = File(context.filesDir, "git-chats.json"),
        log = { Logs.app.add(it) },
    ).also { git ->
        chats.git = git
        phoneBridge.repos = { sandbox, action, name ->
            chats.chatOfSandbox(sandbox)?.let { git.call(it, action, name) } ?: "Not run: no chat owns this session."
        }
    }
    val attachments = AttachmentService(
        context.contentResolver, paths.attachments, context.cacheDir, tensorlake, sandboxes, phoneSandboxes,
        placeFor = chats::placeFor, hasRun = chats::hasRun, log = Logs.app,
    ).also { chats.attachments = it }

    init {
        // opencode lists skills (and which it may load), MCP servers and agents when it starts.
        skills.onChange = { engines.applyWhenIdle(OpencodeBackend.ID) }
        plugins.onChange = { engines.applyWhenIdle(OpencodeBackend.ID) }
    }
    val updater = BinaryUpdater(paths, tools)
    /** Checked whenever the app opens; installed then too when auto update is on. */
    val engineUpdates = EngineUpdates(
        updater,
        autoUpdate = { settings.autoUpdateEngines },
        applyWhenIdle = engines::applyWhenIdle,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /** The notification's Stop: engines down, sandboxes paused, service gone, UI closed. Nothing waits. */
    fun shutdown() {
        engines.stopAllNow()
        sandboxes.suspendAllNow()
        phoneSandboxes.stopAllNow()
        keepalive.clear()
        chats.emitUi("app.exit")
    }
}
