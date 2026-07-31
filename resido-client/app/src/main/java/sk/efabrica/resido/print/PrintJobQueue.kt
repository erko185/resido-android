package sk.efabrica.resido.print

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sk.efabrica.resido.prefs.Prefs
import sk.efabrica.resido.prefs.PrinterConfig
import sk.efabrica.resido.web.UrlPolicy

/**
 * Serializes silent print jobs coming from the JS bridge. One job at a time:
 * the off-screen renderer is single-instance, and sequential jobs are also
 * what keeps multi-printer bon fan-out in order (parity with the desktop
 * client's sequential print windows).
 *
 * Every failure path resolves {ok:false} - the server-side JS then falls
 * back to window.open, so printing problems never crash or block the board.
 */
class PrintJobQueue(
    context: Context,
    scope: CoroutineScope,
    private val prefs: Prefs,
    private val renderer: ReceiptRenderer,
    private val onResolve: (requestId: String, ok: Boolean) -> Unit,
) {

    private data class Job(val requestId: String, val url: String, val slot: Int)

    private val appContext = context.applicationContext
    private val jobs = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in jobs) {
                val ok = try {
                    process(job)
                } catch (_: Exception) {
                    false
                }

                onResolve(job.requestId, ok)
            }
        }
    }

    fun enqueue(requestId: String, url: String, slot: Int) {
        jobs.trySend(Job(requestId, url, slot))
    }

    private suspend fun process(job: Job): Boolean {
        val config = prefs.printer(job.slot)
        if (config is PrinterConfig.None) {
            // Unconfigured slot -> web fallback. Unlike Windows there is no
            // OS-default ESC/POS printer to fall back to for slot 1.
            return false
        }

        if (!UrlPolicy.isInternalAppUrl(job.url, prefs.serverUrl)) {
            return false
        }

        val uri = Uri.parse(job.url)
        val paperWidthMm = uri.getQueryParameter("paperWidth")?.toIntOrNull() ?: 0
        // The page must not run its own window.print() while rendering.
        val loadUrl = removeQueryParameter(uri, "autoprint").toString()

        val rendered = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            renderer.render(loadUrl, paperWidthMm)
        } ?: return false

        return try {
            val transport = PrinterTransport.forConfig(appContext, config) ?: return false

            // Encoding walks a couple of million pixels - keep it off the
            // main thread so printing cannot freeze the board UI.
            val payload = withContext(kotlinx.coroutines.Dispatchers.Default) {
                val singleCopy = EscPosEncoder.encode(rendered.bitmap)

                // N copies as one session of N full streams - the cut command
                // at the end of each stream separates the copies on the roll.
                ByteArray(singleCopy.size * rendered.copies).also { combined ->
                    for (copy in 0 until rendered.copies) {
                        singleCopy.copyInto(combined, copy * singleCopy.size)
                    }
                }
            }

            withContext(Dispatchers.IO) {
                transport.send(payload)
            }

            true
        } catch (e: Exception) {
            android.util.Log.w("ResidoPrint", "Silent print failed (slot ${job.slot})", e)
            false
        } finally {
            rendered.bitmap.recycle()
        }
    }

    private fun removeQueryParameter(uri: Uri, name: String): Uri {
        val builder = uri.buildUpon().clearQuery()

        for (key in uri.queryParameterNames) {
            if (key == name) {
                continue
            }

            for (value in uri.getQueryParameters(key)) {
                builder.appendQueryParameter(key, value)
            }
        }

        return builder.build()
    }

    private companion object {
        // Render + measure + draw; generous because slow tablets load the
        // receipt page over WiFi first.
        const val RENDER_TIMEOUT_MS = 20_000L
    }
}
