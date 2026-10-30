package local.phantom.companion.core.net.peer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * Two phones on one Wi-Fi, over a real socket. Mocking the transport here
 * would test the part that cannot fail; the part that fails is the wire.
 */
class PeerLinkTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var link: PeerLink? = null
    // Ephemeral: debug and release test runs execute in parallel, and a fixed
    // port made them fight over it — a failure of the test, not of the link.
    @Volatile private var port = 0

    @After
    fun tearDown() {
        link?.stop()
        scope.cancel()
    }

    /** What the pair key would have produced; here it is just the agreed token. */
    private val receipt = "cmVjZWlwdC1mb3ItbTE"

    private fun listen(
        accept: Boolean = true,
        answer: String = receipt,
        seen: MutableList<String> = mutableListOf(),
        onHello: (suspend (String) -> String?)? = null,
    ): PeerLink {
        val l = PeerLink(
            port = 0,
            onMessage = { json -> seen += json; if (accept) answer else null },
            onHello = onHello,
            onBound = { port = it },
        )
        link = l
        l.start(scope)
        return l
    }

    private suspend fun awaitPort() {
        repeat(50) {
            if (port != 0) return
            delay(20)
        }
    }

    @Test
    fun `a message crosses and is acked`() = runBlocking {
        val seen = mutableListOf<String>()
        listen(seen = seen)
        awaitPort()
        val body = """{"v":3,"body":"виходь"}"""
        val ok = PeerLink().send("127.0.0.1", port, receipt, body)
        assertTrue("the sender must be told it landed", ok is PeerLink.Outcome.Acked)
        assertEquals(listOf(body), seen)
    }

    @Test
    fun `a refused message is not acked, so it stays owed`() = runBlocking {
        listen(accept = false)
        awaitPort()
        val ok = PeerLink().send("127.0.0.1", port, receipt, """{"v":3,"body":"x"}""")
        assertFalse(ok is PeerLink.Outcome.Acked)
    }

    @Test
    fun `nobody listening is a failure, not a crash`() = runBlocking {
        val ok = PeerLink().send("127.0.0.1", 47_413, receipt, """{"v":3}""")
        assertEquals(PeerLink.Outcome.NoContact, ok)
    }

    @Test
    fun `a body with newlines arrives whole`() = runBlocking {
        val seen = mutableListOf<String>()
        listen(seen = seen)
        awaitPort()
        val body = "{\"v\":3,\"body\":\"перший\\nдругий\"}"
        assertTrue(PeerLink().send("127.0.0.1", port, receipt, body) is PeerLink.Outcome.Acked)
        assertEquals(body, seen.single())
    }

    @Test
    fun `a port scan gets nothing and does not kill the listener`() = runBlocking {
        val seen = mutableListOf<String>()
        listen(seen = seen)
        awaitPort()
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
        }
        delay(200)
        // Still serving afterwards — one bad frame must not end the link.
        assertTrue(
            PeerLink().send("127.0.0.1", port, receipt, """{"v":3,"body":"x"}""")
                is PeerLink.Outcome.Acked,
        )
        assertTrue(seen.isNotEmpty())
    }

    @Test
    fun `a peer who answers with the wrong receipt has not confirmed anything`() = runBlocking {
        // Той, хто прийняв кадр, але не має ключа пари, напише що завгодно.
        listen(answer = "aW52ZW50ZWQtdGFn")
        awaitPort()
        val ok = PeerLink().send("127.0.0.1", port, receipt, """{"v":3,"body":"x"}""")
        assertFalse(
            "«доставлено» must hang on the receipt, not on the word ACK",
            ok is PeerLink.Outcome.Acked,
        )
    }

    @Test
    fun `an unsolicited ack from a stranger is read and dropped`() = runBlocking {
        val seen = mutableListOf<String>()
        listen(seen = seen)
        awaitPort()
        // Спостерігач, який знайшов порт у mDNS, стукає з готовим ACK. Слухач
        // мусить його пережити й нічого нікому не порахувати.
        Socket("127.0.0.1", port).use { s ->
            val tag = receipt.toByteArray()
            s.getOutputStream().write("PHANTOM/1 ACK ${tag.size}\n".toByteArray())
            s.getOutputStream().write(tag)
            s.getOutputStream().flush()
        }
        delay(200)
        assertTrue(
            PeerLink().send("127.0.0.1", port, receipt, """{"v":3,"body":"x"}""")
                is PeerLink.Outcome.Acked,
        )
        assertEquals(1, seen.size)
    }

    @Test
    fun `a hello is answered with a body of its own`() = runBlocking {
        val heard = mutableListOf<String>()
        listen(onHello = { json -> heard += json; """{"v":1,"from":"b","to":"a"}""" })
        awaitPort()

        val out = PeerLink().hello("127.0.0.1", port, """{"v":1,"from":"a","to":"b"}""")

        assertEquals("""{"v":1,"from":"b","to":"a"}""", (out as PeerLink.Outcome.Met).json)
        assertEquals(listOf("""{"v":1,"from":"a","to":"b"}"""), heard)
    }

    @Test
    fun `a hello with newlines in it arrives whole`() = runBlocking {
        val heard = mutableListOf<String>()
        listen(onHello = { json -> heard += json; "{\n\"v\":1\n}" })
        awaitPort()

        val out = PeerLink().hello("127.0.0.1", port, "{\n\"v\":1,\"from\":\"a\"\n}")

        assertEquals("{\n\"v\":1\n}", (out as PeerLink.Outcome.Met).json)
        assertEquals("{\n\"v\":1,\"from\":\"a\"\n}", heard.single())
    }

    @Test
    fun `a build from before the handshake refuses it in its own words`() = runBlocking {
        // Слухач БЕЗ обробника — це не імітація старої збірки, це вона сама:
        // та сама гілка, той самий рядок. Саме на ньому тримається відкат.
        val seen = mutableListOf<String>()
        listen(seen = seen)
        awaitPort()

        val out = PeerLink().hello("127.0.0.1", port, """{"v":1,"from":"a","to":"b"}""")

        assertTrue(out is PeerLink.Outcome.Refused)
        assertTrue(
            "старий телефон мусить бути впізнаваним, а не просто мовчазним",
            (out as PeerLink.Outcome.Refused).reason.startsWith(PeerWire.UNKNOWN_VERB),
        )
        // І він досі приймає звичайні листи — відкат мусить лишати розмову живою.
        assertTrue(
            PeerLink().send("127.0.0.1", port, receipt, """{"v":3,"body":"x"}""")
                is PeerLink.Outcome.Acked,
        )
        assertEquals(1, seen.size)
    }

    @Test
    fun `a refusal is word-for-word what an older build says`() = runBlocking {
        // Інакше будь-хто в мережі дізнається версію цього телефона одним
        // питанням: «відмовлено» сказала б нова збірка, «не знаю такого слова»
        // — стара. Стороннім обидві мусять звучати однаково.
        val refusing = PeerLink(port = 0, onHello = { null }, onBound = { port = it })
        link = refusing
        refusing.start(scope)
        awaitPort()
        val refused = PeerLink().hello("127.0.0.1", port, """{"v":1}""")
        refusing.stop()

        port = 0
        listen()
        awaitPort()
        val older = PeerLink().hello("127.0.0.1", port, """{"v":1}""")

        assertEquals(
            "нова збірка, що відмовила, мусить звучати як стара",
            (older as PeerLink.Outcome.Refused).reason,
            (refused as PeerLink.Outcome.Refused).reason,
        )
        assertTrue(refused.reason.startsWith(PeerWire.UNKNOWN_VERB))
    }

    @Test
    fun `nobody listening is told apart from somebody saying no`() = runBlocking {
        // Дві дуже різні ситуації: до одного треба стукати знову, до іншого —
        // говорити інакше. Один булеан їх не розрізняв.
        assertEquals(
            PeerLink.Outcome.NoContact,
            PeerLink().hello("127.0.0.1", 47_414, """{"v":1}"""),
        )
        listen(accept = false)
        awaitPort()
        assertEquals(
            PeerLink.Outcome.Refused("refused"),
            PeerLink().send("127.0.0.1", port, receipt, """{"v":3,"body":"x"}"""),
        )
    }

    @Test
    fun `an ack with the wrong tag is a refusal, so the message still costs a try`() =
        runBlocking {
            listen(answer = "aW52ZW50ZWQtdGFn")
            awaitPort()
            val out = PeerLink().send("127.0.0.1", port, receipt, """{"v":3,"body":"x"}""")
            assertEquals(PeerLink.Outcome.Refused("not acked"), out)
        }

    @Test
    fun `stopping releases the port`() = runBlocking {
        val l = listen()
        awaitPort()
        assertTrue(l.isListening)
        l.stop()
        delay(200)
        assertFalse(l.isListening)
    }

    /** Сервер, що приймає рівно ОДНЕ зʼєднання й відповідає на кожен кадр у ньому. */
    private fun oneConnectionServer(closeAfterFirst: Boolean = false): java.net.ServerSocket {
        val server = java.net.ServerSocket(0)
        Thread {
            runCatching {
                while (true) {
                    val c = server.accept()
                    val input = java.io.BufferedInputStream(c.getInputStream())
                    while (true) {
                        val line = StringBuilder()
                        while (true) {
                            val ch = input.read()
                            if (ch < 0 || ch == '\n'.code) break
                            line.append(ch.toChar())
                        }
                        val header = PeerWire.parseHeader(line.toString()) ?: break
                        input.readNBytes(header.bodyBytes)
                        c.getOutputStream().write((PeerWire.ackLine(receipt) + "\n").toByteArray())
                        c.getOutputStream().flush()
                        if (closeAfterFirst) break
                    }
                    c.close()
                    if (!closeAfterFirst) break
                }
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    @Test
    fun `the second letter to the same phone rides the same connection`() = runBlocking {
        val server = oneConnectionServer()
        val client = PeerLink()
        val t0 = System.nanoTime()
        repeat(3) {
            assertEquals(PeerLink.Outcome.Acked, client.send("127.0.0.1", server.localPort, receipt, """{"v":3}"""))
        }
        // Новий сокет на кожен лист чекав би тут 8 с читання: сервер більше не приймає.
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        server.close()
    }

    @Test
    fun `a build that closes after one reply still gets every letter`() = runBlocking {
        val server = oneConnectionServer(closeAfterFirst = true)
        val client = PeerLink()
        repeat(3) {
            assertEquals(PeerLink.Outcome.Acked, client.send("127.0.0.1", server.localPort, receipt, """{"v":3}"""))
        }
        server.close()
    }

    @Test
    fun `a phone that keeps sockets open does not lock out the next one`() = runBlocking {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        listen(seen = seen)
        awaitPort()
        val clients = List(8) { PeerLink() }
        clients.forEach {
            assertEquals(PeerLink.Outcome.Acked, it.send("127.0.0.1", port, receipt, """{"v":3}"""))
        }
        assertEquals(8, seen.size)
        clients.forEach { it.stop() }
    }

    @Test
    fun `a dead address gives up on the connect in about a second and a half`() = runBlocking {
        val t0 = System.nanoTime()
        val out = PeerLink().send("10.255.255.1", 9, receipt, """{"v":3}""")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(PeerLink.Outcome.NoContact, out)
        assertTrue("чекали $ms мс", ms < 2_500)
    }
}
