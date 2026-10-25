package dev.mokouliszt.poordsh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import dev.mokouliszt.poordsh.bridge.DocumentPicker
import dev.mokouliszt.poordsh.bridge.WebBridge
import dev.mokouliszt.poordsh.bridge.toJsLiteral
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the web UI (bundled under assets/webui) in a WebView. The page is
 * served from https://appassets.androidplatform.net, never from file://, and
 * any navigation away from it opens in the browser instead.
 */
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val graph get() = (application as PoorDshApp).graph

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    // The system picker for skills and attachments; one pick at a time.
    private var picking: CompletableDeferred<List<Uri>>? = null
    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        picking?.complete(listOfNotNull(uri))
    }
    private val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picking?.complete(uris)
    }
    private val picker = DocumentPicker { types, multiple ->
        withContext(Dispatchers.Main) {
            picking?.complete(emptyList())
            val result = CompletableDeferred<List<Uri>>().also { picking = it }
            if (multiple) openMany.launch(types) else openOne.launch(types)
            result
        }.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (url.host == APP_HOST) return false
                    openExternal(url)
                    return true
                }
            }
        }
        val bridge = WebBridge(this, graph, ::runJs, ::askNotificationPermission, picker)
        webView.addJavascriptInterface(bridge, "PoorDSHNative")

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // Edge-to-edge is enforced on Android 15+: keep the page clear of system bars and the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.__poordsh&&window.__poordsh.onBack&&window.__poordsh.onBack()") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        lifecycleScope.launch {
            graph.chats.uiEvents.collect { e ->
                if (e.optString("type") == "app.exit") finishAndRemoveTask()
                else runJs("window.__poordsh&&window.__poordsh.onEvent(${e.toJsLiteral()})")
            }
        }

        webView.loadUrl("https://$APP_HOST/assets/webui/index.html")
        // Engines are not started here: they start when a chat needs them.
        graph.runtime.ensureReady()
        graph.engines.discoverMissing()
    }

    override fun onStart() {
        super.onStart()
        // Each time the app comes up, engine updates are checked (and installed with
        // auto update on); a return within minutes of the last check does not repeat it.
        graph.engineUpdates.onAppOpened()
    }

    private fun runJs(script: String) {
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runOnUiThread { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("PoorDSHNative")
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
    }
}
