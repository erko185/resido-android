package sk.efabrica.resido.web

import android.webkit.JavascriptInterface

/**
 * Native half of the window.reservationClient bridge. The injected shim
 * (PageScripts.BRIDGE_SHIM_JS) wraps these synchronous entry points into
 * Promises: each call carries a request id, and the host resolves it later
 * via window.__residoResolve(id, {ok}) on the main WebView.
 *
 * Slot numbering matches the desktop client: 1 = receipt printer
 * (printSilent), 2-5 = bon printers (printSilentBon .. printSilentBonFour).
 *
 * All methods run on the WebView's JS binder thread - implementations must
 * hop to the right thread themselves.
 */
class JsBridge(private val host: Host) {

    interface Host {
        fun enqueuePrint(requestId: String, url: String, slot: Int)

        fun openSettingsScreen()
    }

    @JavascriptInterface
    fun printSilent(requestId: String, url: String) = host.enqueuePrint(requestId, url, 1)

    @JavascriptInterface
    fun printSilentBon(requestId: String, url: String) = host.enqueuePrint(requestId, url, 2)

    @JavascriptInterface
    fun printSilentBonTwo(requestId: String, url: String) = host.enqueuePrint(requestId, url, 3)

    @JavascriptInterface
    fun printSilentBonThree(requestId: String, url: String) = host.enqueuePrint(requestId, url, 4)

    @JavascriptInterface
    fun printSilentBonFour(requestId: String, url: String) = host.enqueuePrint(requestId, url, 5)

    @JavascriptInterface
    fun openSettings() = host.openSettingsScreen()

    companion object {
        /** JS global the native object is attached under. */
        const val NAME = "__residoNative"
    }
}
