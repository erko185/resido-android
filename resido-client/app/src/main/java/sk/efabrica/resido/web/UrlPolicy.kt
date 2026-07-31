package sk.efabrica.resido.web

import java.net.URI

/**
 * URL validation shared by the WebView navigation policy and the print
 * pipeline - a port of isValidHttpUrl/isInternalAppUrl from the desktop
 * client (resido.ps1).
 */
object UrlPolicy {

    fun isValidHttpUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) {
            return false
        }

        return try {
            val uri = URI(value)
            (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /** True when [url] shares scheme+host+port with the configured [serverUrl]. */
    fun isInternalAppUrl(url: String?, serverUrl: String?): Boolean {
        if (!isValidHttpUrl(url) || !isValidHttpUrl(serverUrl)) {
            return false
        }

        return try {
            origin(URI(url)) == origin(URI(serverUrl))
        } catch (_: Exception) {
            false
        }
    }

    private fun origin(uri: URI): String {
        val port = if (uri.port != -1) {
            uri.port
        } else {
            if (uri.scheme == "https") 443 else 80
        }

        return "${uri.scheme}://${uri.host?.lowercase()}:$port"
    }
}
