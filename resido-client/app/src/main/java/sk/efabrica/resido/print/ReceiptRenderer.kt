package sk.efabrica.resido.print

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
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
class ReceiptRenderer(
    context: Context,
    /**
     * Optional view hierarchy to (invisibly) attach the render WebView into.
     * Some WebView builds never rasterize a window-detached view (draw() and
     * capturePicture() both yield blank output, e.g. MIUI devices) - being
     * attached, even at 1x1 px and INVISIBLE, restores pixel output.
     */
    private val attachHost: ViewGroup? = null,
) {

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
        val targetCssWidth = widthMm * CSS_PX_PER_MM

        // Pin the CSS layout viewport to the paper width via a meta viewport
        // tag - the one mechanism actually designed for this (honoured with
        // useWideViewPort=true), instead of fighting version-specific
        // initial-scale/zoom behaviours.
        view.evaluateJs(metaViewportJs(targetCssWidth))

        if (view.width != widthDots) {
            layoutWebView(view, widthDots, PROVISIONAL_HEIGHT_DOTS)
        }
        delay(RENDER_SETTLE_DELAY_MS)

        // Calibrate whatever this WebView actually did: clientWidth is the
        // real CSS layout width, view.scale the real physical-per-CSS ratio.
        // The Canvas transform bridges any remaining difference losslessly
        // (Skia scales vectors, text stays crisp).
        val clientWidthCss = view.evaluateJs(VIEWPORT_WIDTH_JS)?.toDoubleOrNull()
        @Suppress("DEPRECATION") val pageScale = view.scale.toDouble()
        if (clientWidthCss == null || clientWidthCss <= 0 || pageScale <= 0) {
            resetWebView(view)
            return@withContext null
        }

        val contentPhysicalWidth = clientWidthCss * pageScale
        val renderScale = (widthDots / contentPhysicalWidth).toFloat()

        val heightCssPx = measure(view)?.heightCssPx ?: measurement.heightCssPx
        val contentPhysicalHeight = ceil(heightCssPx * pageScale).toInt().coerceAtLeast(1)
        layoutWebView(view, widthDots, contentPhysicalHeight)
        delay(RENDER_SETTLE_DELAY_MS)

        val contentHeightDots = ceil(contentPhysicalHeight * renderScale).toInt() + HEIGHT_BUFFER_MM * DOTS_PER_MM
        val heightDots = contentHeightDots.coerceIn(MIN_HEIGHT_DOTS, MAX_HEIGHT_DOTS)

        android.util.Log.i(
            "ResidoPrint",
            "render: widthMm=$widthMm widthDots=$widthDots clientWidthCss=$clientWidthCss " +
                "pageScale=$pageScale renderScale=$renderScale heightCss=$heightCssPx heightDots=$heightDots"
        )

        // Chromium produces the first rasterizable frame asynchronously - a
        // draw() too early yields a blank bitmap on some devices. Re-layout
        // and redraw until pixels appear (each attempt re-applies the layout,
        // because attached views get re-measured back to their placeholder
        // size by the hierarchy's own traversals).
        var bitmap: Bitmap? = null

        for (attempt in 1..DRAW_ATTEMPTS) {
            layoutWebView(view, widthDots, contentPhysicalHeight)
            view.invalidate()
            val candidate = drawToBitmap(view, widthDots, heightDots, renderScale)

            if (!isBlank(candidate)) {
                if (attempt > 1) {
                    android.util.Log.i("ResidoPrint", "render: pixels appeared on draw attempt $attempt")
                }
                bitmap = candidate
                break
            }

            candidate.recycle()
            delay(DRAW_RETRY_DELAY_MS)
        }

        resetWebView(view)

        if (bitmap == null) {
            // Better a browser-window fallback than feeding blank paper out.
            android.util.Log.w("ResidoPrint", "render: bitmap stayed blank, giving up")
            return@withContext null
        }

        Rendered(bitmap, measurement.copies)
    }

    private fun drawToBitmap(view: WebView, widthDots: Int, heightDots: Int, renderScale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(widthDots, heightDots, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        canvas.scale(renderScale, renderScale)
        view.draw(canvas)

        return bitmap
    }

    /** Cheap sparse scan for any non-white pixel. */
    private fun isBlank(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        var y = 0
        val row = IntArray(width)

        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width step 4) {
                if (row[x] and 0xFFFFFF != 0xFFFFFF) {
                    return false
                }
            }
            y += 16
        }

        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView {
        webView?.let { return it }

        val view = WebView(appContext)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            // Honour the meta viewport tag the renderer injects (pins the CSS
            // layout width to the paper width) and auto-fit it to the view.
            useWideViewPort = true
            loadWithOverviewMode = true
            // WebView multiplies CSS text sizes by the system font-size
            // setting by default - a "large font" device would print bigger
            // text that overflows the paper width. Print output must be
            // deterministic, identical to the desktop client.
            textZoom = 100
            // Pin the font environment to desktop-Chromium defaults - vendors
            // override these (e.g. MIUI bumps the fixed font size), which
            // reflows the receipt's monospace layout.
            defaultFontSize = 16
            defaultFixedFontSize = 13
            minimumFontSize = 1
            minimumLogicalFontSize = 1
        }
        // Scrollbars would be rasterized right into the receipt.
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        // NOTE: do NOT setLayerType(LAYER_TYPE_SOFTWARE) here - modern
        // Chromium WebView does not support software layers and renders
        // nothing at all with one.

        attachHost?.let { host ->
            // VISIBLE on purpose: Chromium only rasterizes web content for
            // views it considers shown - INVISIBLE/detached ones draw blank
            // on some devices. A 1x1 px container is imperceptible, clips the
            // web content away, and - crucially - swallows the WebView's
            // requestLayout() storms so printing cannot jank or freeze the
            // activity's real UI.
            val isolator = RenderHostView(host.context)
            host.addView(isolator, ViewGroup.LayoutParams(1, 1))
            isolator.addView(view, ViewGroup.LayoutParams(1, 1))
            isolator.swallowLayoutRequests = true
        }

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
     * Keeps the render WebView attached (so Chromium rasterizes it) while
     * isolating the rest of the view hierarchy from its layout requests -
     * without this every print re-layouts the whole activity repeatedly,
     * which shows up as UI flicker and multi-second freezes.
     */
    private class RenderHostView(context: Context) : android.widget.FrameLayout(context) {
        var swallowLayoutRequests = false

        override fun requestLayout() {
            if (!swallowLayoutRequests) {
                super.requestLayout()
            }
        }
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

        /** CSS renders at 96 px/inch. */
        private const val CSS_PX_PER_MM = 96.0 / 25.4

        /** Dots per CSS px = 8 / (96/25.4) ~ 2.1167. */
        private const val DOTS_PER_CSS_PX = DOTS_PER_MM / CSS_PX_PER_MM

        /** Real CSS layout viewport width (excludes any pinch/visual zoom). */
        private const val VIEWPORT_WIDTH_JS = "document.documentElement.clientWidth"

        private const val DRAW_ATTEMPTS = 15
        private const val DRAW_RETRY_DELAY_MS = 400L

        /** Injects/overwrites the page's meta viewport with a fixed width. */
        fun metaViewportJs(targetCssWidth: Double): String {
            val width = targetCssWidth.roundToInt()

            return """
                (() => {
                    let meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.setAttribute('name', 'viewport');
                        document.head.appendChild(meta);
                    }
                    meta.setAttribute('content', 'width=$width');
                    return 'vp';
                })()
            """
        }

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
