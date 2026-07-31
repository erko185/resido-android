package sk.efabrica.resido

import android.app.Application
import android.webkit.WebView

class ResidoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Must run before any WebView is created: allows draw() to rasterize
        // the whole document instead of just the visible tiles - required by
        // ReceiptRenderer to capture full-height receipts.
        WebView.enableSlowWholeDocumentDraw()
    }
}
