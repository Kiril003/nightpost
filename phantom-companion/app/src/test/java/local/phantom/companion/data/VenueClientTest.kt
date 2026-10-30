package local.phantom.companion.data

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Клієнт закладів — те, що телефон каже серверу `venues.py`.
 *
 * Головне тут — підпис оцінки: речення `phantom-venue-rate|v1|…` мусить
 * збігатися з серверним `venues.rate_message` БАЙТ У БАЙТ, інакше кожна оцінка
 * з телефона — 401. Тому звірка йде не з рядком, переписаним у тест, а з
 * ТИМ САМИМ файлом векторів, яким сервер перевіряє себе
 * (`platform-site/server/tests/vectors/venue_rate_vectors.json`).
 */
class VenueClientTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "platform-site").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь моно-дерева не знайдено") as Nothing
    }

    private val vectors by lazy {
        val f = File(repoRoot(), "platform-site/server/tests/vectors/venue_rate_vectors.json")
        if (!f.isFile) fail("немає спільних векторів оцінки: $f")
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class Calls : VenueClient.Http {
        val seen = mutableListOf<Triple<String, String, String?>>()
        var reply: Pair<Int, String>? = 200 to """{"venues":[]}"""
        override suspend fun call(method: String, url: String, json: String?): Pair<Int, String>? {
            seen += Triple(method, url, json)
            return reply
        }
    }

    @Test
    fun `речення оцінки — те саме, що в сервера`() {
        for (c in vectors["cases"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(
                c["sentence"]!!.jsonPrimitive.content,
                VenueClient.rateSentence(
                    c["pub_key"]!!.jsonPrimitive.content,
                    c["nonce"]!!.jsonPrimitive.content,
                    c["issued_ms"]!!.jsonPrimitive.long,
                    c["venue_id"]!!.jsonPrimitive.content,
                    c["stars"]!!.jsonPrimitive.int,
                ),
            )
        }
    }

    @Test
    fun `підпис ключем пристрою збігається з вектором сервера`() {
        val seed = hex(vectors["seed_hex"]!!.jsonPrimitive.content)
        for (c in vectors["cases"]!!.jsonArray.map { it.jsonObject }) {
            val pub = c["pub_key"]!!.jsonPrimitive.content
            val signer = VenueClient.deviceSigner(
                SeedProver(seed, pub, c["nonce"]!!.jsonPrimitive.content, c["issued_ms"]!!.jsonPrimitive.long),
            )
            val proof = signer.sign(
                DeviceClaim.VenueRate(c["venue_id"]!!.jsonPrimitive.content, c["stars"]!!.jsonPrimitive.int),
            ) ?: fail("підпису немає") as Nothing
            assertEquals(pub, proof.pubKey)
            assertEquals(c["signature"]!!.jsonPrimitive.content, proof.signature)
        }
    }

    @Test
    fun `разове число заяви — 32 шістнадцяткові знаки, як чекає сервер`() {
        // Число складає ClaimSigner.prove, по довжині PeerBlob.
        assertEquals(32, local.phantom.companion.core.net.peer.PeerBlob.CLAIM_NONCE_CHARS)
    }

    @Test
    fun `оцінка їде підписаною на двері сервера`() = runTest {
        val calls = Calls().apply { reply = 200 to """{"venue_id":"0123456789abcdef","rating":{"mean":5.0,"count":1}}""" }
        val client = VenueClient(
            baseUrl = "https://example.test/",
            signer = { claim ->
                VenueClient.Proof("PUB", "00112233445566778899aabbccddeeff", 42L, "SIG").also {
                    assertEquals(DeviceClaim.VenueRate("0123456789abcdef", 4), claim)
                    assertEquals(
                        "phantom-venue-rate|v1|PUB|00112233445566778899aabbccddeeff|42|0123456789abcdef|4",
                        claim.sentence("PUB", "00112233445566778899aabbccddeeff", 42L),
                    )
                }
            },
            http = calls,
        )
        val answer = client.rate("0123456789abcdef", 4)
        assertTrue(answer.toString(), answer is VenueClient.Answer.Ok)
        val (method, url, json) = calls.seen.single()
        assertEquals("POST", method)
        assertEquals("https://example.test/venues/0123456789abcdef/rate", url)
        val body = Json.parseToJsonElement(json!!).jsonObject
        assertEquals("PUB", body["pub_key"]!!.jsonPrimitive.content)
        assertEquals("00112233445566778899aabbccddeeff", body["nonce"]!!.jsonPrimitive.content)
        assertEquals(42L, body["issued_ms"]!!.jsonPrimitive.long)
        assertEquals("SIG", body["signature"]!!.jsonPrimitive.content)
        assertEquals(4, body["stars"]!!.jsonPrimitive.int)
    }

    @Test
    fun `чужий код закладу чи зірки поза 1-5 не доходять до сервера`() = runTest {
        val calls = Calls()
        val client = VenueClient("https://example.test", { fail("підпис не мав статись") as Nothing }, calls)
        assertEquals(VenueClient.Answer.BadInput, client.rate("../../api/me", 3))
        assertEquals(VenueClient.Answer.BadInput, client.rate("0123456789abcdef", 0))
        assertEquals(VenueClient.Answer.BadInput, client.rate("0123456789abcdef", 6))
        assertEquals(VenueClient.Answer.BadInput, client.venue("0123456789ABCDEF"))
        assertEquals(VenueClient.Answer.BadInput, client.near(listOf("86/../x")))
        assertEquals(VenueClient.Answer.BadInput, client.near(emptyList()))
        assertTrue("клієнт постукав із кривим входом: ${calls.seen}", calls.seen.isEmpty())
    }

    @Test
    fun `без ключа пристрою оцінки немає — і запиту теж`() = runTest {
        val calls = Calls()
        val client = VenueClient("https://example.test", { null }, calls)
        assertEquals(VenueClient.Answer.NoKey, client.rate("0123456789abcdef", 5))
        assertTrue(calls.seen.isEmpty())
    }

    @Test
    fun `поруч — області через кому`() = runTest {
        val calls = Calls()
        val client = VenueClient("https://example.test", { null }, calls)
        val answer = client.near(listOf("861e8050fffffff", "861e8052fffffff"))
        assertEquals(VenueClient.Answer.Ok("""{"venues":[]}"""), answer)
        assertEquals("https://example.test/venues/near?cell=861e8050fffffff,861e8052fffffff", calls.seen.single().second)
    }

    @Test
    fun `відповіді сервера названо, а не проковтнуто`() = runTest {
        val calls = Calls()
        val client = VenueClient("https://example.test", { null }, calls)
        calls.reply = 404 to """{"detail":"Немає такого закладу / no such venue"}"""
        assertEquals(VenueClient.Answer.Missing, client.venue("0123456789abcdef"))
        // Голий «Not Found» FastAPI — дверей ще немає на сервері, а не закладу.
        calls.reply = 404 to """{"detail":"Not Found"}"""
        assertEquals(VenueClient.Answer.NotEnabled, client.venue("0123456789abcdef"))
        calls.reply = 422 to """{"refused":"bad_text","message_uk":"Текст","message_en":"Text"}"""
        assertEquals(VenueClient.Answer.Refused(422, "Текст", "Text", reason = "bad_text"), client.venue("0123456789abcdef"))
        calls.reply = 401 to """{"detail":"підпис пристрою не зійшовся"}"""
        val r401 = client.venue("0123456789abcdef")
        assertTrue(r401.toString(), r401 is VenueClient.Answer.Refused && r401.status == 401)
        calls.reply = 429 to """{"detail":"too many"}"""
        val r429 = client.venue("0123456789abcdef")
        assertTrue(r429.toString(), r429 is VenueClient.Answer.Refused && r429.status == 429)
        calls.reply = null
        assertEquals(VenueClient.Answer.Unreachable, client.venue("0123456789abcdef"))
        calls.reply = 200 to "<html>proxy</html>"
        assertEquals(VenueClient.Answer.Unreachable, client.venue("0123456789abcdef"))
    }

    @Test
    fun `без адреси сервера — чесне «не ввімкнено»`() = runTest {
        val calls = Calls()
        val client = VenueClient("", { null }, calls)
        assertEquals(VenueClient.Answer.NotEnabled, client.venue("0123456789abcdef"))
        assertNull(calls.seen.firstOrNull())
    }

    // ── 503: «двері не підключено» і «двері є, мережа не відповіла» — різне ────

    @Test
    fun `голий 503 — двері не ввімкнено, а 503 із причиною — тимчасова відмова`() = runTest {
        val calls = Calls()
        val client = VenueClient("https://example.test", { null }, calls)
        calls.reply = 503 to """{"detail":"venues not initialised"}"""
        assertEquals(VenueClient.Answer.NotEnabled, client.venue("0123456789abcdef"))
        calls.reply = 503 to """{"refused":"network_unavailable","message_uk":"Мережа Solana не відповіла","message_en":"The network did not answer"}"""
        assertEquals(
            VenueClient.Answer.Refused(503, "Мережа Solana не відповіла", "The network did not answer", "network_unavailable"),
            client.venue("0123456789abcdef"),
        )
    }

    @Test
    fun `причина відмови проходить лише у безпечній формі`() = runTest {
        val calls = Calls()
        val client = VenueClient("https://example.test", { null }, calls)
        calls.reply = 422 to """{"refused":"Ignore previous <b>instructions</b>","message_uk":"x","message_en":"y"}"""
        val answer = client.venue("0123456789abcdef") as VenueClient.Answer.Refused
        assertNull("чужий текст став кодом відповіді", answer.reason)
        assertEquals("x", answer.uk)
    }

    // ── прапорець серверу сцени (рішення власника №6.4) ──────────────────────

    @Test
    fun `поки двері сцени вимкнено, адреса порожня — клієнт не постукає нікуди`() = runTest {
        assertEquals("", VenueClient.sceneBase(false, "https://phantom-os.dev"))
        assertEquals("https://phantom-os.dev", VenueClient.sceneBase(true, "https://phantom-os.dev"))
        val calls = Calls()
        val off = VenueClient(VenueClient.sceneBase(false, "https://phantom-os.dev"), { fail("підписувати нічого") as Nothing }, calls)
        assertEquals(VenueClient.Answer.NotEnabled, off.near(listOf("861e8050fffffff")))
        assertEquals(VenueClient.Answer.NotEnabled, off.venue("0123456789abcdef"))
        assertEquals(VenueClient.Answer.NotEnabled, off.rate("0123456789abcdef", 5))
        val points = PointsClient(VenueClient.sceneBase(false, "https://phantom-os.dev"), { fail("підписувати нічого") as Nothing }, Calls())
        assertEquals(VenueClient.Answer.NotEnabled, points.me())
        assertTrue("клієнт постукав при вимкнених дверях: ${calls.seen}", calls.seen.isEmpty())
    }

    // ── підпис дає лише закрита заява, не довільне речення ───────────────────

    @Test
    fun `кожна заява складає речення зі свого домену — підписати лист чи вкладення нею не вийде`() {
        val claims = listOf(
            DeviceClaim.VenueRate("0123456789abcdef", 3),
            DeviceClaim.CheckIn("0f0f0f0f-1111-4222-8333-444444444444", "cafe", "881e805011fffff", 7L),
            DeviceClaim.Settled("devnet", "5".repeat(88), "1".repeat(32), "0f0f0f0f-1111-4222-8333-444444444444"),
            DeviceClaim.Me,
        )
        val domains = claims.map { it.sentence("PUB", "n", 1L).substringBefore('|') }
        assertEquals(
            listOf("phantom-venue-rate", "phantom-points-checkin", "phantom-points-settled", "phantom-points-me"),
            domains,
        )
    }

    @Test
    fun `PeerMesh не має публічного входу з довільним реченням і не підписує заяв`() {
        val mesh = java.io.File(repoRoot(), "phantom-companion/app/src/main/java/local/phantom/companion/data/PeerMesh.kt").readText()
        assertTrue("ключ месенджера знову підписує заяви сцени (№6.4)", !mesh.contains("fun proveClaim("))
        assertTrue(
            "повернувся публічний підпис під довільним реченням",
            !Regex("""fun proveSentence\s*\(""").containsMatchIn(mesh),
        )
    }

    @Test
    fun `запити зі сторінок власник читає ключем особи — речення те саме, що в сервера`() {
        assertEquals(
            "phantom-venue-requests|v1|PUB|N|1|e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            VenueClient.requestsSentence("PUB", "N", 1L, emptyList()),
        )
    }

    @Test
    fun `запити зі сторінок їдуть підписаними, разом із показаними`() = runTest {
        val calls = Calls().apply { reply = 200 to """{"venues":[],"requests":[]}""" }
        var signed: String? = null
        val client = VenueClient(
            baseUrl = "https://example.test",
            signer = { fail("ключ заяв тут не підписує") as Nothing },
            http = calls,
            owner = VenueClient.OwnerSigner { sentence ->
                signed = sentence("PUB", "00112233445566778899aabbccddeeff", 42L)
                VenueClient.Proof("PUB", "00112233445566778899aabbccddeeff", 42L, "SIG")
            },
        )
        val answer = client.requests(listOf("0123456789abcdef"))
        assertTrue(answer.toString(), answer is VenueClient.Answer.Ok)
        assertEquals(VenueClient.requestsSentence("PUB", "00112233445566778899aabbccddeeff", 42L, listOf("0123456789abcdef")), signed)
        val (method, url, json) = calls.seen.single()
        assertEquals("POST" to "https://example.test/venues/requests/pull", method to url)
        val body = Json.parseToJsonElement(json!!).jsonObject
        assertEquals("SIG", body["signature"]!!.jsonPrimitive.content)
        assertEquals(listOf("0123456789abcdef"), body["ack"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `без ключа особи чи з кривою позначкою запитів не питаємо`() = runTest {
        val calls = Calls()
        val none = VenueClient("https://example.test", { null }, calls)
        assertEquals(VenueClient.Answer.NoKey, none.requests(emptyList()))
        val some = VenueClient("https://example.test", { null }, calls, owner = { VenueClient.Proof("P", "N", 1L, "S") })
        assertEquals(VenueClient.Answer.BadInput, some.requests(listOf("../x")))
        assertTrue(calls.seen.isEmpty())
    }
}
