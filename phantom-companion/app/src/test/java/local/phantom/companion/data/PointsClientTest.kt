package local.phantom.companion.data

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Клієнт балів — те, що телефон каже серверу `points.py`.
 *
 * Речення під підписом мусять збігатися з серверними БАЙТ У БАЙТ, інакше
 * кожна заява з телефона — 401. Тому звірка йде з ТИМ САМИМ файлом векторів,
 * яким сервер перевіряє себе (`points_sentence_vectors.json`, його вичитує
 * `test_points.py::test_вектор_речень_балів`).
 */
class PointsClientTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "platform-site").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь моно-дерева не знайдено") as Nothing
    }

    private val vectors by lazy {
        val f = File(repoRoot(), "platform-site/server/tests/vectors/points_sentence_vectors.json")
        if (!f.isFile) fail("немає спільних векторів балів: $f")
        Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun case(kind: String): JsonObject =
        vectors["cases"]!!.jsonArray.map { it.jsonObject }.first { it["kind"]!!.jsonPrimitive.content == kind }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class Calls : VenueClient.Http {
        val seen = mutableListOf<Triple<String, String, String?>>()
        var reply: Pair<Int, String>? = 200 to """{"checked_in":true,"awarded":[],"waiting_for":1}"""
        override suspend fun call(method: String, url: String, json: String?): Pair<Int, String>? {
            seen += Triple(method, url, json)
            return reply
        }
    }

    /** Підписувач із фіксованим ключем, числом і часом вектора — як у серверному файлі. */
    private fun signerOf(c: JsonObject) = VenueClient.deviceSigner(
        SeedProver(hex(vectors.s("seed_hex")), c.s("pub_key"), c.s("nonce"), c["issued_ms"]!!.jsonPrimitive.long),
    )

    @Test
    fun `заява «я на місці» — те саме речення і той самий підпис, що в сервера`() = runTest {
        val c = case("checkin")
        val calls = Calls()
        val client = PointsClient("https://example.test/", signerOf(c), calls)
        val answer = client.checkin(c.s("meetup_id"), c.s("venue_id"), c.s("cell"), c["meetup_at"]!!.jsonPrimitive.long)
        assertTrue(answer.toString(), answer is VenueClient.Answer.Ok)
        val (method, url, json) = calls.seen.single()
        assertEquals("POST", method)
        assertEquals("https://example.test/points/checkin", url)
        val body = Json.parseToJsonElement(json!!).jsonObject
        assertEquals(c.s("signature"), body.s("signature"))
        assertEquals(c.s("cell"), body.s("cell"))
        assertEquals(c["meetup_at"]!!.jsonPrimitive.long, body["meetup_at"]!!.jsonPrimitive.long)
        assertEquals(c.s("sentence"), PointsClient.checkinSentence(
            c.s("pub_key"), c.s("nonce"), c["issued_ms"]!!.jsonPrimitive.long,
            c.s("meetup_id"), c.s("venue_id"), c.s("cell"), c["meetup_at"]!!.jsonPrimitive.long,
        ))
    }

    /** Гаманець вектора: той самий seed, що в серверному файлі. */
    private fun walletSigner(): (ByteArray) -> ByteArray = { message ->
        local.phantom.companion.core.net.crypto.Ed25519.sign(hex(vectors.s("wallet_seed_hex")), message)
    }

    @Test
    fun `заява про оплачену частку — два підписи під одним реченням, байт у байт як у сервера`() = runTest {
        val s = case("settled")
        val calls = Calls().apply { reply = 200 to """{"status":"confirmed","awarded":[]}""" }
        val client = PointsClient("https://example.test", signerOf(s), calls)
        val signedByWallet = mutableListOf<String>()
        val wallet = walletSigner()
        val answer = client.settled(s.s("cluster"), s.s("tx"), s.s("address"), s.s("ref")) { message ->
            signedByWallet += String(message, Charsets.UTF_8)
            wallet(message)
        }
        assertTrue(answer.toString(), answer is VenueClient.Answer.Ok)
        val (method, url, json) = calls.seen.single()
        assertEquals("POST", method)
        assertEquals("https://example.test/points/settled", url)
        val sent = Json.parseToJsonElement(json!!).jsonObject
        assertEquals("підпис пристрою", s.s("signature"), sent.s("signature"))
        assertEquals("підпис гаманця", s.s("wallet_signature"), sent.s("wallet_signature"))
        assertEquals(s.s("ref"), sent.s("ref"))
        assertEquals(s.s("address"), sent.s("address"))
        assertEquals("гаманець підписав інше речення, ніж пристрій", listOf(s.s("sentence")), signedByWallet)
        assertEquals(s.s("sentence"), PointsClient.settledSentence(
            s.s("pub_key"), s.s("nonce"), s["issued_ms"]!!.jsonPrimitive.long,
            s.s("cluster"), s.s("tx"), s.s("address"), s.s("ref"),
        ))
        assertTrue("клієнт названий кількість балів", "points" !in sent.keys)
    }

    @Test
    fun `запит власних балів — теж байт у байт`() = runTest {
        val m = case("me")
        val mine = Calls().apply { reply = 200 to """{"total":0,"events":[]}""" }
        assertTrue(PointsClient("https://example.test", signerOf(m), mine).me() is VenueClient.Answer.Ok)
        assertEquals(m.s("signature"), Json.parseToJsonElement(mine.seen.single().third!!).jsonObject.s("signature"))
        assertEquals("https://example.test/points/me", mine.seen.single().second)
        assertEquals(m.s("sentence"), PointsClient.meSentence(m.s("pub_key"), m.s("nonce"), m["issued_ms"]!!.jsonPrimitive.long))
    }

    @Test
    fun `кривий вхід не доходить ні до підпису, ні до мережі`() = runTest {
        val calls = Calls()
        val client = PointsClient("https://example.test", { fail("підпис не мав статись") as Nothing }, calls)
        val ok = case("checkin")
        assertEquals(VenueClient.Answer.BadInput, client.checkin("не-uuid", "cafe", "881e805011fffff", 1L))
        assertEquals(VenueClient.Answer.BadInput, client.checkin(ok.s("meetup_id"), "../x", "881e805011fffff", 1L))
        assertEquals(VenueClient.Answer.BadInput, client.checkin(ok.s("meetup_id"), "cafe", "88/../x", 1L))
        assertEquals(VenueClient.Answer.BadInput, client.checkin(ok.s("meetup_id"), "cafe", "881e805011fffff", -1L))
        val bill = case("settled").s("ref")
        val noWallet: (ByteArray) -> ByteArray = { fail("гаманець не мав підписувати") as Nothing }
        assertEquals(VenueClient.Answer.BadInput, client.settled("regtest", "5".repeat(88), "1".repeat(32), bill, noWallet))
        // Лише devnet: mainnet не доходить ні до підпису, ні до мережі.
        assertEquals(VenueClient.Answer.BadInput, client.settled("mainnet", "5".repeat(88), "1".repeat(32), bill, noWallet))
        assertEquals(VenueClient.Answer.BadInput, client.settled("devnet", "0OIl" + "5".repeat(84), "1".repeat(32), bill, noWallet))
        assertEquals(VenueClient.Answer.BadInput, client.settled("devnet", "5".repeat(88), "short", bill, noWallet))
        // id рахунку — uuid, як у Memo переказу.
        assertEquals(VenueClient.Answer.BadInput, client.settled("devnet", "5".repeat(88), "1".repeat(32), "bill-1", noWallet))
        assertEquals(VenueClient.Answer.BadInput, client.settled("devnet", "5".repeat(88), "1".repeat(32), "", noWallet))
        assertTrue("клієнт постукав із кривим входом: ${calls.seen}", calls.seen.isEmpty())
    }

    @Test
    fun `без ключа заяви немає, без адреси сервера — чесне «не ввімкнено»`() = runTest {
        val calls = Calls()
        val ok = case("checkin")
        assertEquals(
            VenueClient.Answer.NoKey,
            PointsClient("https://example.test", { null }, calls)
                .checkin(ok.s("meetup_id"), ok.s("venue_id"), ok.s("cell"), 1L),
        )
        assertEquals(
            VenueClient.Answer.NotEnabled,
            PointsClient("", { fail("без адреси підписувати нічого") as Nothing }, calls).me(),
        )
        // Без ключа пристрою гаманець теж не підписує: підписувати нічого.
        val settled = case("settled")
        assertEquals(
            VenueClient.Answer.NoKey,
            PointsClient("https://example.test", { null }, calls)
                .settled("devnet", settled.s("tx"), settled.s("address"), settled.s("ref")) { fail("гаманець не мав підписувати") as Nothing },
        )
        assertTrue(calls.seen.isEmpty())
    }

    @Test
    fun `відмова сервера балів несе його речення, а нема мережі — Unreachable`() = runTest {
        val calls = Calls()
        val c = case("checkin")
        val client = PointsClient("https://example.test", signerOf(c), calls)
        calls.reply = 422 to """{"refused":"not_at_venue","message_uk":"Не в закладі","message_en":"Not at the venue"}"""
        assertEquals(
            VenueClient.Answer.Refused(422, "Не в закладі", "Not at the venue", reason = "not_at_venue"),
            client.checkin(c.s("meetup_id"), c.s("venue_id"), c.s("cell"), 1L),
        )
        calls.reply = null
        assertEquals(VenueClient.Answer.Unreachable, client.me())
        // 202 «мережа ще не підтвердила» — не відмова: веб питатиме пізніше.
        calls.reply = 202 to """{"status":"pending"}"""
        assertEquals(
            VenueClient.Answer.Ok("""{"status":"pending"}"""),
            client.settled("devnet", "5".repeat(88), "1".repeat(32), case("settled").s("ref")) { ByteArray(64) },
        )
    }

    @Test
    fun `сервер без дверей балів — «не ввімкнено», а не «немає звʼязку»`() = runTest {
        // phantom-os.dev, поки /points/* не розгорнуто: сторінка сайту, не JSON FastAPI.
        val c = case("me")
        for (body in listOf("<!DOCTYPE html><title>Page not found</title>", """{"detail":"Not Found"}""")) {
            val calls = Calls().apply { reply = 404 to body }
            val client = PointsClient("https://example.test", signerOf(c), calls)
            assertEquals(VenueClient.Answer.NotEnabled, client.me())
            assertEquals(VenueClient.Answer.NotEnabled, client.checkin(case("checkin").s("meetup_id"), "0123456789abcdef", "881e805011fffff", 1L))
            val s = case("settled")
            assertEquals(
                VenueClient.Answer.NotEnabled,
                client.settled("devnet", s.s("tx"), s.s("address"), s.s("ref")) { ByteArray(64) },
            )
        }
    }
}
