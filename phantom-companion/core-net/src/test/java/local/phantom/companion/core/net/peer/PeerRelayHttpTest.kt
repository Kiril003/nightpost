package local.phantom.companion.core.net.peer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Що саме телефон каже серверові — і чого не каже.
 *
 * Головне тут не форма запиту, а порожнеча в ньому: жодного заголовка, за яким
 * трафік можна приписати цьому апарату. Ключ ліцензії або кука звели б нанівець
 * усю решту роботи зі схову адрес, і помітити це можна тільки так — подивившись
 * на справжній запит.
 *
 * Сервер тут — СТАРИЙ (v1): на `/relay/v2/...` він відповідає 404. Отже кожен
 * тест нижче доводить заразом, що новий телефон проти старого сервера ходить
 * тим самим дротом, що й до v2, байт у байт.
 */
class PeerRelayHttpTest {

    @Suppress("TestFunctionName")
    private fun MockEngine(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MockEngine = io.ktor.client.engine.mock.MockEngine { request ->
        if (request.url.encodedPath.startsWith("/relay/v2/")) {
            respond("", HttpStatusCode.NotFound)
        } else {
            handler(request)
        }
    }

    /** Лише v1-запити: одна проба v2 на клієнта — ціна того, що сервер старий. */
    private val MockEngine.v1Requests
        get() = requestHistory.filterNot { it.url.encodedPath.startsWith("/relay/v2/") }

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
    fun `a drop carries the mailbox and the bytes, and nothing that names the phone`() = runBlocking {
        val engine = MockEngine { respond("""{"held_until":"2026-08-23T00:00:00Z"}""", HttpStatusCode.OK, json) }
        assertEquals(PeerRelayTransport.Drop.HELD, clientFor(engine).drop(tag, blob).verdict)

        val request = engine.v1Requests.single()
        assertEquals("/relay/drop", request.url.encodedPath)
        assertNull(request.headers[HttpHeaders.Authorization])
        assertNull(request.headers[HttpHeaders.Cookie])
        assertTrue(request.headers.names().none { it.startsWith("X-Phantom", ignoreCase = true) })
        val body = (request.body as TextContent).text
        assertTrue(body.contains(tag))
        assertFalse(body.contains("license"))
    }

    @Test
    fun `a full store is not a failure the phone hides`() = runBlocking {
        val conflict = MockEngine { respond("""{"detail":"mailbox full"}""", HttpStatusCode.Conflict, json) }
        assertEquals(PeerRelayTransport.Drop.FULL, clientFor(conflict).drop(tag, blob).verdict)
        val noRoom = MockEngine { respond("{}", HttpStatusCode.InsufficientStorage, json) }
        assertEquals(PeerRelayTransport.Drop.FULL, clientFor(noRoom).drop(tag, blob).verdict)
    }

    @Test
    fun `a relay that is simply not there is an absence, not a refusal`() = runBlocking {
        val dead = MockEngine { throw java.io.IOException("no route") }
        assertEquals(PeerRelayTransport.Drop.NO_CONTACT, clientFor(dead).drop(tag, blob).verdict)
    }

    /**
     * Виміряно 17.08: сервер живий (`/healthz` 200), а `/relay/drop` віддає 404 —
     * ручки ще не на машині. Якби це рахувалось відмовою, кожен телефон з цією
     * збіркою палив би листу спробу на кожному такті й ховав би його у FAILED за
     * те, що ми не встигли викласти сервер.
     */
    @Test
    fun `a server with no relay deployed costs the letter nothing`() = runBlocking {
        for (code in listOf(
            HttpStatusCode.NotFound,
            HttpStatusCode.NotImplemented,
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.GatewayTimeout,
        )) {
            val engine = MockEngine { respond("", code) }
            assertEquals(
                "$code мусить бути відсутністю, не відмовою",
                PeerRelayTransport.Drop.NO_CONTACT,
                clientFor(engine).drop(tag, blob).verdict,
            )
        }
    }

    /**
     * Забір і спалення теж мусять мовчати проти невикладеного сервера. Виняток
     * тут коштував би рядка помилки кожні пʼятнадцять хвилин на кожному
     * телефоні — рівно там, де потім шукатимуть справжню причину.
     */
    @Test
    fun `a poll and a burn against a server with no relay stay quiet`() = runBlocking {
        val gone = MockEngine { respond("", HttpStatusCode.NotFound) }
        val client = clientFor(gone)
        assertNull(
            "порожньої видачі тут бути не може — сервера з релеєм немає",
            client.fetch(List(PeerRelay.FETCH_TAGS) { tag }),
        )
        assertFalse("спалити не вдалось, і це не виняток", client.burn(listOf("b-1")))
    }

    @Test
    fun `a blob of a length that would single this phone out never leaves`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, json) }
        assertEquals(PeerRelayTransport.Drop.REFUSED, clientFor(engine).drop(tag, ByteArray(999)).verdict)
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `a poll asks for sixty-four names and reads back what it recognises`() = runBlocking {
        val body = """{"blobs":[{"id":"b-1","tag":"$tag","blob":"${
            java.util.Base64.getEncoder().encodeToString(blob)
        }"}]}"""
        val engine = MockEngine { respond(body, HttpStatusCode.OK, json) }
        val got = clientFor(engine).fetch(List(PeerRelay.FETCH_TAGS) { tag })!!
        assertEquals(1, got.blobs.size)
        assertEquals("b-1", got.blobs.first().id)
        assertTrue(got.blobs.first().bytes.contentEquals(blob))
        assertEquals("/relay/fetch", engine.v1Requests.single().url.encodedPath)
    }

    /**
     * Два числа, якими сервер тримає обіцянку про сім діб: строк на вкиданні й
     * народження бази на заборі. Обидва — його годинником, і саме тому їх можна
     * порівняти між собою.
     */
    @Test
    fun `the phone reads the two numbers the relay states about its own clock`() = runBlocking {
        val dropped = MockEngine {
            respond("""{"held_until_ms":1755000000000}""", HttpStatusCode.OK, json)
        }
        assertEquals(1_755_000_000_000L, clientFor(dropped).drop(tag, blob).heldUntilMs)

        val fetched = MockEngine {
            respond("""{"blobs":[],"store_since_ms":1754000000000}""", HttpStatusCode.OK, json)
        }
        val got = clientFor(fetched).fetch(List(PeerRelay.FETCH_TAGS) { tag })!!
        assertTrue(got.blobs.isEmpty())
        assertEquals(1_754_000_000_000L, got.storeSinceMs)
    }

    /**
     * Старий сховок жодного з цих полів не знає. Телефон від цього не ламається
     * і нічого не вигадує: обидва числа просто відсутні, а відсутнє число нічого
     * не вирішує (див. `PeerRelayPump.sweepWiped`).
     */
    @Test
    fun `a relay that states neither number is answered, not broken`() = runBlocking {
        val old = MockEngine { respond("""{"held_until":"2026-08-23T00:00:00Z"}""", HttpStatusCode.OK, json) }
        val kept = clientFor(old).drop(tag, blob)
        assertEquals(PeerRelayTransport.Drop.HELD, kept.verdict)
        assertNull(kept.heldUntilMs)

        val empty = MockEngine { respond("""{"blobs":[]}""", HttpStatusCode.OK, json) }
        val got = clientFor(empty).fetch(List(PeerRelay.FETCH_TAGS) { tag })!!
        assertNull(got.storeSinceMs)
    }

    @Test
    fun `a poll narrower than sixty-four names is refused before the wire`() = runBlocking {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, json) }
        assertNull(clientFor(engine).fetch(List(PeerRelay.FETCH_TAGS - 1) { tag }))
        assertTrue(engine.requestHistory.isEmpty())
    }

    @Test
    fun `burning names the copies and nothing else`() = runBlocking {
        val engine = MockEngine { respond("""{"ok":true}""", HttpStatusCode.OK, json) }
        assertTrue(clientFor(engine).burn(listOf("b-1", "b-2")))
        val request = engine.v1Requests.single()
        assertEquals("/relay/burn", request.url.encodedPath)
        assertNull(request.headers[HttpHeaders.Authorization])
        assertTrue((request.body as TextContent).text.contains("b-1"))
    }
}
