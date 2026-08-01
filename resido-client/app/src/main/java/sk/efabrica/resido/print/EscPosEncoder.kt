package sk.efabrica.resido.print

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Converts a rendered receipt bitmap into an ESC/POS byte stream for thermal
 * roll printers (the Android counterpart of Electron's silent print - here
 * the receipt page is rasterized and sent as GS v 0 raster chunks).
 */
object EscPosEncoder {

    /** Luminance below this is printed black; receipts are dark text on white. */
    private const val BLACK_THRESHOLD = 160

    /**
     * Max raster rows per GS v 0 chunk. Deliberately 255, not 256: with 256
     * the header is yL=0/yH=1, and cheap printer firmwares that only honour
     * yL see "0 rows" and spew the raster data out as garbage text.
     */
    private const val MAX_CHUNK_ROWS = 255

    private val INIT = byteArrayOf(0x1B, 0x40) // ESC @

    /**
     * Feed (printer dots, 8 dots/mm -> 28mm) pushed out before each cut so
     * the printed end clears the cutter blade. The feed is raster rows
     * carrying a single dot at the left edge (a faint hairline in the
     * otherwise blank tail): every pure feed command proved unreliable
     * across firmwares - ESC d is line-spacing dependent (fell short of
     * the CK710's head-to-cutter distance), ESC J was ignored outright by
     * an XP-80 clone, and fully blank raster rows are dropped by
     * remove-blank-lines paper saving. Rows with ink cannot be dropped.
     * Must stay <= MAX_CHUNK_ROWS (single GS v 0 chunk).
     */
    private const val CUT_FEED_DOTS = 28 * 8

    // GS V 0 (full cut) - the plain m=0 form is the most widely implemented;
    // e.g. Xprinter V330N firmware in Bluetooth mode ignores the fancier
    // GS V B n (feed+partial) variant and just doesn't cut.
    private val CUT = byteArrayOf(0x1D, 0x56, 0x00)

    /**
     * Encodes one printed copy of the bitmap, including trailing feed and
     * paper cut. Copies are produced by repeating the returned stream - the
     * cut between repetitions is what separates copies on a roll printer.
     */
    fun encode(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val widthBytes = (width + 7) / 8
        val rows = Array(height) { y -> packRow(pixels, width, y, widthBytes) }
        // Print only the inked band - blank rows above the content would just
        // extend the physical head-to-cutter gap every receipt starts with.
        val firstInkRow = rows.indexOfFirst { row -> row.any { it != 0.toByte() } }
        val lastInkRow = rows.indexOfLast { row -> row.any { it != 0.toByte() } }

        val out = ByteArrayOutputStream()
        out.write(INIT)

        if (lastInkRow >= 0) {
            var y = firstInkRow
            while (y <= lastInkRow) {
                val chunkRows = minOf(MAX_CHUNK_ROWS, lastInkRow - y + 1)

                // GS v 0 m=0 xL xH yL yH <raster data>
                out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
                out.write(widthBytes and 0xFF)
                out.write((widthBytes shr 8) and 0xFF)
                out.write(chunkRows and 0xFF)
                out.write((chunkRows shr 8) and 0xFF)

                for (row in y until y + chunkRows) {
                    out.write(rows[row])
                }

                y += chunkRows
            }
        }

        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
        out.write(widthBytes and 0xFF)
        out.write((widthBytes shr 8) and 0xFF)
        out.write(CUT_FEED_DOTS and 0xFF)
        out.write((CUT_FEED_DOTS shr 8) and 0xFF)
        val feedRow = ByteArray(widthBytes).also { it[0] = 0x80.toByte() }
        repeat(CUT_FEED_DOTS) { out.write(feedRow) }
        out.write(CUT)

        return out.toByteArray()
    }

    private fun packRow(pixels: IntArray, width: Int, y: Int, widthBytes: Int): ByteArray {
        val row = ByteArray(widthBytes)
        val offset = y * width

        for (x in 0 until width) {
            val pixel = pixels[offset + x]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = (r * 299 + g * 587 + b * 114) / 1000

            if (luminance < BLACK_THRESHOLD) {
                // MSB-first: leftmost pixel is the highest bit of the byte.
                row[x / 8] = (row[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
            }
        }

        return row
    }
}
