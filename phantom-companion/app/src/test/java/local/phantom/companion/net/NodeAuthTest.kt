package local.phantom.companion.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Довгоживучий месенджер тримається на одному: строк токена мусить
 * продовжуватись сам. До цих тестів 401 не розрізняв ніхто.
 */
class NodeAuthTest {

    private val jsonHeader = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun client(engine: MockEngine) = HttpClient(engine)

    // ── 401 → refresh → повтор ───────────────────────────────────────────────

    @Test
    fun `401 refreshes once and retries with the new token`() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { req ->
            val auth = req.headers[HttpHeaders.Authorization]
            seen += auth
            if (auth == "Bearer stale") respond("", HttpStatusCode.Unauthorized)
            else respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeader)
        }
        val http = client(engine)
        var refreshes = 0

        val res = callWithRefresh(
            token = { "stale" },
            refresh = { refreshes++; "fresh" },
            call = { token -> http.post("http://node/api/v1/messenger/queue/status") { bearerAuth(token) } },
        )

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(listOf("Bearer stale", "Bearer fresh"), seen)
        assertEquals("продовжувати строк треба рівно раз", 1, refreshes)
    }

    @Test
    fun `retry after 401 carries the very same client_id`() = runTest {
        // Повтор, що вигадав би новий client_id, поклав би на вузол ДРУГИЙ
        // лист — саме той збій, заради якого id мінтиться до відправки.
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            bodies += (req.body as TextContent).text
            if (req.headers[HttpHeaders.Authorization] == "Bearer stale") {
                respond("", HttpStatusCode.Unauthorized)
            } else {
                respond("""{"client_id":"ph_42"}""", HttpStatusCode.OK, jsonHeader)
            }
        }
        val http = client(engine)
        val payload = """{"client_id":"ph_42","body":"привіт"}"""

        val res = callWithRefresh(
            token = { "stale" },
            refresh = { "fresh" },
            call = { token ->
                http.post("http://node/api/v1/messenger/conversations/c1/messages") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            },
        )

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(2, bodies.size)
        assertEquals("повтор мусить везти той самий лист", bodies[0], bodies[1])
        assertTrue(bodies.all { it.contains("ph_42") })
        assertTrue(res.bodyAsText().contains("ph_42"))
    }

    @Test
    fun `a refresh that fails leaves the original answer alone`() = runTest {
        // Вузол лежить — брехати «все добре» не можна, віддаємо чесний 401.
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val http = client(engine)

        val res = callWithRefresh(
            token = { "stale" },
            refresh = { null },
            call = { token -> http.post("http://node/x") { bearerAuth(token) } },
        )

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `a healthy call never refreshes`() = runTest {
        val engine = MockEngine { respond("""{"queued":0}""", HttpStatusCode.OK, jsonHeader) }
        val http = client(engine)
        var refreshes = 0

        callWithRefresh(
            token = { "good" },
            refresh = { refreshes++; "other" },
            call = { token -> http.post("http://node/x") { bearerAuth(token) } },
        )

        assertEquals(0, refreshes)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `no pairing means no call at all`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        val http = client(engine)
        val thrown = runCatching {
            callWithRefresh(
                token = { null },
                refresh = { null },
                call = { token -> http.post("http://node/x") { bearerAuth(token) } },
            )
        }.exceptionOrNull()

        assertTrue(thrown is PhantomApiException)
        assertEquals(0, engine.requestHistory.size)
    }

    // ── Строк ────────────────────────────────────────────────────────────────

    @Test
    fun `the challenge is device_id colon nonce`() {
        assertEquals(
            "dev-7:bm9uY2U=",
            refreshChallenge("dev-7", "bm9uY2U=").toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `a token with under a day left is refreshed ahead of time`() {
        val now = 1_755_000_000_000L
        val inTwelveHours = java.time.Instant.ofEpochMilli(now + 12 * 3_600_000L).toString()
        assertTrue(expiresSoon(inTwelveHours, now))
    }

    @Test
    fun `a token with weeks left is left alone`() {
        val now = 1_755_000_000_000L
        val inThirtyDays = java.time.Instant.ofEpochMilli(now + 30L * 24 * 3_600_000L).toString()
        assertFalse(expiresSoon(inThirtyDays, now))
    }

    @Test
    fun `an expired token is refreshed`() {
        val now = 1_755_000_000_000L
        val yesterday = java.time.Instant.ofEpochMilli(now - 24 * 3_600_000L).toString()
        assertTrue(expiresSoon(yesterday, now))
    }

    @Test
    fun `an unreadable expiry does not send us to the node every call`() {
        // Нерозбірливий рядок мовчить: строк доживе до 401, і його підбере повтор.
        assertFalse(expiresSoon("", 1L))
        assertFalse(expiresSoon("не-дата", 1L))
    }

    @Test
    fun `node time is read with a zone and without one`() {
        // /pair/refresh віддає час з поясом, а стрічка — голий UTC.
        val withZone = parseInstantMs("2026-08-22T16:08:06+00:00")
        val bare = parseInstantMs("2026-08-22T16:08:06")
        assertEquals(withZone, bare)
        assertEquals(parseInstantMs("2026-08-22T16:08:06Z"), bare)
        assertNull(parseInstantMs("   "))
    }
}
