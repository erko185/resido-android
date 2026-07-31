package sk.efabrica.resido

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import sk.efabrica.resido.prefs.Prefs
import sk.efabrica.resido.print.PrintJobQueue
import sk.efabrica.resido.print.ReceiptRenderer
import sk.efabrica.resido.update.UpdateManager
import sk.efabrica.resido.web.JsBridge
import sk.efabrica.resido.web.PageScripts
import sk.efabrica.resido.web.ResidoWebViewClient
import sk.efabrica.resido.web.UrlPolicy

class MainActivity : AppCompatActivity(), JsBridge.Host {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var offlineView: View
    private lateinit var printQueue: PrintJobQueue
    private lateinit var updateManager: UpdateManager

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Mirrors the desktop's saveSettings handler: navigate straight
            // to the operator panel after the server URL is saved.
            val serverUrl = prefs.serverUrl
            if (UrlPolicy.isValidHttpUrl(serverUrl)) {
                hideOffline()
                webView.loadUrl(serverUrl.trimEnd('/') + "/resido/")
            }
        } else if (!UrlPolicy.isValidHttpUrl(prefs.serverUrl)) {
            showOffline()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Reception tablets must not sleep mid-shift.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        webView = findViewById(R.id.web_view)
        offlineView = findViewById(R.id.offline_view)

        configureWebView()

        printQueue = PrintJobQueue(
            context = this,
            scope = lifecycleScope,
            prefs = prefs,
            // Attached (1x1 px) to the window: Chromium does not rasterize
            // window-detached WebViews on some devices, which made silent
            // prints come out blank.
            renderer = ReceiptRenderer(this, findViewById(android.R.id.content)),
            onResolve = ::resolveBridgeRequest,
        )

        updateManager = UpdateManager(this)
        if (BuildConfig.SELF_UPDATE_ENABLED) {
            // Sideload channel only - Play builds are updated by the store.
            updateManager.startPeriodicChecks(lifecycleScope)
        }

        findViewById<View>(R.id.offline_retry).setOnClickListener {
            hideOffline()
            loadConfiguredUrl()
        }
        findViewById<View>(R.id.offline_settings).setOnClickListener { openSettingsScreen() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
                // Otherwise swallow: the shell must not be exited by an
                // accidental back gesture on the POS tablet.
            }
        })

        if (UrlPolicy.isValidHttpUrl(prefs.serverUrl)) {
            loadConfiguredUrl()
        } else {
            // First run: no server configured yet - open settings directly,
            // the role offline.html plays on the desktop.
            openSettingsScreen()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Parity with the desktop client's no-cache session headers.
            cacheMode = WebSettings.LOAD_NO_CACHE
            // window.open (the print fallback) navigates in-place because
            // multiple windows stay unsupported.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(JsBridge(this), JsBridge.NAME)

        // Install the bridge shim before any page script runs so Filament's
        // capability checks (typeof reservationClient.printSilent) pass.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                PageScripts.BRIDGE_SHIM_JS,
                setOf("*"),
            )
        }

        webView.webViewClient = ResidoWebViewClient(
            serverUrl = { prefs.serverUrl },
            onInternalPageChanged = { hideOffline() },
            onMainFrameError = { showOffline() },
        )
    }

    private fun loadConfiguredUrl() {
        val serverUrl = prefs.serverUrl

        if (!UrlPolicy.isValidHttpUrl(serverUrl)) {
            showOffline()
            return
        }

        // Parity with the desktop loadConfiguredUrl: always start from a
        // clean cache so a deploy is picked up immediately.
        webView.clearCache(true)
        webView.loadUrl(serverUrl)
    }

    private fun showOffline() {
        offlineView.visibility = View.VISIBLE
    }

    private fun hideOffline() {
        offlineView.visibility = View.GONE
    }

    // JsBridge.Host - called from the WebView's JS binder thread.

    override fun enqueuePrint(requestId: String, url: String, slot: Int) {
        printQueue.enqueue(requestId, url, slot)
    }

    override fun openSettingsScreen() {
        runOnUiThread {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun resolveBridgeRequest(requestId: String, ok: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript(
                "window.__residoResolve && window.__residoResolve(${JSONObject.quote(requestId)}, {ok: $ok});",
                null,
            )
        }
    }
}
