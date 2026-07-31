package sk.efabrica.resido

import org.junit.Assert.assertEquals
import org.junit.Test
import sk.efabrica.resido.prefs.PrinterConfig

class PrinterConfigTest {

    @Test
    fun `decodes empty and null as None`() {
        assertEquals(PrinterConfig.None, PrinterConfig.decode(null))
        assertEquals(PrinterConfig.None, PrinterConfig.decode(""))
        assertEquals(PrinterConfig.None, PrinterConfig.decode("garbage"))
    }

    @Test
    fun `round-trips network printer`() {
        val config = PrinterConfig.Network("192.168.1.200", 9100)

        assertEquals(config, PrinterConfig.decode(config.encode()))
        assertEquals("net:192.168.1.200:9100", config.encode())
    }

    @Test
    fun `rejects invalid network config`() {
        assertEquals(PrinterConfig.None, PrinterConfig.decode("net:"))
        assertEquals(PrinterConfig.None, PrinterConfig.decode("net:hostonly"))
        assertEquals(PrinterConfig.None, PrinterConfig.decode("net:host:notaport"))
        assertEquals(PrinterConfig.None, PrinterConfig.decode("net:host:0"))
        assertEquals(PrinterConfig.None, PrinterConfig.decode("net:host:70000"))
    }

    @Test
    fun `round-trips bluetooth printer including MAC colons`() {
        val config = PrinterConfig.Bluetooth("AA:BB:CC:DD:EE:FF", "Termo 58")

        assertEquals(config, PrinterConfig.decode(config.encode()))
        assertEquals("bt:AA:BB:CC:DD:EE:FF|Termo 58", config.encode())
    }

    @Test
    fun `bluetooth without name decodes with empty name`() {
        assertEquals(
            PrinterConfig.Bluetooth("AA:BB:CC:DD:EE:FF", ""),
            PrinterConfig.decode("bt:AA:BB:CC:DD:EE:FF")
        )
    }
}
