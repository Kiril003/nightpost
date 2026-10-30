package local.phantom.companion.core.voice.live

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Одноразовий токен: хто його карбує, коли він уже не годиться, і що ключ нікуди не тече. */
class LiveTokenTest {

    private val t0 = isoMs("2026-10-02T12:00:00Z")!!

    private fun pcBody(start: String = "2026-10-02T12:01:00Z", exp: String = "2026-10-02T12:30:00Z") =
        """{"token":"auth_tokens/eph-1","model":"gemini-3.8-live","expires_at":"$exp","start_before":"$start"}"""

    @Test
    fun `ПК видає токен — телефон читає його строки`() = runTest {
        val m = PcTokenSource { 200 to pcBody() }.mint(t0)
        m as Mint.Got
        assertEquals("auth_tokens/eph-1", m.token.token)
        assertEquals("gemini-3.8-live", m.token.model)
        assertEquals(t0 + 60_000, m.token.startBeforeMs)
        assertEquals(t0 + 30 * 60_000, m.token.expiresAtMs)
        assertEquals(LiveToken.Source.PC, m.token.source)
    }

    @Test
    fun `ПК без ключа — людська причина, а не HTTP`() = runTest {
        val m = PcTokenSource { 503 to """{"detail":{"code":"no_key","message":"x"}}""" }.mint(t0)
        assertEquals(Mint.Refused("на компʼютері не задано ключ Gemini"), m)
        val unpaired = PcTokenSource { null }.mint(t0)
        assertTrue(unpaired is Mint.Refused)
        val down = PcTokenSource { throw java.io.IOException("x") }.mint(t0)
        assertEquals(Mint.Refused("компʼютер недосяжний"), down)
    }

    @Test
    fun `токен живий лише до start_before мінус запас`() {
        val tok = LiveToken("t", "m", t0 + 30 * 60_000, t0 + 60_000, LiveToken.Source.PC)
        assertTrue(tok.canStartAt(t0))
        assertTrue(tok.canStartAt(t0 + 60_000 - LiveToken.START_MARGIN_MS - 1))
        assertFalse(tok.canStartAt(t0 + 60_000 - LiveToken.START_MARGIN_MS))
        assertFalse(tok.canStartAt(t0 + 31 * 60_000))
    }

    @Test
    fun `ключ власника карбує той самий одноразовий запит і не тече в причину`() = runTest {
        val key = "AIza-owner-secret"
        val seen = mutableListOf<Triple<String, String, String>>()
        val src = OwnerKeyTokenSource({ key }, { url, k, body -> seen += Triple(url, k, body); 403 to """{"error":"$k"}""" })
        val refused = src.mint(t0) as Mint.Refused
        assertFalse("ключ у причині відмови", key in refused.why)
        val (url, sentKey, body) = seen.single()
        assertEquals(AUTH_TOKENS_URL, url)
        assertEquals(key, sentKey)
        val json = LiveJson.parseToJsonElement(body).jsonObject
        assertEquals(1, json["uses"]!!.jsonPrimitive.int)
        assertEquals("2026-10-02T12:30:00Z", json["expireTime"]!!.jsonPrimitive.content)
        assertEquals("2026-10-02T12:01:00Z", json["newSessionExpireTime"]!!.jsonPrimitive.content)

        val ok = OwnerKeyTokenSource({ key }, { _, _, _ -> 200 to """{"name":"auth_tokens/own"}""" }).mint(t0)
        ok as Mint.Got
        assertEquals(LiveToken.Source.OWNER_KEY, ok.token.source)
        assertEquals(t0 + 60_000, ok.token.startBeforeMs)
        assertFalse(key in ok.token.toString())

        assertEquals(Mint.Refused("на телефоні немає ключа Gemini"), OwnerKeyTokenSource({ " " }, { _, _, _ -> error("no") }).mint(t0))
    }

    @Test
    fun `спершу ПК, потім ключ телефона, обидва відмовили — обидві причини`() = runTest {
        var now = t0
        val pcRefuses = LiveTokenSource { Mint.Refused("на компʼютері не задано ключ Gemini") }
        val own = LiveTokenSource { n -> Mint.Got(LiveToken("own", "m", n + TOKEN_TTL_MS, n + START_WINDOW_MS, LiveToken.Source.OWNER_KEY)) }
        val got = LiveTokens(listOf(pcRefuses, own)) { now }.take() as Mint.Got
        assertEquals("own", got.token.token)

        val none = LiveTokens(listOf(pcRefuses, LiveTokenSource { Mint.Refused("на телефоні немає ключа Gemini") })) { now }.take()
        assertEquals(Mint.Refused("на компʼютері не задано ключ Gemini; на телефоні немає ключа Gemini"), none)
        assertEquals(Mint.Refused(LiveTokens.NO_SOURCE), LiveTokens(emptyList()) { now }.take())
    }

    @Test
    fun `прострочений токен не віддається — береться наступне джерело`() = runTest {
        val now = t0
        val stale = LiveTokenSource { Mint.Got(LiveToken("old", "m", now + TOKEN_TTL_MS, now + 1_000, LiveToken.Source.PC)) }
        val fresh = LiveTokenSource { n -> Mint.Got(LiveToken("new", "m", n + TOKEN_TTL_MS, n + START_WINDOW_MS, LiveToken.Source.OWNER_KEY)) }
        val got = LiveTokens(listOf(stale, fresh)) { now }.take() as Mint.Got
        assertEquals("new", got.token.token)
        assertEquals(Mint.Refused(LiveTokens.STALE), LiveTokens(listOf(stale)) { now }.take())
    }

    @Test
    fun `токен одноразовий — кожне зʼєднання карбує свій, запасний віддається раз`() = runTest {
        var now = t0
        var n = 0
        val src = LiveTokenSource { at -> Mint.Got(LiveToken("t${++n}", "m", at + TOKEN_TTL_MS, at + START_WINDOW_MS, LiveToken.Source.PC)) }
        val tokens = LiveTokens(listOf(src)) { now }
        assertEquals("t1", (tokens.take() as Mint.Got).token.token)
        assertEquals("t2", (tokens.take() as Mint.Got).token.token)
        tokens.prefetch()
        assertEquals(3, n)
        assertEquals("t3", (tokens.take() as Mint.Got).token.token)
        // Запасний, що встиг протухнути, не віддається.
        tokens.prefetch()
        now += START_WINDOW_MS
        assertEquals("t5", (tokens.take() as Mint.Got).token.token)
    }

    @Test
    fun `server source parses a good reply`() = runTest {
        val m = ServerTokenSource {
            200 to """{"token":"auth_tokens/srv-1","model":"gemini-live-srv","expires_at":"2026-10-02T12:30:00Z","start_before":"2026-10-02T12:01:00Z"}"""
        }.mint(t0)
        m as Mint.Got
        assertEquals("auth_tokens/srv-1", m.token.token)
        assertEquals("gemini-live-srv", m.token.model)
        assertEquals(t0 + 60_000, m.token.startBeforeMs)
        assertEquals(t0 + 30 * 60_000, m.token.expiresAtMs)
        assertEquals(LiveToken.Source.SERVER, m.token.source)
    }

    @Test
    fun `server source refuses 429 and 503 with distinct reasons`() = runTest {
        assertEquals(Mint.Refused("server not allowed"), ServerTokenSource { null }.mint(t0))
        assertEquals(Mint.Refused("server did not recognise the device"), ServerTokenSource { 401 to "{}" }.mint(t0))
        assertEquals(Mint.Refused("live limit reached"), ServerTokenSource { 429 to "{}" }.mint(t0))
        assertEquals(Mint.Refused("server has no live voice"), ServerTokenSource { 503 to "{}" }.mint(t0))
        assertEquals(Mint.Refused("server refused (HTTP 500)"), ServerTokenSource { 500 to "{}" }.mint(t0))
    }

    @Test
    fun `server reply without model is refused`() = runTest {
        val noModel = ServerTokenSource {
            200 to """{"token":"auth_tokens/srv-1","expires_at":"2026-10-02T12:30:00Z","start_before":"2026-10-02T12:01:00Z"}"""
        }.mint(t0)
        assertTrue(noModel is Mint.Refused)
        val blankModel = ServerTokenSource {
            200 to """{"token":"auth_tokens/srv-1","model":" ","expires_at":"2026-10-02T12:30:00Z","start_before":"2026-10-02T12:01:00Z"}"""
        }.mint(t0)
        assertTrue(blankModel is Mint.Refused)
    }

    @Test
    fun `take falls through to server when pc and own key refuse`() = runTest {
        val pc = LiveTokenSource { Mint.Refused("no pc") }
        val own = LiveTokenSource { Mint.Refused("no key") }
        val srv = LiveTokenSource { n ->
            Mint.Got(LiveToken("srv", "gemini-live-srv", n + TOKEN_TTL_MS, n + START_WINDOW_MS, LiveToken.Source.SERVER))
        }
        val got = LiveTokens(listOf(pc, own, srv)) { t0 }.take() as Mint.Got
        assertEquals(LiveToken.Source.SERVER, got.token.source)
        assertEquals("srv", got.token.token)
    }
}
