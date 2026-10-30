package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class NominatimClientNoPublicTierTest {
    private val seen = CopyOnWriteArrayList<String>()
    private var server: ServerSocket? = null

    private fun emptyOwnServer(): String {
        val sock = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = sock
        thread(isDaemon = true) {
            while (!sock.isClosed) {
                val s = runCatching { sock.accept() }.getOrNull() ?: break
                s.use { c ->
                    val input = c.getInputStream().bufferedReader(Charsets.UTF_8)
                    val head = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    head.firstOrNull()?.split(' ')?.getOrNull(1)?.let { seen += it }
                    c.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".toByteArray())
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

    @Test
    fun `own server with nothing found ends the search - one request, empty answer`() = runBlocking {
        val result = NominatimClient(baseUrl = emptyOwnServer()).search("Хрещатик 22", 5, near = 50.45 to 30.52)
        assertTrue(result.isEmpty())
        assertEquals(1, seen.size)
    }

    @Test
    fun `no public geocoder host is left in map code`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "feature-stream").isDirectory }
        val code = File(root, "feature-stream/src/main/java").walk().filter { it.extension == "kt" }
            .map { f -> f.name to stripComments(f.readText()) }
        val hits = code.filter { (_, t) -> publicHost(t) }.map { it.first }.toList()
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `the scan sees a public host in code and ignores it in comments`() {
        assertTrue(publicHost(stripComments("""val u = "https://photon.komoot.io/api" // fallback""")))
        assertTrue(!publicHost(stripComments("// https://photon.komoot.io\n/* nominatim.openstreetmap.org */ val x = 1")))
    }

    private fun stripComments(t: String) =
        t.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""(?<!:)//[^\n]*"""), "")

    private fun publicHost(t: String) = "photon.komoot.io" in t || "nominatim.openstreetmap.org" in t
}
