package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 28.10, власник: «асистент — нічого не працює». Людина з мертвим власним ключем
 * (невірний, прострочений, вичерпаний пробний період) чула лише помилку ключа:
 * сервер Nightpost для неї був закритий. Мертвий ключ має мовчки поступитись серверу.
 */
class OwnKeyFallbackTest {

    private val seen = mutableListOf<HttpRequestData>()
    private var key: String? = "dead-key"
    private var now = 0L

    private fun http(googleCode: HttpStatusCode, googleBody: String) = HttpClient(MockEngine { req ->
        seen += req
        when {
            req.url.host == "generativelanguage.googleapis.com" ->
                respond(googleBody, googleCode, headersOf("Content-Type", "application/json"))
            req.url.encodedPath.endsWith("/api/brain/status") ->
                respond("""{"brain":true,"models":["gemini-3.8-flash"],"daily":60}""", HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"))
            else -> respond(
                """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Server answer."}]}}]}""" + "\n\n",
                HttpStatusCode.OK,
                headersOf("Content-Type", "text/event-stream"),
            )
        }
    }) { expectSuccess = false }

    private fun chain(client: HttpClient, routing: RoutingStrategy = RoutingStrategy.CloudFirst): BrainChain {
        val own = OwnKeyGuard(GeminiCloudRoute(apiKeyProvider = { key }, httpClient = client), key = { key }, nowMs = { now })
        val server = NightpostBrainRoute(
            base = "https://brain.test",
            installId = { "AbCdEfGhIjKlMnOpQrStUv" },
            allowed = { NightpostBrainRoute.mayAsk(routing, key, own.dead()) },
            httpClient = client,
            nowMs = { now },
        )
        return BrainChain(listOf(own, server))
    }

    private fun googleCalls() = seen.count { it.url.host == "generativelanguage.googleapis.com" }
    private fun serverAsks() = seen.count { it.url.host == "brain.test" && it.url.encodedPath.contains("streamGenerateContent") }

    private suspend fun BrainChain.ask(): AIChunk = complete(AIIntent.Text(prompt = "plan my evening")).toList().last()

    private val invalid = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""

    @Test
    fun `невірний власний ключ — відповідає сервер, у той самий раз`() = runTest {
        val last = chain(http(HttpStatusCode.BadRequest, invalid)).ask()
        assertTrue("людина побачила помилку ключа замість відповіді: $last", last is AIChunk.Final)
        assertEquals(Route.NIGHTPOST_SERVER, (last as AIChunk.Final).route)
        assertEquals("Server answer.", last.text)
    }

    @Test
    fun `мертвий ключ більше не питають, доки його не змінили`() = runTest {
        val c = chain(http(HttpStatusCode.BadRequest, invalid))
        c.ask()
        val google = googleCalls()
        c.ask()
        assertEquals("мертвий ключ питали вдруге", google, googleCalls())
        assertEquals(2, serverAsks())

        key = "new-key"
        c.ask()
        assertTrue("новий ключ не спробували", googleCalls() > google)
    }

    @Test
    fun `вичерпана квота ключа — сервер, а за чверть години ключ пробують знову`() = runTest {
        val quota = """{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}"""
        val c = chain(http(HttpStatusCode.TooManyRequests, quota))
        val first = c.ask()
        assertEquals(Route.NIGHTPOST_SERVER, (first as AIChunk.Final).route)
        val google = googleCalls()
        now += OwnKeyGuard.QUOTA_MS + 1
        c.ask()
        assertTrue("ключ після квоти не спробували знову", googleCalls() > google)
    }

    @Test
    fun `збій Google, що не про ключ, не веде питання через сервер`() = runTest {
        val c = chain(http(HttpStatusCode.BadRequest, """{"error":{"code":400,"message":"Request contains an invalid argument.","status":"INVALID_ARGUMENT"}}"""))
        c.ask()
        assertEquals("живий ключ людини віддав питання серверу", 0, serverAsks())
    }

    @Test
    fun `лише на пристрої — мертвий ключ теж не відкриває сервер`() = runTest {
        val c = chain(http(HttpStatusCode.BadRequest, invalid), routing = RoutingStrategy.LocalOnly)
        c.ask()
        c.ask()
        assertEquals(0, serverAsks())
    }

    @Test
    fun `відмова ключа в голосі позначає його мертвим так само, як у чаті`() {
        var now = 0L
        val g = OwnKeyGuard(delegate = GeminiCloudRoute(apiKeyProvider = { "k1" }), key = { "k1" }, nowMs = { now })
        g.note(AIError.CloudRejected(400, "Request contains an invalid argument."))
        assertFalse("загальна 400 вбила живий ключ", g.dead())
        g.note(AIError.CloudRejected(403, "PERMISSION_DENIED"))
        assertTrue("403 від Google не відкрив сервер для голосу", g.dead())
        now += OwnKeyGuard.REFUSED_MS + 1
        assertFalse(g.dead())
    }
}
