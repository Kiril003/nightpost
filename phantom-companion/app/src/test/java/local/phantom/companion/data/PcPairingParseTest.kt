package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Додати свій пристрій»: що саме міст приймає від веба як скан. */
class PcPairingParseTest {

    private val qr = """{"v":2,"pair_id":"p-12345678","server_pub":"AAAA","exp":4102444800,"nonce":"bm9uY2U=",""" +
        """"endpoints":[{"type":"lan","url":"https://192.168.1.5:8443"}]}"""

    @Test
    fun `QR from the computer is a scan`() {
        val parsed = PcPairing.parse(qr)
        assertTrue(parsed is PcPairing.Input.Qr)
        assertEquals("p-12345678", (parsed as PcPairing.Input.Qr).qr.pairId)
    }

    @Test
    fun `digits are only the deep fallback and keep their length rule`() {
        assertEquals(PcPairing.Input.Digits("12345678"), PcPairing.parse(" 12345678 "))
        assertEquals(PcPairing.Input.Bad, PcPairing.parse("123"))
    }

    @Test
    fun `anything else is refused before any network`() {
        assertEquals(PcPairing.Input.Bad, PcPairing.parse(""))
        assertEquals(PcPairing.Input.Bad, PcPairing.parse("PH2:ZTc2:483778"))
        assertEquals(PcPairing.Input.Bad, PcPairing.parse(qr.replace("\"v\":2", "\"v\":2,\"purpose\":\"self\"")))
    }

    /** Досяжність: і веб, і рідний екран ідуть однією дорогою. */
    @Test
    fun `web bridge and native screen both reach the same pairing`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        val root = requireNotNull(dir) { "корінь дерева не знайдено" }
        val bridge = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        assertTrue(bridge.contains("seg[1] == \"pair\"") && bridge.contains("pcPairing.start(input)"))
        val vm = File(root, "app/src/main/java/local/phantom/companion/vm/PhantomViewModel.kt").readText()
        assertTrue(vm.contains("PcPairing(container).claim("))
    }
}
