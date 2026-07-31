package sk.efabrica.resido.prefs

/**
 * One configured printer slot. Persisted as a compact string so the whole
 * config survives in SharedPreferences without JSON dependencies:
 *
 *  - ""                     -> None
 *  - "net:192.168.1.200:9100" -> Network
 *  - "bt:AA:BB:CC:DD:EE:FF|My Printer" -> Bluetooth (name is display-only)
 */
sealed class PrinterConfig {

    object None : PrinterConfig()

    data class Network(val host: String, val port: Int) : PrinterConfig()

    data class Bluetooth(val mac: String, val name: String) : PrinterConfig()

    fun encode(): String = when (this) {
        is None -> ""
        is Network -> "net:$host:$port"
        is Bluetooth -> "bt:$mac|$name"
    }

    companion object {
        const val DEFAULT_NETWORK_PORT = 9100

        fun decode(raw: String?): PrinterConfig {
            if (raw.isNullOrBlank()) {
                return None
            }

            return when {
                raw.startsWith("net:") -> {
                    val rest = raw.removePrefix("net:")
                    val host = rest.substringBeforeLast(':', missingDelimiterValue = "")
                    val port = rest.substringAfterLast(':', missingDelimiterValue = "")
                        .toIntOrNull()

                    if (host.isBlank() || port == null || port !in 1..65535) {
                        None
                    } else {
                        Network(host, port)
                    }
                }

                raw.startsWith("bt:") -> {
                    val rest = raw.removePrefix("bt:")
                    val mac = rest.substringBefore('|')
                    val name = rest.substringAfter('|', missingDelimiterValue = "")

                    if (mac.isBlank()) None else Bluetooth(mac, name)
                }

                else -> None
            }
        }
    }
}
