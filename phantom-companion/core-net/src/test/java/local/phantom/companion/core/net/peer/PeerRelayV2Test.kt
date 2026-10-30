package local.phantom.companion.core.net.peer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Сховок v2 з боку телефона: пакет, чесне «зачекай», живий канал. */
class PeerRelayV2Test {

    private val blob = ByteArray(PeerRelay.BLOB_SIZES.first()) { it.toByte() }
    private val tag = "A".repeat(PeerRelay.TAG_CHARS)
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun clientFor(engine: MockEngine) = PeerRelayClient(
        baseUrl = "https://example.invalid",
        http = HttpClient(engine) {
            install(ContentNegotiation) { json() }
            expectSuccess = false
        },
    )

    @Test
    fun `many drops ride in one request, each with its own verdict`() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"dropped":[{"status":"held","held_until_ms":5},{"status":"full","retry_after_s":60},{"status":"bad"}],"burned":0}""",
                HttpStatusCode.OK,
                json,
            )
        }
        val got = clientFor(engine).dropMany(listOf(tag to blob, tag to blob, tag to blob))
        assertEquals(1, engine.requestHistory.size)
        assertEquals("/relay/v2/batch", engine.requestHistory.single().url.encodedPath)
        assertEquals(
            listOf(PeerRelayTransport.Drop.HELD, PeerRelayTransport.Drop.FULL, PeerRelayTransport.Drop.REFUSED),
            got.map { it.verdict },
        )
        assertEquals(5L, got[0].heldUntilMs)
        assertEquals(60_000L, got[1].retryAfterMs)
    }

    @Test
    fun `too many requests is busy with the server's own wait, not a refusal`() = runBlocking {
        val engine = MockEngine {
            respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "17"))
        }
        val got = clientFor(engine).drop(tag, blob)
        assertEquals(PeerRelayTransport.Drop.BUSY, got.verdict)
        assertEquals(17_000L, got.retryAfterMs)
    }

    @Test
    fun `an old server is asked for v2 once, then v1 for good`() = runBlocking {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.startsWith("/relay/v2/")) {
                respond("", HttpStatusCode.NotFound)
            } else {
                respond("""{"held_until_ms":1}""", HttpStatusCode.OK, json)
            }
        }
        val client = clientFor(engine)
        repeat(3) { assertEquals(PeerRelayTransport.Drop.HELD, client.drop(tag, blob).verdict) }
        val paths = engine.requestHistory.map { it.url.encodedPath }
        assertEquals(listOf("/relay/v2/batch", "/relay/drop", "/relay/drop", "/relay/drop"), paths)
        // Живого каналу на такому сервері немає — і телефон його не набирає.
        assertNull(client.openLive(Silent))
    }

    @Test
    fun `fetch and burn go through the batch on a v2 server`() = runBlocking {
        val b64 = java.util.Base64.getEncoder().encodeToString(blob)
        val engine = MockEngine {
            respond(
                """{"dropped":[],"burned":2,"blobs":[{"id":"b-1","tag":"$tag","blob":"$b64"}],"store_since_ms":9}""",
                HttpStatusCode.OK,
                json,
            )
        }
        val client = clientFor(engine)
        val got = client.fetch(List(PeerRelay.FETCH_TAGS) { tag })!!
        assertEquals("b-1", got.blobs.single().id)
        assertEquals(9L, got.storeSinceMs)
        assertTrue(client.burn(listOf("b-1", "b-2")))
        assertTrue(engine.requestHistory.all { it.url.encodedPath == "/relay/v2/batch" })
        assertTrue((engine.requestHistory.last().body as TextContent).text.contains("\"burn\":[\"b-1\",\"b-2\"]"))
    }

    // --- живий канал -------------------------------------------------------

    private object Silent : PeerRelayLive.Events {
        override fun onBlobs(blobs: List<PeerRelayTransport.Blob>, storeSinceMs: Long?) {}
    }

    private class FakeSocket : WebSocket {
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun request(): Request = Request.Builder().url("https://example.invalid").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { sent += text; return true }
        val binary = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        override fun send(bytes: ByteString): Boolean { binary += bytes.toByteArray(); return true }
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    private class Dialer {
        val sockets = mutableListOf<FakeSocket>()
        var listener: WebSocketListener? = null
        fun dial(url: String, l: WebSocketListener): WebSocket {
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

    @Test
    fun `the live channel subscribes to exactly the poll's names and hands pushed copies over`() {
        val dialer = Dialer()
        val got = mutableListOf<PeerRelayTransport.Blob>()
        val live = PeerRelayLive("wss://x/relay/v2/live", object : PeerRelayLive.Events {
            override fun onBlobs(blobs: List<PeerRelayTransport.Blob>, storeSinceMs: Long?) { got += blobs }
        }, dial = dialer::dial)
        live.ensure(tags, 0L)
        dialer.open()
        val sub = dialer.sockets.single().sent.single()
        assertTrue(sub.contains("\"op\":\"sub\""))
        assertEquals(PeerRelay.FETCH_TAGS, Regex("T0+\\d*").findAll(sub).count())
        dialer.push("""{"t":"subbed","store_since_ms":3}""")
        assertTrue(live.subscribed)
        val b64 = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        dialer.push("""{"t":"blobs","blobs":[{"id":"b-1","tag":"${tags[0]}","blob":"$b64"}]}""")
        assertEquals("b-1", got.single().id)
        // Той самий набір на тому самому зʼєднанні — жодного зайвого кадру.
        live.ensure(tags, 1_000L)
        assertEquals(1, dialer.sockets.single().sent.size)
    }

    @Test
    fun `a new day resubscribes on the same connection instead of redialing`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        live.ensure(tags, 0L)
        dialer.open()
        live.ensure(tags.reversed(), 1_000L)
        assertEquals(1, dialer.sockets.size)
        assertEquals(2, dialer.sockets.single().sent.size)
    }

    @Test
    fun `a lost channel is redialed with growing pauses capped at five seconds while it is fresh`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, jitter = { it }, dial = dialer::dial)
        var now = 0L
        val dialedAt = mutableListOf<Long>()
        // Такт кожні 0,5 с упродовж 10 хвилин; кожен набір одразу рветься.
        repeat(1_200) {
            val before = live.dials
            live.ensure(tags, now)
            if (live.dials > before) dialedAt += now
            if (live.state == PeerRelayLive.State.CONNECTING) dialer.fail()
            now += 500L
        }
        val gaps = dialedAt.zipWithNext { a, b -> b - a }
        assertEquals("пауза росте до стелі", listOf(500L, 500L, 1_000L, 2_000L, 4_000L, 5_000L), gaps.take(6))
        val fresh = gaps.take(PeerRelayLive.FAST_TRIES - 1)
        assertTrue("стеля 5 с, поки провали свіжі: $fresh", fresh.all { it <= PeerRelayLive.MAX_BACKOFF_MS })
        assertTrue("не тісна петля: ${live.dials}", live.dials < 140)
    }

    @Test
    fun `the server asking to reconnect is redialed at once and costs no pause`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, jitter = { it }, dial = dialer::dial)
        live.ensure(tags, 0L)
        dialer.open()
        dialer.push("""{"t":"subbed"}""")
        dialer.listener!!.onClosed(dialer.sockets.last(), 1013, "try again")
        assertEquals(0L, live.redialInMs(10L))
        live.ensure(tags, 10L)
        assertEquals(2, live.dials)
    }

    @Test
    fun `a subscription or a reset forgets that the server had no live channel`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        live.ensure(tags, 0L)
        dialer.fail(404)
        assertTrue(live.unsupported)
        live.reset()
        assertFalse(live.unsupported)
        live.ensure(tags, 2_000L)
        assertEquals("мережа змінилась — питаємо зараз, а не через годину", 2, live.dials)
        dialer.open()
        dialer.push("""{"t":"subbed"}""")
        assertFalse(live.unsupported)
    }

    @Test
    fun `a drop rides the open socket as one binary frame and is answered by its seq`() = runBlocking {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        live.ensure(tags, 0L)
        dialer.open()
        val small = ByteArray(PeerRelay.SMALL_BLOB_SIZE) { 7 }
        val got = async(kotlinx.coroutines.Dispatchers.Default) { live.drop(tags[1], small) }
        val frame = waitFor { dialer.sockets.single().binary.firstOrNull() }
        assertEquals(1 + 4 + PeerRelay.TAG_CHARS + small.size, frame.size)
        assertEquals(1, frame[0].toInt())
        val seq = java.nio.ByteBuffer.wrap(frame, 1, 4).int
        assertEquals(tags[1], String(frame, 5, PeerRelay.TAG_CHARS, Charsets.US_ASCII))
        dialer.push("""{"t":"ka"}""")
        dialer.push("""{"t":"dropped","seq":$seq,"status":"held","held_until_ms":77}""")
        val verdict = got.await()!!
        assertEquals(PeerRelayTransport.Drop.HELD, verdict.verdict)
        assertEquals(77L, verdict.heldUntilMs)
    }

    @Test
    fun `a socket that dies before the ack hands the drop back for the batch`() = runBlocking {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        live.ensure(tags, 0L)
        dialer.open()
        val got = async(kotlinx.coroutines.Dispatchers.Default) {
            live.drop(tags[1], ByteArray(PeerRelay.BLOB_SIZES.first()))
        }
        waitFor { dialer.sockets.single().binary.firstOrNull() }
        dialer.fail()
        assertNull(got.await())
        assertNull("без сокета — одразу назад", live.drop(tags[1], ByteArray(PeerRelay.BLOB_SIZES.first())))
    }

    @Test
    fun `health that advertises socket drops and the small bucket turns both on, and forgets an old downgrade`() =
        runBlocking {
            var deployed = false
            val engine = MockEngine { request ->
                when {
                    !deployed && request.url.encodedPath.startsWith("/relay/v2/") -> respond("", HttpStatusCode.NotFound)
                    request.url.encodedPath == "/relay/v2/health" -> respond(
                        """{"ok":true,"store_since_ms":1,"blob_sizes":[540,4124,16412,69660],"live":2}""",
                        HttpStatusCode.OK,
                        json,
                    )
                    request.url.encodedPath == "/relay/v2/batch" ->
                        respond("""{"dropped":[{"status":"held"}],"burned":0}""", HttpStatusCode.OK, json)
                    else -> respond("""{"held_until_ms":1}""", HttpStatusCode.OK, json)
                }
            }
            val client = clientFor(engine)
            client.drop(tag, blob)
            assertFalse(client.liveDrops)
            deployed = true
            client.probe()
            assertTrue(client.liveDrops)
            assertTrue(client.smallBlobs)
            client.drop(tag, blob)
            assertEquals("/relay/v2/batch", engine.requestHistory.last().url.encodedPath)
        }

    @Test
    fun `health without the small bucket keeps every letter at the old sizes`() = runBlocking {
        val engine = MockEngine {
            respond("""{"ok":true,"blob_sizes":[4124,16412,69660],"live":1}""", HttpStatusCode.OK, json)
        }
        val client = clientFor(engine)
        client.probe()
        assertFalse(client.smallBlobs)
        assertFalse("live 1 — лише підписка, вкидання пакетом", client.liveDrops)
    }

    private suspend fun <T : Any> waitFor(get: () -> T?): T {
        repeat(200) {
            get()?.let { return it }
            kotlinx.coroutines.delay(5)
        }
        error("не дочекались")
    }

    @Test
    fun `a server without the live channel is asked once an hour`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        live.ensure(tags, 0L)
        dialer.fail(404)
        assertTrue(live.unsupported)
        var now = 0L
        while (now < 3_000_000L) { now += 2_000L; live.ensure(tags, now) }
        assertEquals(1, live.dials)
    }

    @Test
    fun `no subscription narrower than sixty-four names ever leaves`() {
        val dialer = Dialer()
        val live = PeerRelayLive("wss://x", Silent, dial = dialer::dial)
        live.ensure(tags.take(3), 0L)
        assertTrue(dialer.sockets.isEmpty())
        assertFalse(live.sendLive(tags[0], byteArrayOf(1)))
    }

    @Test
    fun `live url is wss and never cleartext`() {
        assertEquals("wss://relay.example/relay/v2/live", PeerRelayLive.urlFor("https://relay.example/"))
    }
}
