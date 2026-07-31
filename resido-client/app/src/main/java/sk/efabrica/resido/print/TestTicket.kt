package sk.efabrica.resido.print

/**
 * A tiny hardcoded ESC/POS ticket for the settings screen's "Test tlače"
 * button - proves the transport works without involving the web renderer.
 * ASCII only: thermal printer code pages rarely cover Slovak diacritics.
 */
object TestTicket {

    fun bytes(slotLabel: String): ByteArray {
        val text = buildString {
            append("Resido - test tlace\n")
            append(slotLabel.replace(Regex("[^\\x20-\\x7E]"), "?"))
            append("\nOK\n")
        }

        return byteArrayOf(0x1B, 0x40) + // ESC @ init
            byteArrayOf(0x1B, 0x61, 0x01) + // ESC a 1 - center
            text.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x1B, 0x64, 0x04) + // feed
            byteArrayOf(0x1D, 0x56, 0x42, 0x00) // partial cut
    }
}
