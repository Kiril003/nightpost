package local.phantom.companion.core.net.peer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/** Сховок у підвалі: 2G, RTT 1–2 с, завмирання ефіру, мертві сокети, проксі, що ріже апгрейд. */
class PeerRelayBasementTest {

    private object Silent : PeerRelayLive.Events {
        override fun onBlobs(blobs: List<PeerRelayTransport.Blob>, storeSinceMs: Long?) {}
    }

    private class FakeSocket : WebSocket {
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val binary = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        var cancelled = false
        override fun request(): Request = Request.Builder().url("https://example.invalid").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString): Boolean { binary += bytes.toByteArray(); return true }
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() { cancelled = true }
    }

    private class Dialer {
        val sockets = mutableListOf<FakeSocket>()
        var listener: WebSocketListener? = null
        fun dial(@Suppress("UNUSED_PARAMETER") url: String, l: WebSocketListener): WebSocket {
            listener = l
            return FakeSocket().also { sockets += it }
        }
        fun open() = listener!!.onOpen(sockets.last(), response(101))
        fun fail(code: Int? = null) =
            listener!!.onFailure(sockets.last(), java.io.IOException("gone"), code?.let { response(it) })
        fun push(text: String) = listener!!.onMessage(sockets.last(), text)
        private fun response(code: Int) = Response.Builder()
            .request(Request.Builder().url("https://example.invalid").build())
            .protocol(Protocol.HTTP_1_1).code(code).message("").build()
    }

    private val tags = List(PeerRelay.FETCH_TAGS) { i -> "T${i.toString().padStart(42, '0')}" }

    private fun subscribed(dialer: Dialer, live: PeerRelayLive, now: Long) {
        live.ensure(tags, now)
        dialer.open()
        dialer.push("""{"t":"subbed"}""")
    }

    @Test
    fun `a handshake that hangs on a dead link is abandoned and redialed, not waited on forever`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, jitter = { it }, dial = dialer::dial)
        live.ensure(tags, 0L)
        var now = 0L
        while (now < 120_000L) {
            now += 2_000L
            live.ensure(tags, now)
        }
        assertTrue("завислий набір кинуто: ${dialer.sockets.first().cancelled}", dialer.sockets.first().cancelled)
        assertTrue("і набрано знову: ${live.dials}", live.dials >= 2)
        assertTrue("строк набору ріс із RTO, а не 10 с OkHttp", live.meter.dialTimeoutMs() >= PeerLinkMeter.MIN_DIAL_MS)
    }

    @Test
    fun `thirty seconds of dead air does not kill a subscribed socket`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        subscribed(dialer, live, 0L)
        var now = 0L
        while (now < 30_000L) {
            now += 2_000L
            live.ensure(tags, now)
        }
        assertEquals(PeerRelayLive.State.SUBSCRIBED, live.state)
        assertFalse(dialer.sockets.single().cancelled)
        dialer.push("""{"t":"ka"}""")
        while (now < 60_000L) {
            now += 2_000L
            live.ensure(tags, now)
        }
        assertEquals("ефір ожив — зʼєднання те саме", 1, live.dials)
    }

    @Test
    fun `a socket that went silent for good is asked once, then given up and redialed`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, jitter = { it }, dial = dialer::dial)
        subscribed(dialer, live, 0L)
        var now = 0L
        var gaveUpAt = -1L
        while (now < 180_000L && gaveUpAt < 0) {
            now += 2_000L
            live.ensure(tags, now)
            if (dialer.sockets.first().cancelled) gaveUpAt = now
        }
        val first = dialer.sockets.first()
        assertTrue("пінг пішов перед присудом: ${first.sent}", first.sent.any { it.contains("\"op\":\"ping\"") })
        assertTrue("мертвий сокет помічено за ${gaveUpAt / 1000} с", gaveUpAt in 1..90_000L)
        now += 2_000L
        live.ensure(tags, now)
        assertEquals(2, live.dials)
    }

    @Test
    fun `a link that flaps every few seconds is not redialed at full speed forever`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, jitter = { it }, dial = dialer::dial)
        var now = 0L
        // Кожне зʼєднання підписується й за 4 с рветься, десять хвилин поспіль.
        var openedAt = -1L
        while (now < 600_000L) {
            val before = live.dials
            live.ensure(tags, now)
            if (live.dials > before) {
                dialer.open()
                dialer.push("""{"t":"subbed"}""")
                openedAt = now
            }
            if (openedAt >= 0 && now - openedAt >= 4_000L && live.state == PeerRelayLive.State.SUBSCRIBED) {
                dialer.fail()
                openedAt = -1L
            }
            now += 500L
        }
        assertTrue("набрано ${live.dials} разів за 10 хв", live.dials < 40)
    }

    @Test
    fun `after half a minute of failures the pause grows past five seconds`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, jitter = { it }, dial = dialer::dial)
        var now = 0L
        val dialedAt = mutableListOf<Long>()
        repeat(1_200) {
            val before = live.dials
            live.ensure(tags, now)
            if (live.dials > before) dialedAt += now
            if (live.state == PeerRelayLive.State.CONNECTING) dialer.fail()
            now += 500L
        }
        val gaps = dialedAt.zipWithNext { a, b -> b - a }
        assertEquals("спершу швидко", listOf(500L, 500L, 1_000L, 2_000L, 4_000L, 5_000L), gaps.take(6))
        assertTrue("потім рідше: ${gaps.takeLast(3)}", gaps.last() >= 30_000L)
        assertTrue("не шторм: ${live.dials}", live.dials < 30)
    }

    @Test
    fun `a proxy that refuses the upgrade sends the phone to the batch for ten minutes`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        live.ensure(tags, 0L)
        dialer.fail(403)
        assertTrue(live.blocked)
        var now = 0L
        while (now < 590_000L) { now += 2_000L; live.ensure(tags, now) }
        assertEquals(1, live.dials)
        live.reset()
        live.ensure(tags, now + 2_000L)
        assertEquals("нова мережа — пробуємо зараз", 2, live.dials)
    }

    @Test
    fun `an ack that takes seven seconds on a slow link is still the socket's answer`() = runTest {
        val dialer = Dialer()
        val meter = PeerLinkMeter()
        repeat(4) { meter.rtt(3_000L) }
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial, meter = meter)
        subscribed(dialer, live, 0L)
        val got = async { live.drop(tags[1], ByteArray(PeerRelay.SMALL_BLOB_SIZE)) }
        runCurrent()
        val frame = dialer.sockets.single().binary.single()
        val seq = java.nio.ByteBuffer.wrap(frame, 1, 4).int
        advanceTimeBy(7_000L)
        dialer.push("""{"t":"dropped","seq":$seq,"status":"held","held_until_ms":5}""")
        assertNotNull("квитанція за 7 с — не привід вкидати вдруге пакетом", got.await())
    }

    @Test
    fun `an ack that never comes makes the next tick ask the socket whether it is alive`() = runTest {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        subscribed(dialer, live, 0L)
        val got = async { live.drop(tags[1], ByteArray(PeerRelay.SMALL_BLOB_SIZE)) }
        advanceTimeBy(PeerLinkMeter.MAX_WAIT_MS)
        runCurrent()
        assertEquals(null, got.await())
        live.ensure(tags, 2_000L)
        assertTrue(dialer.sockets.single().sent.any { it.contains("\"op\":\"ping\"") })
    }

    @Test
    fun `a narrow phone asks the server to knock less often`() {
        val dialer = Dialer()
        val meter = PeerLinkMeter()
        meter.rtt(1_800L)
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial, meter = meter)
        live.ensure(tags, 0L)
        dialer.open()
        assertTrue(dialer.sockets.single().sent.single().contains("\"ka\":${PeerRelayLive.NARROW_KA_S}"))
    }

    // --- курʼєр ------------------------------------------------------------

    private class SocketTransport(private val live: PeerRelayLive) : PeerRelayTransport {
        val httpDrops = mutableListOf<Int>()
        override val liveDrops = true
        override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
            httpDrops += blob.size
            return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD, 1L)
        }
        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? = null
        override suspend fun burn(ids: List<String>): Boolean = true
        override fun openLive(events: PeerRelayLive.Events): PeerRelayLive = live
    }

    @Test
    fun `a file chunk never rides the socket where it would hold the words behind it`() = runBlocking {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        subscribed(dialer, live, 0L)
        val relay = SocketTransport(live)
        val courier = PeerRelayCourier(relay, live = { live })
        val big = ByteArray(PeerRelay.BLOB_SIZES.last())
        courier.acknowledgeAll(listOf(tags[2] to big))
        assertTrue("шматок не в сокеті", dialer.sockets.single().binary.isEmpty())
        assertEquals(listOf(big.size), relay.httpDrops)
    }

    @Test
    fun `all receipts of a round go into the socket before the first ack is awaited`() = runTest {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        subscribed(dialer, live, 0L)
        val courier = PeerRelayCourier(SocketTransport(live), live = { live })
        val receipts = List(3) { tags[it] to ByteArray(PeerRelay.SMALL_BLOB_SIZE) }
        val got = async { courier.acknowledgeAll(receipts) }
        runCurrent()
        val frames = dialer.sockets.single().binary
        assertEquals("три кадри разом, не по одному на обертання", 3, frames.size)
        frames.forEach {
            dialer.push("""{"t":"dropped","seq":${java.nio.ByteBuffer.wrap(it, 1, 4).int},"status":"held"}""")
        }
        assertEquals(listOf(true, true, true), got.await())
    }

    // --- лічильник дороги ----------------------------------------------------

    @Test
    fun `one-second round trips switch the narrow mode on, and a calm minute switches it off`() {
        var now = 0L
        val meter = PeerLinkMeter { now }
        repeat(3) { meter.rtt(120L) }
        assertFalse(meter.narrow)
        meter.rtt(1_900L)
        meter.rtt(1_700L)
        meter.rtt(1_800L)
        assertTrue(meter.narrow)
        assertTrue("RTO росте з ефіром: ${meter.rtoMs}", meter.rtoMs >= 2_000L)
        repeat(20) { meter.rtt(150L) }
        now += 30_000L
        meter.recheck()
        assertTrue("півхвилини тиші — ще вузько", meter.narrow)
        now += 31_000L
        meter.recheck()
        assertFalse(meter.narrow)
    }

    @Test
    fun `two failures among the last ten events are a narrow link even with fast round trips`() {
        val meter = PeerLinkMeter()
        repeat(5) { meter.rtt(100L) }
        meter.fail()
        assertFalse(meter.narrow)
        meter.fail()
        assertTrue(meter.narrow)
    }

    @Test
    fun `timeouts grow with the measured link and with the frame size`() {
        val meter = PeerLinkMeter()
        assertEquals(PeerLinkMeter.INITIAL_RTO_MS, meter.rtoMs)
        repeat(8) { meter.rtt(2_000L) }
        val small = meter.replyTimeoutMs(PeerRelay.SMALL_BLOB_SIZE, 5_000L)
        val big = meter.replyTimeoutMs(PeerRelay.BLOB_SIZES.last(), 5_000L)
        assertTrue("квитанція на 2-секундному ефірі чекає більше за 5 с: $small", small > 5_000L)
        assertTrue("70 КБ на 20 кбіт/с — це півхвилини ефіру: $big", big >= 28_000L)
    }

    @Test
    fun `a relay request that throws counts against the link`() = runBlocking {
        val meter = PeerLinkMeter()
        val client = PeerRelayClient(
            baseUrl = "https://example.invalid",
            http = HttpClient(MockEngine { throw java.io.IOException("ефір зник") }) {
                install(ContentNegotiation) { json() }
                expectSuccess = false
            },
            meter = meter,
        )
        val tag = "A".repeat(PeerRelay.TAG_CHARS)
        repeat(2) {
            assertEquals(
                PeerRelayTransport.Drop.NO_CONTACT,
                client.drop(tag, ByteArray(PeerRelay.SMALL_BLOB_SIZE)).verdict,
            )
        }
        assertTrue(meter.narrow)
        assertTrue("той самий лічильник у живого каналу", client.openLive(Silent)!!.meter === meter)
    }

    // --- DNS ---------------------------------------------------------------

    @Test
    fun `a resolver that fails in the basement falls back to the last good answer`() {
        val good = listOf(InetAddress.getByAddress("relay", byteArrayOf(10, 0, 0, 7)))
        var up = true
        val dns = PeerStickyDns(system = { if (up) good else throw UnknownHostException(it) })
        assertEquals(good, dns.lookup("relay"))
        up = false
        assertEquals(good, dns.lookup("relay"))
        try {
            dns.lookup("never-seen")
            error("незнайоме імʼя не вигадується")
        } catch (_: UnknownHostException) {
        }
    }
}
