package sk.efabrica.resido.print

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Renders a receipt/bon print URL into a bitmap at thermal-printer
 * resolution using a reusable off-screen WebView. This replaces the desktop
 * client's hidden BrowserWindow + webContents.print flow: the page is
 * rasterized here and sent to the printer as ESC/POS raster data.
 */
class ReceiptRenderer(context: Context) {

    data class Rendered(val bitmap: Bitmap, val copies: Int)

    private val appContext = context.applicationContext
    private var webView: WebView? = null
    private var pageLoadContinuation: CancellableContinuation<Boolean>? = null

    /**
     * Loads [url] and returns the rasterized page, or null on any failure.
     * Must be called from a context where main-thread work is acceptable;
     * callers serialize invocations (PrintJobQueue) - this class is not
     * re-entrant.
     */
    suspend fun render(url: String, paperWidthMmFromUrl: Int): Rendered? = withContext(Dispatchers.Main) {
        val view = obtainWebView()

        // Initial viewport width: bon URLs carry paperWidth; receipts get a
        // provisional width and are re-laid-out after --receipt-width is read.
        val initialWidthMm = if (paperWidthMmFromUrl > 0) paperWidthMmFromUrl else DEFAULT_WIDTH_MM
        layoutWebView(view, mmToDots(initialWidthMm), PROVISIONAL_HEIGHT_DOTS)

        val loaded = awaitPageLoad(view, url)
        if (!loaded) {
            resetWebView(view)
            return@withContext null
        }

        awaitPageReady(view)
        view.evaluateJs(ACTIVATE_PRINT_CSS_JS)
        delay(RENDER_SETTLE_DELAY_MS)

        val measurement = measure(view) ?: run {
            resetWebView(view)
            return@withContext null
        }

        val widthMm = when {
            paperWidthMmFromUrl > 0 -> paperWidthMmFromUrl
            measurement.widthMm > 0 -> measurement.widthMm
            else -> DEFAULT_WIDTH_MM
        }
        val widthDots = mmToDots(widthMm)

        // Re-layout when the CSS-declared width differs from the provisional one.
        if (widthDots != view.width) {
            layoutWebView(view, widthDots, PROVISIONAL_HEIGHT_DOTS)
            delay(RENDER_SETTLE_DELAY_MS)
        }

        // Self-calibrating scale: whatever page scale the WebView chose, the
        // measured CSS viewport width tells us the effective device-px-per-
        // CSS-px ratio, and a CSS zoom re-lays the content out at exactly
        // widthMm of CSS millimetres across the widthDots-wide bitmap.
        if (!applyCalibratedZoom(view, widthMm)) {
            resetWebView(view)
            return@withContext null
        }
        delay(RENDER_SETTLE_DELAY_MS)

        // Height measured after the zoom, in the content's own coordinate
        // space - device px = css px * DOTS_PER_CSS_PX by construction.
        val heightCssPx = measure(view)?.heightCssPx ?: measurement.heightCssPx
        val contentHeightDots = ceil(heightCssPx * DOTS_PER_CSS_PX).toInt() + HEIGHT_BUFFER_MM * DOTS_PER_MM
        val heightDots = contentHeightDots.coerceIn(MIN_HEIGHT_DOTS, MAX_HEIGHT_DOTS)

        layoutWebView(view, widthDots, heightDots)
        delay(RENDER_SETTLE_DELAY_MS)

        val bitmap = Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        view.draw(Canvas(bitmap))

        resetWebView(view)

        Rendered(bitmap, measurement.copies)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView {
        webView?.let { return it }

        val view = WebView(appContext)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            // The layout viewport must be exactly layoutWidth/scale CSS px -
            // ignore the page's viewport meta so the receipt width is under
            // our control (mirrors the desktop's fixed print pageSize).
            useWideViewPort = false
            loadWithOverviewMode = false
            // WebView multiplies CSS text sizes by the system font-size
            // setting by default - a "large font" device would print bigger
            // text that overflows the paper width. Print output must be
            // deterministic, identical to the desktop client.
            textZoom = 100
        }
        // No setInitialScale here: WebView versions differ in whether they
        // honour it (density interplay), which broke print sizing on real
        // devices. The scale is self-calibrated per print instead - the
        // renderer measures window.innerWidth and injects a CSS zoom so the
        // content lays out at exactly the paper width (see applyCalibratedZoom).

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageLoadContinuation?.takeIf { it.isActive }?.resume(true)
                pageLoadContinuation = null
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    pageLoadContinuation?.takeIf { it.isActive }?.resume(false)
                    pageLoadContinuation = null
                }
            }
        }

        webView = view

        return view
    }

    private suspend fun awaitPageLoad(view: WebView, url: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            pageLoadContinuation = continuation
            continuation.invokeOnCancellation { pageLoadContinuation = null }
            view.loadUrl(url)
        }

    /** Polls until the document, fonts and images are fully loaded. */
    private suspend fun awaitPageReady(view: WebView) {
        repeat(READY_POLL_ATTEMPTS) {
            val ready = view.evaluateJs(READY_CHECK_JS)
            if (ready == "true") {
                return
            }

            delay(READY_POLL_INTERVAL_MS)
        }
    }

    private data class Measurement(val widthMm: Int, val heightCssPx: Double, val copies: Int)

    private suspend fun measure(view: WebView): Measurement? {
        val raw = view.evaluateJs(MEASURE_JS) ?: return null

        return try {
            // evaluateJavascript JSON-encodes the returned string, so the
            // payload is a JSON string containing a JSON object.
            val unwrapped = JSONTokener(raw).nextValue() as? String ?: return null
            val json = JSONObject(unwrapped)
            val widthMm = parseMmValue(json.optString("widthVar"))
            val heightCssPx = json.optDouble("heightPx", 0.0)
            val copies = json.optInt("copies", 1).coerceIn(1, MAX_COPIES)

            if (heightCssPx <= 0) null else Measurement(widthMm, heightCssPx, copies)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Measures the actual CSS viewport width and injects a CSS zoom so the
     * content lays out at exactly [widthMm] CSS millimetres across the
     * WebView's physical width. Immune to setInitialScale/density/font-scale
     * differences between WebView versions (the same technique the desktop
     * client uses for its off-screen print window).
     */
    private suspend fun applyCalibratedZoom(view: WebView, widthMm: Int): Boolean {
        val innerWidthCss = view.evaluateJs("window.innerWidth")?.toDoubleOrNull() ?: return false

        if (innerWidthCss <= 0) {
            return false
        }

        val targetCssWidth = widthMm * (96.0 / 25.4)
        val zoom = innerWidthCss / targetCssWidth
        view.evaluateJs("document.documentElement.style.zoom = '$zoom'")

        return true
    }

    private fun layoutWebView(view: WebView, widthDots: Int, heightDots: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthDots, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightDots, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthDots, heightDots)
    }

    private fun resetWebView(view: WebView) {
        view.loadUrl("about:blank")
    }

    private fun mmToDots(mm: Int): Int = mm * DOTS_PER_MM

    companion object {
        /** Standard thermal printer resolution: 203 dpi ~ 8 dots per mm. */
        private const val DOTS_PER_MM = 8

        /** CSS renders at 96 px/inch; dots per CSS px = 8 / (96/25.4) ~ 2.1167. */
        private const val DOTS_PER_CSS_PX = DOTS_PER_MM / (96.0 / 25.4)

        /** Printable width fallback when neither URL nor CSS declare one. */
        private const val DEFAULT_WIDTH_MM = 72

        private const val HEIGHT_BUFFER_MM = 3
        private const val MIN_HEIGHT_DOTS = 50 * DOTS_PER_MM
        private const val MAX_HEIGHT_DOTS = 2000 * DOTS_PER_MM
        private const val PROVISIONAL_HEIGHT_DOTS = 200 * DOTS_PER_MM
        private const val MAX_COPIES = 5

        private const val READY_POLL_ATTEMPTS = 40
        private const val READY_POLL_INTERVAL_MS = 250L
        private const val RENDER_SETTLE_DELAY_MS = 200L

        /** Port of parseMmValue from resido.ps1 - extracts "75" from "75mm". */
        fun parseMmValue(rawValue: String?): Int {
            val match = Regex("([\\d.]+)\\s*mm").find(rawValue.orEmpty()) ?: return 0
            val mm = match.groupValues[1].toDoubleOrNull() ?: return 0

            return if (mm > 0) mm.roundToInt() else 0
        }

        private const val READY_CHECK_JS = """
            (() => {
                try {
                    return document.readyState === 'complete'
                        && (!document.fonts || document.fonts.status === 'loaded')
                        && Array.from(document.images).every((img) => img.complete);
                } catch (e) {
                    return document.readyState === 'complete';
                }
            })()
        """

        /**
         * Android WebView renders screen media and offers no print-media
         * emulation, so @media print rule bodies are copied into a regular
         * <style> element to win the cascade - this is what activates the
         * receipt/bon print stylesheets (width from --receipt-width etc.).
         */
        private const val ACTIVATE_PRINT_CSS_JS = """
            (() => {
                if (window.__residoPrintCssApplied) return 'done';
                window.__residoPrintCssApplied = true;
                let css = '';
                for (const sheet of Array.from(document.styleSheets)) {
                    let rules;
                    try { rules = sheet.cssRules; } catch (e) { continue; }
                    if (!rules) continue;
                    for (const rule of Array.from(rules)) {
                        if (rule.type === CSSRule.MEDIA_RULE
                            && /(^|[^-\w])print/.test(rule.media.mediaText)
                            && !/not\s+print/.test(rule.media.mediaText)) {
                            for (const inner of Array.from(rule.cssRules)) {
                                css += inner.cssText + '\n';
                            }
                        }
                    }
                }
                if (css) {
                    const style = document.createElement('style');
                    style.textContent = css;
                    document.head.appendChild(style);
                }
                return 'done';
            })()
        """

        /** Port of measurePrintLayout + readPrintCopies from resido.ps1. */
        private const val MEASURE_JS = """
            (() => {
                const rootStyle = getComputedStyle(document.documentElement);
                const widthVar = rootStyle.getPropertyValue('--receipt-width')
                    || rootStyle.getPropertyValue('--bon-width');
                const heightPx = Math.max(
                    document.documentElement.scrollHeight,
                    document.body ? document.body.scrollHeight : 0
                );
                const meta = document.querySelector('meta[name="print-copies"]');
                const copies = meta ? parseInt(meta.content, 10) : 1;
                return JSON.stringify({
                    widthVar: String(widthVar || '').trim(),
                    heightPx: heightPx,
                    copies: Number.isFinite(copies) ? copies : 1
                });
            })()
        """
    }
}

/** evaluateJavascript as a suspend function returning the raw JSON result. */
suspend fun WebView.evaluateJs(script: String): String? =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }
