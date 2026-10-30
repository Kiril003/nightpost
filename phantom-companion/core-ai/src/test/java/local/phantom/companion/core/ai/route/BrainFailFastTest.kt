package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.BrainProfile
import local.phantom.companion.core.ai.BrainProfiles
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A52, 15.10: сервер відповідав 429/503, телефон перепитував хвилинами, а людина
 * чекала ~3,5 хв до знака поразки. Правила: 429 — одна спроба; 5xx чи мовчання —
 * одна легша модель; поразка — не пізніше 20 с.
 */
class BrainFailFastTest {

    private val seen = mutableListOf<HttpRequestData>()
    private fun posts() = seen.filter { it.method == HttpMethod.Post }
    private val json = headersOf("Content-Type", "application/json")
    private val sse = headersOf("Content-Type", "text/event-stream")
    private val answer = """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Here."}]}}]}""" + "\n\n"
    private val key = "fake-own-key"

    private fun googleAnswering(code: HttpStatusCode, body: String) = HttpClient(MockEngine { req ->
        seen += req
        respond(body, code, json)
    }) { expectSuccess = false }

    private fun own(http: HttpClient) = RetryingAIRouter(
        delegate = GeminiCloudRoute(apiKeyProvider = { key }, httpClient = http),
        fallback = GeminiCloudRoute(apiKeyProvider = { key }, httpClient = http, fixedProfile = BrainProfile.CHAT),
    )

    private fun server(http: HttpClient, firstTryMs: Long = RetryingAIRouter.FIRST_TRY_MS) = NightpostBrainRoute(
        base = "https://brain.test",
        installId = { "AbCdEfGhIjKlMnOpQrStUv" },
        allowed = { true },
        httpClient = http,
        nowMs = { 0L },
        firstTryMs = firstTryMs,
    )

    private fun serverAnswering(primary: suspend () -> Triple<HttpStatusCode, String, Map<String, String>>): HttpClient {
        var postCount = 0
        return HttpClient(MockEngine { req ->
            seen += req
            when {
                req.url.encodedPath.endsWith("/api/brain/status") -> respond("""{"brain":true}""", HttpStatusCode.OK, json)
                else -> {
                    postCount++
                    if (postCount > 1) {
                        respond(answer, HttpStatusCode.OK, sse)
                    } else {
                        val (code, body, extra) = primary()
                        respond(body, code, headersOf(*(extra + ("Content-Type" to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))
                    }
                }
            }
        }) { expectSuccess = false }
    }

    private fun model(req: HttpRequestData) = req.url.encodedPath.substringAfter("/models/").substringBefore(":")

    @Test
    fun `429 on the own key is one call and an error, with no wait`() = runTest {
        val http = googleAnswering(HttpStatusCode.TooManyRequests, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""")

        val out = own(http).complete(AIIntent.Text("hi")).toList()

        assertEquals("429 повторено: ${posts().map { model(it) }}", 1, posts().size)
        assertEquals(429, ((out.single() as AIChunk.ErrorChunk).cause as AIError.CloudRejected).httpCode)
        assertEquals("перед поразкою був сон", 0L, currentTime)
    }

    @Test
    fun `429 from the server with Retry-After is one call, and the failure is what the person sees`() = runTest {
        val http = serverAnswering { Triple(HttpStatusCode.TooManyRequests, """{"error":"upstream"}""", mapOf("Retry-After" to "30")) }

        val out = BrainChain(listOf(server(http))).complete(AIIntent.Text("hi")).toList()

        assertEquals(1, posts().size)
        val err = out.single() as AIChunk.ErrorChunk
        assertEquals(429, (err.cause as AIError.CloudRejected).httpCode)
        assertEquals(Route.NIGHTPOST_SERVER, err.route)
    }

    @Test
    fun `503 on the own key is one lite try and then an error, with no wait`() = runTest {
        val http = googleAnswering(HttpStatusCode.ServiceUnavailable, """{"error":{"code":503,"status":"UNAVAILABLE"}}""")
        val tool = ToolDefinition("clock", "time", Json.parseToJsonElement("{}").jsonObject)

        val out = own(http).complete(AIIntent.Text("hi", tools = listOf(tool))).toList()

        assertEquals(
            listOf(BrainProfiles.DEFAULT.getValue(BrainProfile.ACT), BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT)),
            posts().map { model(it) },
        )
        assertEquals(503, ((out.single() as AIChunk.ErrorChunk).cause as AIError.CloudRejected).httpCode)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `503 from the server is one retry on the same profile, and the retry answers`() = runTest {
        val http = serverAnswering { Triple(HttpStatusCode.ServiceUnavailable, """{"error":"upstream"}""", emptyMap()) }

        val out = BrainChain(listOf(server(http))).complete(AIIntent.Text("hi")).toList()

        val expectedModel = BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT)
        assertEquals(listOf(expectedModel, expectedModel), posts().map { model(it) })
        val final = out.filterIsInstance<AIChunk.Final>().singleOrNull()
        assertEquals(out.toString(), "Here.", final?.text)
        assertEquals(Route.NIGHTPOST_SERVER, final?.route)
    }

    @Test
    fun `5xx from the server twice is two calls on the same profile and an error`() = runTest {
        val http = HttpClient(MockEngine { req ->
            seen += req
            if (req.url.encodedPath.endsWith("/status")) respond("""{"brain":true}""", HttpStatusCode.OK, json)
            else respond("""{"error":"upstream"}""", HttpStatusCode.BadGateway, json)
        }) { expectSuccess = false }

        val out = BrainChain(listOf(server(http))).complete(AIIntent.Text("hi")).toList()

        assertEquals(2, posts().size)
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT), model(posts().last()))
        assertEquals(502, ((out.single() as AIChunk.ErrorChunk).cause as AIError.CloudRejected).httpCode)
    }

    @Test
    fun `a silent server model retries the same profile before the chain gives up`() = runTest {
        val http = serverAnswering { awaitCancellation() }

        val out = BrainChain(listOf(server(http, firstTryMs = 300L)), firstChunkTimeoutMs = { 3_000L })
            .complete(AIIntent.Text("hi")).toList()

        assertEquals(out.toString(), "Here.", out.filterIsInstance<AIChunk.Final>().singleOrNull()?.text)
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT), model(posts().last()))
    }

    @Test
    fun `a silent brain fails within 20 s of send, after exactly one lite try`() = runTest {
        val primary = AtomicInteger()
        val lite = AtomicInteger()
        fun silent(calls: AtomicInteger) = object : AIRouter {
            override suspend fun availableRoutes(): Set<Route> = setOf(Route.NIGHTPOST_SERVER)
            override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
                calls.incrementAndGet()
                awaitCancellation()
            }
        }
        val brain = RetryingAIRouter(delegate = silent(primary), fallback = silent(lite), clock = EmptyCoroutineContext)

        val out = BrainChain(listOf(brain)).complete(AIIntent.Text("hi")).toList()

        assertEquals(1, primary.get())
        assertEquals(1, lite.get())
        assertEquals(504, ((out.single() as AIChunk.ErrorChunk).cause as AIError.CloudRejected).httpCode)
        assertTrue("поразка на ${currentTime} мс", currentTime in 1..BrainChain.CLOUD_FIRST_CHUNK_TIMEOUT_MS)
    }

    @Test
    fun `first try and fallback end before the chain's first-chunk watchdog`() {
        // Рівно 20 с — гонка зі сторожем ланцюга: легша модель обірвалась би на останньому слові.
        assertTrue(RetryingAIRouter.FIRST_TRY_MS + RetryingAIRouter.FALLBACK_MS < BrainChain.CLOUD_FIRST_CHUNK_TIMEOUT_MS)
    }
}
