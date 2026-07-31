package sk.efabrica.resido.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persistent client settings - the Android counterpart of the desktop
 * client's electron-store ({serverUrl, printer, printer2..printer5}).
 * Slot 1 is the receipt printer, slots 2-5 are the bon printers.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("resido", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value) }

    fun printer(slot: Int): PrinterConfig =
        PrinterConfig.decode(prefs.getString(printerKey(slot), ""))

    fun setPrinter(slot: Int, config: PrinterConfig) {
        prefs.edit { putString(printerKey(slot), config.encode()) }
    }

    private fun printerKey(slot: Int): String {
        require(slot in 1..PRINTER_SLOTS) { "Invalid printer slot: $slot" }

        return "printer$slot"
    }

    companion object {
        const val PRINTER_SLOTS = 5

        private const val KEY_SERVER_URL = "serverUrl"
    }
}
