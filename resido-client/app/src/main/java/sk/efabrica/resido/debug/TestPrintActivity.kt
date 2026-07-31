package sk.efabrica.resido.debug

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.efabrica.resido.print.EscPosEncoder
import sk.efabrica.resido.print.ReceiptRenderer
import sk.efabrica.resido.print.TcpPrinterTransport

/**
 * Debug-only print harness:
 *
 * adb shell am start -n sk.efabrica.resido.test/sk.efabrica.resido.debug.TestPrintActivity \
 *   --es url "http://host/receipt.html" --es host "192.168.1.10" --ei port 9100 --ei paperWidth 0
 *
 * Renders the URL through the production ReceiptRenderer and sends the
 * ESC/POS payload to the given TCP endpoint, logging progress under the
 * ResidoPrint tag. Finishes itself when done.
 */
class TestPrintActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Chromium suspends rasterization while the app is not visible -
        // wake the screen so off-screen draws actually produce pixels even
        // when the device lies locked on a desk during remote testing.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra("url")
        val host = intent.getStringExtra("host")
        val port = intent.getIntExtra("port", 9100)
        val paperWidth = intent.getIntExtra("paperWidth", 0)

        if (url == null || host == null) {
            Log.w(TAG, "TEST: missing url/host extras")
            finish()
            return
        }

        val attachHost = android.widget.FrameLayout(this)
        setContentView(attachHost)

        lifecycleScope.launch {
            try {
                Log.i(TAG, "TEST: rendering $url")
                val rendered = ReceiptRenderer(applicationContext, attachHost).render(url, paperWidth)

                if (rendered == null) {
                    Log.w(TAG, "TEST: render returned null")
                } else {
                    Log.i(
                        TAG,
                        "TEST: bitmap ${rendered.bitmap.width}x${rendered.bitmap.height}, copies ${rendered.copies}"
                    )
                    val payload = EscPosEncoder.encode(rendered.bitmap)
                    rendered.bitmap.recycle()

                    withContext(Dispatchers.IO) {
                        TcpPrinterTransport(host, port).send(payload)
                    }
                    Log.i(TAG, "TEST: sent ${payload.size} bytes to $host:$port")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TEST: failed", e)
            } finally {
                finish()
            }
        }
    }

    private companion object {
        const val TAG = "ResidoPrint"
    }
}
