package dev.mokouliszt.poordsh.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.browser.customtabs.CustomTabsIntent
import dev.mokouliszt.poordsh.AppGraph
import dev.mokouliszt.poordsh.BuildConfig
import dev.mokouliszt.poordsh.attachments.AttachmentService
import dev.mokouliszt.poordsh.backend.BackendState
import dev.mokouliszt.poordsh.backend.EngineOptions
import dev.mokouliszt.poordsh.backend.EventBus
import dev.mokouliszt.poordsh.backend.Ids
import dev.mokouliszt.poordsh.backend.ModelRef
import dev.mokouliszt.poordsh.backend.Place
import dev.mokouliszt.poordsh.backend.SystemPrompt
import dev.mokouliszt.poordsh.backend.opencode.OpencodeConfig
import dev.mokouliszt.poordsh.git.GitBucket
import dev.mokouliszt.poordsh.outputs.OutputShelf
import dev.mokouliszt.poordsh.plugins.PluginPackage
import dev.mokouliszt.poordsh.plugins.PluginStore
import dev.mokouliszt.poordsh.runtime.Logs
import dev.mokouliszt.poordsh.runtime.Tools
import dev.mokouliszt.poordsh.secrets.SecretStore
import dev.mokouliszt.poordsh.settings.Settings
import dev.mokouliszt.poordsh.skills.SkillPackage
import dev.mokouliszt.poordsh.storage.S3Storage
import dev.mokouliszt.poordsh.tensorlake.SandboxManager
import dev.mokouliszt.poordsh.tensorlake.SandboxManager.Companion.WORKSPACE
import dev.mokouliszt.poordsh.tensorlake.TensorlakeApi
import dev.mokouliszt.poordsh.web.TinyFish
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single entry point from the web UI: `PoorDSHNative.request(json)`.
 * Each request is `{id, method, params}`; the reply is delivered to
 * `window.__poordsh.onResponse({id, ok, result | error})`. See docs/bridge-protocol.md.
 */
class WebBridge(
    private val context: Context,
    private val graph: AppGraph,
    private val deliver: (String) -> Unit,
    private val requestNotificationPermission: () -> Unit,
    private val picker: DocumentPicker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engines get() = graph.engines
    private val export = FileExport(context)

    @JavascriptInterface
    fun request(json: String) {
        val msg = try { JSONObject(json) } catch (_: Exception) { return }
        val id = msg.opt("id")
        val method = msg.optString("method")
        val params = msg.optJSONObject("params") ?: JSONObject()
        scope.launch {
            val reply = try {
                jsonOf("id" to id, "ok" to true, "result" to handle(method, params))
            } catch (e: Exception) {
                Logs.app.add("$method failed: ${e.message}")
                jsonOf("id" to id, "ok" to false, "error" to (e.message ?: e.javaClass.simpleName))
            }
            deliver("window.__poordsh&&window.__poordsh.onResponse(${reply.toJsLiteral()})")
        }
    }

    private fun runtimeJson(): JSONObject = graph.runtime.toJson()
        .put("backends", jsonOf(*engines.backends.map { (k, b) -> k to b.state.value.toJson() }.toTypedArray()))

    private fun applySettingsToEngines() = engines.backends.keys.forEach { engines.applyWhenIdle(it) }

    private suspend fun handle(method: String, p: JSONObject): Any? = when (method) {
        "app.info" -> jsonOf(
            "version" to BuildConfig.VERSION_NAME,
            "workspace" to WORKSPACE,
            "android" to Build.VERSION.RELEASE,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "abi" to Build.SUPPORTED_ABIS.firstOrNull(),
        )
        "app.notices" -> context.assets.open("notices/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }

        // --- runtime & engines ---
        "runtime.state" -> runtimeJson()
        "runtime.start" -> { graph.runtime.ensureReady(); engines.discoverMissing(); null }
        "runtime.reinstall" -> {
            check(!engines.anyActivity()) { "A task is running" }
            engines.stopAll()
            graph.runtime.reinstall()
            null
        }
        "runtime.logs" -> JSONArray().apply {
            Logs.all.forEach { put(jsonOf("source" to it.source, "lines" to it.snapshot())) }
        }
        "engines.start" -> { engines.use(p.getString("target"), "manual") { }; null }
        "engines.stop" -> { applySettingsToEngines(); null }
        // Installed versions, without the network (updates.check also asks npm).
        "engines.versions" -> jsonOf(*Tools.Tool.entries.map { tool ->
            val bin = graph.tools.locate(tool)
            tool.id to jsonOf("current" to bin.version, "bundled" to bin.bundled)
        }.toTypedArray())

        // --- models & chats (no engine needed) ---
        "models.list" -> engines.cachedModels()
        "sessions.list" -> jsonOf("items" to graph.chats.list(), "errors" to JSONObject())
        "sessions.create" -> graph.chats.create()
        "sessions.messages" -> graph.chats.messages(p.getString("sessionId"))
        "sessions.delete" -> { graph.chats.delete(p.getString("sessionId")); null }

        // --- chat ---
        "chat.send" -> {
            val model = ModelRef.parse(p.getString("model"), p.str("effort"))
            graph.settings.lastModel = p.getString("model")
            graph.settings.lastEffort = model.effort
            val files = p.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            graph.chats.send(p.getString("sessionId"), p.optString("text"), model, p.str("modelName"), files)
            null
        }

        // --- attachments: picked into the app now, put in the chat's sandbox on send ---
        "attachments.add" -> {
            val chatId = p.str("sessionId")
            val items = JSONArray()
            val errors = JSONArray()
            for (uri in picker.pick(arrayOf("*/*"), multiple = true)) {
                try {
                    items.put(graph.attachments.json(graph.attachments.stage(uri, chatId)))
                } catch (e: AttachmentService.NoSpace) {
                    errors.put(jsonOf("name" to uri.lastPathSegment, "error" to e.message, "where" to e.where, "needed" to e.needed, "free" to e.free))
                } catch (e: Exception) {
                    Logs.app.add("attach failed: ${e.message}")
                    errors.put(jsonOf("name" to uri.lastPathSegment, "error" to (e.message ?: "attach_failed")))
                }
            }
            jsonOf("items" to items, "errors" to errors)
        }
        "attachments.discard" -> {
            graph.attachments.discard(p.optJSONArray("ids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
            null
        }

        // --- skills (Settings) ---
        "skills.list" -> JSONArray().apply {
            graph.skills.list().forEach { put(jsonOf("name" to it.name, "description" to it.description, "files" to it.files, "bytes" to it.bytes)) }
        }
        // Null when the picker was closed without a file.
        "skills.add" -> picker.pick(arrayOf("*/*"), multiple = false).firstOrNull()?.let { uri ->
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("could not open the file")
            val info = try {
                input.use { graph.skills.install(it) }
            } catch (e: SkillPackage.Invalid) {
                throw IOException("skill_invalid: ${e.message}")
            }
            jsonOf("name" to info.name, "description" to info.description, "files" to info.files, "bytes" to info.bytes)
        }
        "skills.delete" -> { graph.skills.delete(p.getString("name")); null }

        // --- plugins (Settings) ---
        "plugins.list" -> JSONArray().apply { graph.plugins.list().forEach { put(pluginJson(it)) } }
        // Null when the picker was closed without a file.
        "plugins.add" -> picker.pick(arrayOf("*/*"), multiple = false).firstOrNull()?.let { uri ->
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("could not open the file")
            val info = try {
                input.use { graph.plugins.install(it) }
            } catch (e: PluginPackage.Invalid) {
                throw IOException("plugin_invalid:${e.reason}: ${e.message}")
            }
            pluginJson(info)
        }
        "plugins.delete" -> { graph.plugins.delete(p.getString("name")); null }
        "chat.abort" -> { graph.chats.abort(p.getString("sessionId")); null }
        "approval.reply" -> {
            val requestId = p.getString("requestId")
            engines.backend(Ids.split(requestId).first).reply(requestId, p.optString("decision", "once"))
            null
        }
        "question.reply" -> {
            val requestId = p.getString("requestId")
            val answers = p.optJSONArray("answers")?.let { list ->
                (0 until list.length()).map { i -> list.getJSONArray(i).let { a -> (0 until a.length()).map { a.getString(it) } } }
            }
            engines.backend(Ids.split(requestId).first).answer(requestId, answers)
            null
        }

        // --- accounts ---
        "accounts.get" -> jsonOf(
            "opencode" to OpencodeConfig.storedCredentials(graph.paths).let { stored ->
                jsonOf(
                    "keys" to JSONArray(OpencodeConfig.KEY_PROVIDERS.filter { it in stored }),
                    "state" to graph.opencode.state.value.toJson(),
                )
            },
            "tensorlake" to jsonOf("keySet" to graph.secrets.has(SecretStore.TENSORLAKE_API_KEY)),
            "tinyfish" to jsonOf("keySet" to graph.secrets.has(SecretStore.TINYFISH_API_KEY)),
        )
        // provider: one of OpencodeConfig.KEY_PROVIDERS ("opencode" is the OpenCode Zen key).
        "opencode.setKey" -> {
            val provider = p.optString("provider", "opencode")
            // Saving can mean starting opencode, reading a gateway's catalog and reloading
            // the models; each stage is reported so the sheet can say what it waits for.
            val stage = { s: String -> EventBus.emit("key.progress", jsonOf("provider" to provider, "stage" to s)) }
            if (graph.opencode.state.value.phase != BackendState.Phase.READY) stage("starting")
            engines.use("opencode", "key") {
                stage("saving")
                graph.opencode.setKey(provider, p.str("key"))
                stage("models")
                engines.refreshCatalog(it)
            }
            null
        }

        // --- engine options ---
        "options.get" -> jsonOf(
            "items" to EngineOptions.ALL.map { jsonOf("id" to it.id, "engine" to it.engine, "default" to it.default, "requires" to it.requires) },
            "enabled" to graph.settings.engineOptions.toList(),
        )
        "options.set" -> {
            val next = EngineOptions.sanitize(p.optJSONArray("enabled")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
            val changed = next != graph.settings.engineOptions
            graph.settings.engineOptions = next
            // Options take effect on the next engine start, like other restart-needing settings.
            if (changed) applySettingsToEngines()
            jsonOf("enabled" to next.toList())
        }
        "tensorlake.set" -> {
            val key = p.str("key")?.trim()?.takeIf { it.isNotEmpty() }
            if (key != null) {
                // Checked before it is kept: a wrong key would only fail later, mid-chat.
                try {
                    TensorlakeApi({ key }).list("running")
                } catch (e: TensorlakeApi.AuthException) {
                    throw IOException("tensorlake_key_invalid")
                }
            }
            graph.secrets.put(SecretStore.TENSORLAKE_API_KEY, key)
            // opencode reads the key when it starts: applied on the next prompt.
            engines.applyWhenIdle("opencode")
            engines.discoverMissing()
            EventBus.emit("account.changed", jsonOf("backend" to "tensorlake"))
            null
        }

        "tinyfish.set" -> {
            val key = p.str("key")?.trim()?.takeIf { it.isNotEmpty() }
            if (key != null) {
                // Checked before it is kept, like the Tensorlake key.
                try {
                    TinyFish({ key }).check()
                } catch (e: TinyFish.AuthException) {
                    throw IOException("tinyfish_key_invalid")
                }
            }
            // The bridge reads the key at each call: nothing to restart.
            graph.secrets.put(SecretStore.TINYFISH_API_KEY, key)
            EventBus.emit("account.changed", jsonOf("backend" to "tinyfish"))
            null
        }

        // --- files in a chat's sandbox (cloud) or jail (phone) ---
        "files.list" -> {
            val chatId = p.getString("sessionId")
            val path = workspacePath(p.str("path"))
            if (graph.chats.placeOf(chatId) == Place.PHONE) {
                val root = phoneWorkspaceRoot(chatId)
                if (!root.isDirectory) jsonOf("path" to path, "entries" to JSONArray(), "missing" to true)
                else {
                    val dir = phoneResolve(chatId, path)
                    val entries = dir.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).map {
                        jsonOf("name" to it.name, "dir" to it.isDirectory, "size" to it.length().takeIf { _ -> it.isFile }, "modifiedAt" to it.lastModified())
                    }
                    jsonOf("path" to path, "entries" to entries)
                }
            } else {
                val box = SandboxManager.nameFor(chatId)
                // A chat that never ran, or whose sandbox Tensorlake has deleted (7 days): nothing to wake.
                if (!graph.hasTensorlakeKey() || !graph.sandboxes.exists(box)) {
                    jsonOf("path" to path, "entries" to JSONArray(), "missing" to true)
                } else graph.sandboxes.use(box, "files") { ready ->
                    val entries = try {
                        graph.tensorlake.listDir(ready.sandbox, path)
                    } catch (e: TensorlakeApi.ApiException) {
                        if (e.code == 404) emptyList() else throw e
                    }
                    jsonOf(
                        "path" to path,
                        "entries" to entries.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })).map {
                            jsonOf("name" to it.name, "dir" to it.isDir, "size" to it.size, "modifiedAt" to it.modifiedAt)
                        },
                    )
                }
            }
        }
        "files.export" -> {
            val chatId = p.getString("sessionId")
            val path = workspacePath(p.getString("path"))
            if (graph.chats.placeOf(chatId) == Place.PHONE) {
                val f = phoneResolve(chatId, path)
                if (!f.isFile) throw IOException("file_missing")
                deliver(f.name, f.inputStream(), p.optString("action", "save"))
            } else withSandbox(chatId) { ready ->
                val input = graph.tensorlake.read(ready.sandbox, path) ?: throw IOException("file_missing")
                deliver(path.substringAfterLast('/'), input, p.optString("action", "save"))
            }
        }
        "files.zip" -> {
            val chatId = p.getString("sessionId")
            if (graph.chats.placeOf(chatId) == Place.PHONE) {
                val root = phoneWorkspaceRoot(chatId)
                if (!root.isDirectory) throw IOException("sandbox_missing")
                deliver(zipName(chatId), phoneZip(root), p.optString("action", "save"))
            } else withSandbox(chatId) { ready ->
                val zip = "/tmp/poordsh-export.zip"
                val r = graph.tensorlake.run(ready.sandbox, "python3", listOf("-c", ZIP, WORKSPACE, zip), timeoutSecs = 300)
                if (r.exitCode != 0) throw IOException("could not pack the files: ${r.stderr.trim().takeLast(200)}")
                val input = graph.tensorlake.read(ready.sandbox, zip) ?: throw IOException("file_missing")
                deliver(zipName(chatId), input, p.optString("action", "save"))
            }
        }

        // --- files agents hand over (docs/adr/outputs.md) and where they are kept ---
        "storage.get" -> jsonOf(
            "destination" to graph.settings.outputsDestination,
            "s3" to graph.s3()?.config?.describe(),
        )
        "storage.setS3" -> {
            val old = graph.s3()?.config
            val endpoint = p.getString("endpoint").trim()
            val keyId = p.getString("accessKeyId").trim()
            // Editing other fields keeps the stored secret unless a new one is typed.
            val secret = p.str("secret")?.trim()?.takeIf { it.isNotEmpty() }
                ?: old?.secretAccessKey?.takeIf { old.accessKeyId == keyId }
                ?: throw IOException("s3_secret_missing")
            if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) throw IOException("s3_bad_endpoint")
            val config = S3Storage.Config(
                endpoint = endpoint.trimEnd('/'), bucket = p.getString("bucket").trim(), accessKeyId = keyId, secretAccessKey = secret,
                region = p.optString("region").trim(), prefix = p.optString("prefix").trim().trim('/'),
            )
            try {
                // Written, read back and deleted before it is kept: a wrong key or bucket fails here, not mid-chat.
                S3Storage(config).check()
            } catch (e: S3Storage.S3Exception) {
                throw IOException(
                    when {
                        e.status == 403 || e.code == "AccessDenied" || e.code == "InvalidAccessKeyId" || e.code == "SignatureDoesNotMatch" -> "s3_denied"
                        e.code == "NoSuchBucket" || e.status == 404 -> "s3_no_bucket"
                        else -> "s3_failed: ${e.message}"
                    },
                )
            } catch (e: IOException) {
                throw IOException("s3_unreachable: ${e.message}")
            } catch (e: IllegalArgumentException) {
                throw IOException("s3_bad_endpoint")
            }
            graph.secrets.put(SecretStore.S3_STORAGE, config.toJson().toString())
            // opencode offers the repo tool only with a bucket (docs/adr/git-repos.md).
            if (old == null) applySettingsToEngines()
            jsonOf("s3" to config.describe())
        }
        "storage.clearS3" -> {
            graph.secrets.put(SecretStore.S3_STORAGE, null)
            if (graph.settings.outputsDestination == OutputShelf.S3) graph.settings.outputsDestination = OutputShelf.DEVICE
            applySettingsToEngines()
            null
        }
        "storage.setDestination" -> {
            val to = p.getString("destination")
            require(to == OutputShelf.DEVICE || to == OutputShelf.S3) { "bad destination" }
            if (to == OutputShelf.S3 && graph.s3() == null) throw IOException("s3_not_connected")
            graph.settings.outputsDestination = to
            null
        }
        "outputs.list" -> {
            val shelf = shelfOf(p.getString("shelf"))
            val items = shelf.list().sortedByDescending { it.modifiedAt }
            jsonOf(
                "items" to items.map {
                    jsonOf(
                        "chatId" to it.chatId, "chatTitle" to graph.chatStore.meta(it.chatId)?.optString("title"),
                        "name" to it.name, "size" to it.size, "modifiedAt" to it.modifiedAt,
                    )
                },
                "total" to items.sumOf { it.size },
            )
        }
        "outputs.export" -> {
            val name = p.getString("name")
            val input = shelfOf(p.getString("shelf")).open(p.getString("chatId"), name) ?: throw IOException("output_missing")
            deliver(name, input, p.optString("action", "save"))
        }
        "outputs.delete" -> { shelfOf(p.getString("shelf")).delete(p.getString("chatId"), p.getString("name")); null }
        "outputs.deleteAll" -> { shelfOf(p.getString("shelf")).deleteAll(); null }

        // --- git repositories in the bucket (docs/adr/git-repos.md) ---
        "git.list" -> {
            val s3 = graph.s3() ?: throw IOException("s3_not_connected")
            val repos = GitBucket.of(s3.space(S3Storage.GIT)).repos()
            val base = "s3://${s3.config.bucket}/${s3.config.base}${S3Storage.GIT}/"
            jsonOf(
                "items" to repos.map {
                    jsonOf(
                        "name" to it.name, "head" to it.head?.removePrefix("refs/heads/"), "branches" to JSONArray(it.branches),
                        "size" to it.size, "modifiedAt" to it.modifiedAt, "url" to base + it.name,
                    )
                },
                "total" to repos.sumOf { it.size },
            )
        }
        // The default branch's bundle: `git clone -b <branch> <file>` makes a working copy of it.
        "git.export" -> {
            val s3 = graph.s3() ?: throw IOException("s3_not_connected")
            val bucket = GitBucket.of(s3.space(S3Storage.GIT))
            val repo = bucket.repo(p.getString("name")) ?: throw IOException("output_missing")
            val ref = repo.refs[repo.head] ?: repo.refs.values.firstOrNull { it.name.startsWith("refs/heads/") } ?: throw IOException("output_missing")
            val input = s3.space(S3Storage.GIT).get(ref.key) ?: throw IOException("output_missing")
            deliver("${repo.name}-${ref.name.removePrefix("refs/heads/").replace('/', '-')}.bundle", input, p.optString("action", "save"))
        }
        "git.delete" -> {
            val s3 = graph.s3() ?: throw IOException("s3_not_connected")
            GitBucket.of(s3.space(S3Storage.GIT)).delete(p.getString("name"))
            null
        }
        "git.deleteAll" -> {
            val s3 = graph.s3() ?: throw IOException("s3_not_connected")
            s3.space(S3Storage.GIT).deleteAll()
            null
        }

        // --- settings ---
        "settings.get" -> graph.settings.toJson()
        "settings.set" -> {
            var engineSettings = false
            if (p.has("approval")) {
                val next = Settings.Approval.of(p.getString("approval"))
                engineSettings = next != graph.settings.approval
                graph.settings.approval = next
            }
            if (p.has("language")) graph.settings.language = p.getString("language")
            if (p.has("lastModel")) graph.settings.lastModel = p.str("lastModel")
            if (p.has("lastEffort")) graph.settings.lastEffort = p.str("lastEffort")
            if (p.has("disableSeccomp")) {
                engineSettings = engineSettings || p.optBoolean("disableSeccomp") != graph.settings.disableSeccomp
                graph.settings.disableSeccomp = p.optBoolean("disableSeccomp")
            }
            if (p.has("subagentModel") || p.has("subagentEffort")) {
                val model = if (p.has("subagentModel")) p.str("subagentModel") else graph.settings.subagentModel
                val effort = if (model == null) null else if (p.has("subagentEffort")) p.str("subagentEffort") else graph.settings.subagentEffort
                engineSettings = engineSettings || model != graph.settings.subagentModel || effort != graph.settings.subagentEffort
                graph.settings.subagentModel = model
                graph.settings.subagentEffort = effort
            }
            if (p.has("systemPrompt")) {
                val next = p.optString("systemPrompt").trim()
                if (next.length > SystemPrompt.MAX_CHARS) throw IllegalArgumentException("system_prompt_too_long")
                if (next != graph.settings.systemPrompt) {
                    graph.settings.systemPrompt = next
                    // opencode reads it at its next prompt.
                    graph.opencode.applySystemPrompt()
                }
            }
            if (p.has("autoUpdate")) {
                val on = p.optBoolean("autoUpdate")
                val turnedOn = on && !graph.settings.autoUpdateEngines
                graph.settings.autoUpdateEngines = on
                if (turnedOn) graph.engineUpdates.onAutoUpdateEnabled()
            }
            if (engineSettings) applySettingsToEngines()
            graph.settings.toJson()
        }

        // --- updates ---
        "updates.check" -> graph.engineUpdates.check()
        // The launch check's result, if one ran (null otherwise); no network.
        "updates.last" -> graph.engineUpdates.last
        "updates.apply" -> jsonOf("version" to graph.engineUpdates.apply(p.getString("tool")))
        "updates.reset" -> {
            val tool = Tools.Tool.entries.first { it.id == p.getString("tool") }
            graph.tools.resetToBundled(tool)
            engines.applyWhenIdle(tool.id)
            null
        }

        // --- device ---
        "app.openUrl" -> { openUrl(p.getString("url")); null }
        "app.copy" -> {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("PoorHarness", p.getString("text")))
            null
        }
        "app.share" -> {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.getString("text"))
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }
        "app.requestNotifications" -> { requestNotificationPermission(); null }

        else -> throw IllegalArgumentException("unknown method $method")
    }

    companion object {
        /** Packs the workspace, leaving out dependency folders that can be rebuilt and files it cannot read. */
        private val ZIP = """
            import os, sys, zipfile
            root, out = sys.argv[1], sys.argv[2]
            skip = {"node_modules", ".venv", "venv", "__pycache__"}
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
                for d, dirs, files in os.walk(root):
                    dirs[:] = [x for x in dirs if x not in skip]
                    for f in files:
                        p = os.path.join(d, f)
                        try:
                            z.write(p, os.path.relpath(p, root))
                        except OSError:
                            pass
        """.trimIndent()
    }

    // --- phone (device jail) files ---

    private fun phoneWorkspaceRoot(chatId: String): File =
        File(File(graph.paths.sandboxes, dev.mokouliszt.poordsh.phone.PhoneSandboxManager.safe(chatId)), "workspace")

    /** Maps a validated guest workspace path to the host file, refusing anything outside the workspace. */
    private fun phoneResolve(chatId: String, guestPath: String): File {
        val root = phoneWorkspaceRoot(chatId).canonicalFile
        val rel = guestPath.removePrefix(WORKSPACE).trimStart('/')
        val f = (if (rel.isEmpty()) root else File(root, rel)).canonicalFile
        if (f != root && !f.path.startsWith(root.path + File.separator)) throw IllegalArgumentException("outside the workspace")
        return f
    }

    /** Zips a chat's on-device workspace to a temp file (skipping rebuildable dependency folders). */
    private fun phoneZip(root: File): java.io.InputStream {
        val skip = setOf("node_modules", ".venv", "venv", "__pycache__")
        val out = File(context.cacheDir, "exports").apply { mkdirs() }.let { File(it, "phone-export.zip") }
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            root.walkTopDown()
                .onEnter { it.name !in skip }
                .filter { it.isFile }
                .forEach { f ->
                    runCatching {
                        zip.putNextEntry(ZipEntry(root.toPath().relativize(f.toPath()).toString()))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
        }
        return out.inputStream()
    }

    /** The chat's sandbox, woken if needed; never created just to read files. */
    private suspend fun <T> withSandbox(chatId: String, block: suspend (SandboxManager.Ready) -> T): T {
        val box = SandboxManager.nameFor(chatId)
        if (!graph.sandboxes.exists(box)) throw IOException("sandbox_missing")
        return graph.sandboxes.use(box, "files", block)
    }

    /** Paths the UI may ask for: inside the chat's workspace only. */
    private fun workspacePath(path: String?): String {
        val p = (path ?: WORKSPACE).trimEnd('/').ifEmpty { WORKSPACE }
        if (p != WORKSPACE && !p.startsWith("$WORKSPACE/") || p.split('/').any { it == ".." }) {
            throw IllegalArgumentException("outside the workspace")
        }
        return p
    }

    private fun shelfOf(id: String): OutputShelf =
        graph.outputs.shelf(id) ?: throw IOException(if (id == OutputShelf.S3) "s3_not_connected" else "bad shelf")

    private fun deliver(name: String, input: java.io.InputStream, action: String): JSONObject {
        val where = if (action == "share") {
            export.share(name, input)
            "share"
        } else {
            export.save(name, input)
        }
        return jsonOf("where" to where, "name" to name)
    }

    private fun zipName(chatId: String): String {
        val title = graph.chats.title(chatId).replace(Regex("[^\\p{L}\\p{N}._-]+"), "-").trim('-').take(40)
        return (title.ifEmpty { "workspace" }) + ".zip"
    }

    private fun openUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" && uri.scheme != "http") return
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().apply {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }.launchUrl(context, uri)
        } catch (_: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** A plugin as Settings shows it: what it holds, and which engine uses each part (docs/bridge-protocol.md). */
    private fun pluginJson(info: PluginStore.Info): JSONObject {
        val m = info.manifest
        return jsonOf(
            "name" to m.name,
            "version" to m.version,
            "description" to m.description,
            "format" to m.format.id,
            "files" to info.files,
            "bytes" to info.bytes,
            "skills" to jsonArrayOf(m.skills.map { jsonOf("name" to it.name, "description" to it.description) }),
            "mcpServers" to jsonArrayOf(m.mcpServers.map { jsonOf("name" to it.name, "remote" to (it.url != null), "url" to it.url, "command" to it.command) }),
            "hooks" to jsonArrayOf(m.hooks.map { jsonOf("event" to it.event, "command" to it.command) }),
            "agents" to jsonArrayOf(m.agents.map { jsonOf("name" to it.name, "description" to it.description) }),
            "commands" to m.commands,
            "apps" to m.apps,
        )
    }
}
