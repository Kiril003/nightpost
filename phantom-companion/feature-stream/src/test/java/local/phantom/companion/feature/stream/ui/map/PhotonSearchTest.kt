package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.map.GeoEndpointsConfig
import local.phantom.companion.core.net.map.GeocoderKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * K5 / D7: online place search goes straight from the phone to Photon, and the
 * only position it carries is rounded to 0.01° (about a kilometre).
 * The "Photon" here is a loopback server: no live call leaves the test.
 */
class PhotonSearchTest {
    private val seen = CopyOnWriteArrayList<String>()
    private var server: ServerSocket? = null

    private fun fakePhoton(): String {
        val sock = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = sock
        thread(isDaemon = true) {
            while (!sock.isClosed) {
                val s = runCatching { sock.accept() }.getOrNull() ?: break
                s.use { c ->
                    val input = c.getInputStream().bufferedReader(Charsets.UTF_8)
                    val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    head.firstOrNull()?.split(' ')?.getOrNull(1)?.let { seen += it }
                    val bytes = """{"type":"FeatureCollection","features":[]}""".toByteArray()
                    c.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        return "http://127.0.0.1:${sock.localPort}"
    }

    @After
    fun stop() {
        server?.close()
    }

    private fun params(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun `bias is my position rounded to about a kilometre`() {
        assertEquals(49.84 to 18.28, kmBias(49.8352 to 18.2831))
        assertEquals(50.45 to 30.52, kmBias(50.4501 to 30.5234))
        assertEquals(-33.87 to -70.65, kmBias(-33.8688 to -70.6512))
    }

    @Test
    fun `search url carries the rounded bias and nothing more precise`() {
        val p = params(photonSearchUrl("https://p", "cafe", 5, "en", 49.8352 to 18.2831))
        assertEquals("49.84", p["lat"])
        assertEquals("18.28", p["lon"])
        assertEquals("cafe", p["q"])
        assertEquals(setOf("q", "limit", "lang", "lat", "lon"), p.keys)
    }

    @Test
    fun `without a position the url is the plain search`() {
        assertEquals("https://p/api?q=cafe&limit=5&lang=en", photonSearchUrl("https://p", "cafe", 5, "en", null))
    }

    @Test
    fun `the client sends the rounded bias to Photon`() = runBlocking {
        val base = fakePhoton()
        PhotonClient(baseUrl = base, locale = java.util.Locale.ENGLISH).search("pizza", 5, near = 49.8352 to 18.2831)
        val p = params(seen.single())
        assertEquals("49.84", p["lat"])
        assertEquals("18.28", p["lon"])
        assertFalse(seen.single().contains("49.8352"))
    }

    @Test
    fun `with no search server of our own nothing leaves the phone`() = runBlocking {
        for (cfg in listOf(GeoEndpointsConfig.DISABLED, null)) {
            val client = placeSearchFor(cfg)
            assertFalse(client is PhotonClient || client is NominatimClient)
            assertTrue(client.search("pizza", 5, near = 50.45 to 30.52).isEmpty())
            assertNull(client.reverse(50.45, 30.52))
        }
    }

    @Test
    fun `reverse on a client without reverse makes no request`() = runBlocking {
        val base = fakePhoton()
        assertNull(PhotonClient(baseUrl = base, reverseAllowed = false).reverse(50.45, 30.52))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `our own search server still wins`() {
        val own = GeoEndpointsConfig.DISABLED.copy(geocodeBaseUrl = "http://10.0.2.2:8088", geocoderKind = GeocoderKind.NOMINATIM)
        assertTrue(placeSearchFor(own) is NominatimClient)
    }
}
