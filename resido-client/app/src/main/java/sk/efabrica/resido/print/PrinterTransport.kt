package sk.efabrica.resido.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import sk.efabrica.resido.prefs.PrinterConfig
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Raw byte transport to a thermal printer. Implementations throw IOException
 * on any failure - callers translate that into the {ok:false} bridge result
 * so the web app's window.open fallback kicks in.
 */
interface PrinterTransport {

    @Throws(IOException::class)
    fun send(data: ByteArray)

    companion object {
        fun forConfig(context: Context, config: PrinterConfig): PrinterTransport? = when (config) {
            is PrinterConfig.None -> null
            is PrinterConfig.Network -> TcpPrinterTransport(config.host, config.port)
            is PrinterConfig.Bluetooth -> BluetoothPrinterTransport(context, config.mac)
        }
    }
}

class TcpPrinterTransport(
    private val host: String,
    private val port: Int,
) : PrinterTransport {

    override fun send(data: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            socket.getOutputStream().apply {
                write(data)
                flush()
            }

            // Signal end-of-data and drain whatever the printer answers before
            // closing - some printers drop the job tail if the socket
            // disappears immediately after the last write.
            socket.shutdownOutput()
            try {
                val input = socket.getInputStream()
                while (input.read() != -1) {
                    // Discard status bytes until the printer closes its side.
                }
            } catch (_: IOException) {
                // Read timeout while draining is fine - the data was written.
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 10_000
    }
}

class BluetoothPrinterTransport(
    private val context: Context,
    private val mac: String,
) : PrinterTransport {

    // Connect permission is verified explicitly before any Bluetooth call.
    @SuppressLint("MissingPermission")
    override fun send(data: ByteArray) {
        if (!hasConnectPermission(context)) {
            throw IOException("Missing BLUETOOTH_CONNECT permission")
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
            ?: throw IOException("Bluetooth is not available")

        if (!adapter.isEnabled) {
            throw IOException("Bluetooth is disabled")
        }

        val device = adapter.getRemoteDevice(mac)

        // Best-effort only: cancelDiscovery needs BLUETOOTH_SCAN on API 31+,
        // which this app neither declares nor needs (we never discover, only
        // talk to bonded devices) - without the catch it would kill every
        // print with a SecurityException.
        try {
            adapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }

        device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
            socket.connect()
            val output = socket.outputStream

            // Cheap BT printers have tiny input buffers and no working flow
            // control - dumping a whole raster receipt at once overflows them
            // and the tail comes out as garbage characters. Feed the data in
            // small chunks with short pauses instead.
            var offset = 0
            while (offset < data.size) {
                val length = minOf(BT_WRITE_CHUNK_BYTES, data.size - offset)
                output.write(data, offset, length)
                output.flush()
                offset += length

                if (offset < data.size) {
                    Thread.sleep(BT_WRITE_CHUNK_DELAY_MS)
                }
            }

            // SPP has no delivery acknowledgement; give the printer a moment
            // to consume its input buffer before the socket goes away.
            Thread.sleep(FLUSH_DELAY_MS)
        }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val FLUSH_DELAY_MS = 500L
        private const val BT_WRITE_CHUNK_BYTES = 512
        private const val BT_WRITE_CHUNK_DELAY_MS = 15L

        fun hasConnectPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return true
            }

            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
