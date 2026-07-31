package sk.efabrica.resido.web

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri

/**
 * Navigation policy for the main WebView, mirroring the desktop client's
 * setWindowOpenHandler + did-fail-load behavior: internal URLs stay in the
 * app, external URLs open in the system browser, main-frame load failures
 * surface the native offline screen.
 */
class ResidoWebViewClient(
    private val serverUrl: () -> String,
    private val onInternalPageChanged: (WebView) -> Unit,
    private val onMainFrameError: () -> Unit,
) : WebViewClient() {

    private var mainFrameFailed = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()

        if (UrlPolicy.isInternalAppUrl(url, serverUrl())) {
            return false
        }

        // External links (including window.open to another origin) go to the
        // OS, same as shell.openExternal on the desktop.
        try {
            view.context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            // No handler for the scheme - swallow, never crash the shell.
        }

        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        mainFrameFailed = false
        // Fallback for WebView versions without document-start script
        // support; the shim is idempotent so double-installation is fine.
        view.evaluateJavascript(PageScripts.BRIDGE_SHIM_JS, null)
    }

    override fun onPageFinished(view: WebView, url: String) {
        // Persist the Laravel session cookie so a restart keeps the login.
        CookieManager.getInstance().flush()

        if (!mainFrameFailed) {
            onInternalPageChanged(view)
            injectIntoInternalPage(view)
        } else {
            onMainFrameError()
        }
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        // Covers Livewire/SPA in-page navigation (the desktop client's
        // did-navigate-in-page) - e.g. leaving a print page must remove the
        // injected back button.
        injectIntoInternalPage(view)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            mainFrameFailed = true
        }
    }

    private fun injectIntoInternalPage(view: WebView) {
        if (!UrlPolicy.isInternalAppUrl(view.url, serverUrl())) {
            return
        }

        view.evaluateJavascript(PageScripts.BRIDGE_SHIM_JS, null)
        view.evaluateJavascript(PageScripts.BUTTONS_JS, null)
    }
}
