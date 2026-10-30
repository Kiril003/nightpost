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
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Що телефон каже довідникові — і чого не каже.
 *
 * Перше важливіше за друге: у запиті на пошук не мусить бути самого імені,
 * інакше сервер веде журнал «хто кого шукав» просто тим, що обслуговує запити.
 * Побачити це можна лише на справжньому запиті, а не в описі ручки.
 */
class PeerDirectoryHttpTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val keys = Ed25519.generateKeypair()

    private val card = PeerInvite.Invite(
        peerId = PeerIntro.fingerprint(b64(keys.publicKey)),
        name = "Марта",
        pubKey = b64(keys.publicKey),
        dhPubKey = b64(dhKeypair().publicKey),
        host = "192.168.1.9",
        port = 47_331,
    )

    private fun clientFor(engine: MockEngine) = PeerDirectoryClient.open(
        baseUrl = "https://example.invalid",
        http = HttpClient(engine) {
            install(ContentNegotiation) { json() }
            expectSuccess = false
        },
    )!!

    @Test
    fun `a lookup asks by digest and never carries the name`() = runBlocking {
        val engine = MockEngine { respond("""{"invite":"PH2:x:aaaaaa","sig":"s","exp":9}""", HttpStatusCode.OK, json) }
        clientFor(engine).lookup("@Marta_Ko")

        val request = engine.requestHistory.single()
        assertEquals(
            "/directory/lookup/${PeerDirectory.handleHash("marta_ko")}",
            request.url.encodedPath,
        )
        assertFalse("імʼя не має бути в запиті", request.url.toString().contains("marta_ko"))
        assertNull(request.headers[HttpHeaders.Authorization])
        assertNull(request.headers[HttpHeaders.Cookie])
    }

    @Test
    fun `nobody by that name and nobody answering are different answers`() = runBlocking {
        val absent = MockEngine { respond("", HttpStatusCode.NotFound) }
        assertEquals(PeerDirectoryClient.Lookup.None, clientFor(absent).lookup("marta_ko"))

        val dead = MockEngine { throw java.io.IOException("no route") }
        assertEquals(PeerDirectoryClient.Lookup.Unreachable, clientFor(dead).lookup("marta_ko"))

        val broken = MockEngine { respond("", HttpStatusCode.InternalServerError) }
        assertEquals(PeerDirectoryClient.Lookup.Unreachable, clientFor(broken).lookup("marta_ko"))
    }

    /**
     * Сервер довідника на проді ще не викочений, тож 404 на публікацію — це
     * «ручки ще немає», а не «імʼя погане». Назвати це відмовою означало б
     * послати власника міняти цілком нормальне імʼя.
     */
    @Test
    fun `a server with no directory deployed is an absence, not a refusal`() = runBlocking {
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
                PeerDirectoryClient.Outcome.Unreachable,
                clientFor(engine).publish("marta_ko", card, keys.privateKey, nowMs = 1_000_000L),
            )
        }
    }

    @Test
    fun `publishing signs an invite with no address in it`() = runBlocking {
        val engine = MockEngine {
            respond("""{"handle_hash":"aa","exp":2592000}""", HttpStatusCode.OK, json)
        }
        val outcome = clientFor(engine)
            .publish("@Marta_Ko", card, keys.privateKey, nowMs = 1_000_000L)

        assertTrue(outcome is PeerDirectoryClient.Outcome.Published)
        val request = engine.requestHistory.single()
        assertEquals("/directory/publish", request.url.encodedPath)
        val body = (request.body as TextContent).text
        assertTrue(
            "їде адреса, а не імʼя",
            body.contains("\"handle_hash\":\"${PeerDirectory.handleHash("marta_ko")}\""),
        )
        assertFalse("адреса телефона у довіднику не живе", body.contains("192.168.1.9"))
        assertFalse(body.contains("47331"))

        // Те, що поїхало, мусить пройти ту саму перевірку, яку робить чужий
        // телефон, — інакше ми публікуємо запис, якого самі не приймаємо.
        val invite = Regex("\"invite\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val sig = Regex("\"sig\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        val exp = Regex("\"exp\":(\\d+)").find(body)!!.groupValues[1].toLong()
        assertTrue("сервер не тримає довше за 30 діб", exp - 1_000L <= PeerDirectory.MAX_TTL_SECONDS)
        assertTrue(
            PeerDirectory.verify(
                PeerDirectory.Record("marta_ko", invite, sig, exp, pubKey = card.pubKey),
                nowMs = 1_000_000L,
            ) is PeerDirectory.Verdict.Trusted,
        )
    }

    /**
     * Сторож над СЕРІАЛІЗОВАНИМ тілом: імені там немає — ні в публікації, ні
     * у відклику.
     *
     * Ставиться на знайдену ваду, а не про всяк випадок. `PeerDirectory` обіцяв
     * рядками, що «сервер знає лише SHA-256 імені», а `publish` слав
     * `handle = name` відкритим текстом: пошук ішов хешем, публікація — ні,
     * і сервер міг скласти рівно той перелік, якого «не існує». Перевірка над
     * оголошенням `PublishBody` цього не спіймала б — поле могло б зватись
     * як завгодно й везти імʼя; тому дивимось на те, що справді пішло в мережу.
     *
     * Друга половина — конверт усередині тіла. Він теж не несе людського
     * рядка: `addressless()` зрізає й показне імʼя пристрою, тож із бази
     * довідника не складається перелік ані нікнеймів, ані імен апаратів.
     */
    @Test
    fun `neither publish nor withdraw carries the name itself`() = runBlocking {
        val digest = PeerDirectory.handleHash("marta_ko")
        val published = MockEngine {
            respond("""{"handle_hash":"$digest","exp":2592000}""", HttpStatusCode.OK, json)
        }
        clientFor(published).publish("@Marta_Ko", card, keys.privateKey, nowMs = 1_000_000L)
        val withdrawn = MockEngine { respond("", HttpStatusCode.OK, json) }
        clientFor(withdrawn).withdraw("@Marta_Ko", keys.privateKey, nowMs = 1_000_000L)

        for (engine in listOf(published, withdrawn)) {
            val body = (engine.requestHistory.single().body as TextContent).text
            assertTrue("адреса мусить бути в тілі", body.contains(digest))
            assertFalse("імені в тілі немає", body.lowercase().contains("marta_ko"))
            // Не `!contains("handle")` — це відкинуло б і `handle_hash`.
            assertFalse("поля handle не існує", body.contains("\"handle\":"))
        }

        // Імʼя апарата всередині конверта — теж ні. Дивимось на РОЗПАКОВАНЕ
        // тіло коду, а не на поле `Invite`: розбір підставляє короткий
        // ідентифікатор замість порожнього, тож `inside.name` буде непорожнім
        // і сховав би зріз, якби його не зробили.
        val code = Regex("\"invite\":\"([^\"]+)\"")
            .find((published.requestHistory.single().body as TextContent).text)!!
            .groupValues[1]
        val raw = String(
            java.util.Base64.getUrlDecoder().decode(code.split(":")[1]),
            Charsets.UTF_8,
        )
        assertEquals("показне імʼя зрізане", "", raw.split("|")[1])
        assertFalse("імені апарата в конверті немає", raw.contains("Марта"))
        // І те, що з нього виходить на скло, — короткий id, а не порожньо.
        val inside = (PeerInvite.decode(code) as PeerInvite.Decoded.Ph2).invite
        assertEquals(PeerRoster.shortId(inside.peerId), inside.name)
        assertTrue(inside.name.isNotBlank())
    }

    @Test
    fun `a name somebody else holds comes back as taken`() = runBlocking {
        val engine = MockEngine { respond("""{"detail":"taken"}""", HttpStatusCode.Conflict, json) }
        assertEquals(
            PeerDirectoryClient.Outcome.Taken,
            clientFor(engine).publish("marta_ko", card, keys.privateKey, nowMs = 1_000_000L),
        )
    }

    @Test
    fun `withdrawing a name that is already gone still reads as withdrawn`() = runBlocking {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        assertEquals(
            PeerDirectoryClient.Outcome.Withdrawn,
            clientFor(engine).withdraw("marta_ko", keys.privateKey, nowMs = 1_000_000L),
        )
        assertEquals("/directory/publish", engine.requestHistory.single().url.encodedPath)
    }

    @Test
    fun `a plaintext address gets no client at all`() {
        assertNull(PeerDirectoryClient.open("http://example.invalid"))
        assertNull(PeerDirectoryClient.open(""))
        assertNull(PeerDirectoryClient.open("https://"))
        assertNotNull(PeerDirectoryClient.open("https://example.invalid/"))
    }
}
