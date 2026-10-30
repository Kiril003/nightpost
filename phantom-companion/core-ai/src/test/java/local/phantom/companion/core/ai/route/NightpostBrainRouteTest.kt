package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.BrainProfile
import local.phantom.companion.core.ai.BrainProfiles
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Збірка 17 на A52: людина без ключа й без ПК питає — і чує «розум ще не
 * ввімкнено». Сервер Nightpost — четвертий розум: після ключа, ПК і перед
 * правилами; лише коли сервер сам сказав `brain: true`, і ніколи на «лише на
 * пристрої».
 */
class NightpostBrainRouteTest {

    private val base = "https://brain.test"
    private val device = "AbCdEfGhIjKlMnOpQrStUv"
    private val seen = mutableListOf<HttpRequestData>()

    private fun client(
        status: String? = """{"brain":true,"models":["gemini-3.8-flash"],"daily":60}""",
        brainCode: HttpStatusCode = HttpStatusCode.OK,
        brainBody: String = """{"error":"upstream"}""",
        statusCode: HttpStatusCode = HttpStatusCode.OK,
    ) =
        HttpClient(MockEngine { req ->
            seen += req
            when {
                req.url.encodedPath.endsWith("/api/brain/status") ->
                    if (status == null) respond("", HttpStatusCode.NotFound)
                    else respond(status, statusCode, headersOf("Content-Type", "application/json"))
                brainCode != HttpStatusCode.OK ->
                    respond(brainBody, brainCode, headersOf("Content-Type", "application/json"))
                else -> respond(
                    """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"I can plan a meetup."}]}}]}""" + "\n\n",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "text/event-stream"),
                )
            }
        }) { expectSuccess = false }

    private fun server(
        http: HttpClient,
        allowed: Boolean = true,
        clock: () -> Long = { 0L },
        at: String = base,
    ) = NightpostBrainRoute(
        base = at,
        installId = { device },
        allowed = { allowed },
        httpClient = http,
        nowMs = clock,
    )

    private val pcAbsent = PcBrainRoute(reachable = { false }, ask = { _, _ -> error("ПК не спарений") })

    private fun posts() = seen.filter { it.method == HttpMethod.Post }
    private fun statusGets() = seen.filter { it.method == HttpMethod.Get && it.url.encodedPath.endsWith("/status") }

    @Test
    fun `без ключа і без ПК відповідає сервер, і відповідь підписана сервером`() = runTest {
        val http = client()
        val chain = BrainChain(listOf(pcAbsent, GeminiCloudRoute(apiKeyProvider = { null }, httpClient = http), server(http)))

        val out = chain.complete(AIIntent.Text("what can you do")).toList()

        val final = out.filterIsInstance<AIChunk.Final>().single()
        assertEquals("I can plan a meetup.", final.text)
        assertEquals(Route.NIGHTPOST_SERVER, final.route)
        assertTrue(out.none { it is AIChunk.ErrorChunk })
        assertTrue("чужий маршрут на відповіді сервера", out.all { routeOf(it) == Route.NIGHTPOST_SERVER })
        assertEquals(setOf(Route.NIGHTPOST_SERVER), chain.availableRoutes())

        val post = posts().single()
        val chatModel = BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT)
        assertEquals(
            "https://brain.test/api/brain/v1beta/models/$chatModel:streamGenerateContent?alt=sse",
            post.url.toString(),
        )
        assertEquals(device, post.headers[NightpostBrainRoute.DEVICE_HEADER])
        assertFalse("ключ у запиті до сервера", post.url.toString().contains("key="))
        assertEquals(null, post.headers["x-goog-api-key"])
    }

    @Test
    fun `власний ключ іде першим, і сервер тоді не чіпається взагалі`() = runTest {
        val http = client()
        val own = GeminiCloudRoute(apiKeyProvider = { "fake-own-key" }, httpClient = http)
        val out = BrainChain(listOf(pcAbsent, own, server(http))).complete(AIIntent.Text("hi there")).toList()

        assertEquals(Route.CLOUD_GEMINI, out.filterIsInstance<AIChunk.Final>().single().route)
        assertTrue("до сервера пішов запит, хоча відповів власний ключ", seen.none { it.url.host == "brain.test" })
    }

    @Test
    fun `статус false — розуму немає і жодного звернення до мозку сервера`() = runTest {
        val http = client(status = """{"brain":false,"models":[],"daily":60}""")
        val route = server(http)

        assertTrue(route.availableRoutes().isEmpty())
        val out = BrainChain(listOf(pcAbsent, route)).complete(AIIntent.Text("what can you do")).toList()

        assertTrue((out.single() as AIChunk.ErrorChunk).cause is AIError.NoBrain)
        assertTrue("сервер без розуму дістав запит на відповідь", posts().isEmpty())
    }

    @Test
    fun `«лише на пристрої» — сервер не чує ні статусу, ні питання`() = runTest {
        val http = client()
        val route = server(http, allowed = false)

        assertTrue(route.availableRoutes().isEmpty())
        val direct = route.complete(AIIntent.Text("what can you do")).toList()
        BrainChain(listOf(pcAbsent, route)).complete(AIIntent.Text("what can you do")).toList()

        assertTrue(direct.single() is AIChunk.ErrorChunk)
        assertTrue("LocalOnly, а з телефона пішло: ${seen.map { it.url }}", seen.isEmpty())
    }

    @Test
    fun `збірка без адреси сервера нікуди не ходить`() = runTest {
        val http = client()
        assertTrue(server(http, at = "").availableRoutes().isEmpty())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `спарений ПК відповідає сам, сервер не чує питання`() = runTest {
        val http = client()
        val pc = PcBrainRoute(reachable = { true }, ask = { _, _ -> PcBrainRoute.Reply(200, """{"message":{"content":"з ПК"}}""") })
        val out = BrainChain(listOf(pc, GeminiCloudRoute(apiKeyProvider = { null }, httpClient = http), server(http)))
            .complete(AIIntent.Text("what can you do")).toList()

        assertEquals(Route.PC_NODE, out.filterIsInstance<AIChunk.Final>().single().route)
        assertTrue(posts().isEmpty())
    }

    @Test
    fun `сервер не відповів на статус — питає знову за хвилину, а не за десять`() = runTest {
        var now = 0L
        var calls = 0
        val down = HttpClient(MockEngine { calls++; throw java.io.IOException("offline") }) { expectSuccess = false }
        val route = server(down, clock = { now })

        route.availableRoutes()
        route.availableRoutes()
        assertEquals(1, calls)
        now += 61_000L
        route.availableRoutes()
        assertEquals(2, calls)
    }

    @Test
    fun `статус живе десять хвилин, а не питається на кожен хід`() = runTest {
        var now = 0L
        val route = server(client(), clock = { now })

        route.availableRoutes()
        now += 9 * 60_000L
        route.availableRoutes()
        assertEquals(1, statusGets().size)

        now += 2 * 60_000L
        route.availableRoutes()
        assertEquals(2, statusGets().size)
    }

    @Test
    fun `403 і 503 від Google без слів «розуму немає» не гасять маршрут і не кажуть перевірити ключ`() = runTest {
        // Один збій Google раніше вимикав помічника на десять хвилин: у людини без
        // ключа відповіддю було «нема чим думати» — той самий симптом, що й був P0.
        for (code in listOf(HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized, HttpStatusCode.ServiceUnavailable)) {
            seen.clear()
            val route = server(client(brainCode = code))

            val err = route.complete(AIIntent.Text("hello")).toList().single() as AIChunk.ErrorChunk
            val cause = err.cause as AIError.CloudRejected

            assertEquals("$code: ні «перевір ключ» (401/403), ні «розуму немає» (503)", 502, cause.httpCode)
            assertEquals(Route.NIGHTPOST_SERVER, err.route)
            assertEquals("$code: маршрут погас", setOf(Route.NIGHTPOST_SERVER), route.availableRoutes())

            route.complete(AIIntent.Text("hello again")).toList()
            val perAsk = if (code == HttpStatusCode.ServiceUnavailable) 2 else 1
            assertEquals("$code: друге питання не дійшло до сервера", 2 * perAsk, posts().size)
            assertEquals("$code: статус перепитано", 1, statusGets().size)
        }
    }

    @Test
    fun `сервер, що сам каже brain=false тілом, гасне до нового статусу і не каже перевірити ключ`() = runTest {
        val route = server(client(brainCode = HttpStatusCode.ServiceUnavailable, brainBody = """{"brain":false}"""))
        assertEquals(setOf(Route.NIGHTPOST_SERVER), route.availableRoutes())

        val err = route.complete(AIIntent.Text("hello")).toList().single() as AIChunk.ErrorChunk

        assertEquals(Route.NIGHTPOST_SERVER, err.route)
        assertEquals(503, (err.cause as AIError.CloudRejected).httpCode)
        assertTrue(route.availableRoutes().isEmpty())
        assertEquals(1, statusGets().size)
    }

    @Test
    fun `без мережі на першому запиті людина чує «немає мережі», а не «нема чим думати»`() = runTest {
        val offline = HttpClient(MockEngine { throw java.io.IOException("Unable to resolve host") }) { expectSuccess = false }
        val route = server(offline)

        assertEquals("статус не прочитано — це ще не «розуму немає»", setOf(Route.NIGHTPOST_SERVER), route.availableRoutes())
        val out = BrainChain(listOf(pcAbsent, route)).complete(AIIntent.Text("what can you do")).toList()

        val err = out.single() as AIChunk.ErrorChunk
        assertTrue(err.cause.toString(), err.cause is AIError.NoNetwork)
    }

    @Test
    fun `статус не встиг, а питання проходить — відповідь є, і статус більше не питається`() = runTest {
        val slow = HttpClient(MockEngine { req ->
            seen += req
            if (req.url.encodedPath.endsWith("/status")) throw java.io.IOException("slow link")
            respond(
                """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"Here you go."}]}}]}""" + "\n\n",
                HttpStatusCode.OK,
                headersOf("Content-Type", "text/event-stream"),
            )
        }) { expectSuccess = false }
        var now = 0L
        val route = server(slow, clock = { now })

        val out = BrainChain(listOf(pcAbsent, route)).complete(AIIntent.Text("what can you do")).toList()

        assertEquals("Here you go.", out.filterIsInstance<AIChunk.Final>().single().text)
        now += 90_000L
        assertTrue(route.isUp())
        assertEquals("відповідь довела, що сервер є: статус не питається знову", 1, statusGets().size)
    }

    @Test
    fun `5xx і сторінка перехоплювача на статусі — «не знаємо», а 404 — «розуму немає»`() = runTest {
        val flaky = server(client(statusCode = HttpStatusCode.BadGateway))
        assertEquals(setOf(Route.NIGHTPOST_SERVER), flaky.availableRoutes())
        assertFalse(flaky.isUp())

        val portal = server(client(status = "<html>Sign in to the Wi-Fi</html>"))
        assertEquals(setOf(Route.NIGHTPOST_SERVER), portal.availableRoutes())

        val oldServer = server(client(status = null))
        assertTrue(oldServer.availableRoutes().isEmpty())
    }

    @Test
    fun `статус не несе номера установки, і не питається в того, хто вписав свій ключ`() = runTest {
        val http = client()
        val route = server(http)
        route.availableRoutes()
        assertEquals("номер установки пішов із питанням про статус", null, statusGets().single().headers[NightpostBrainRoute.DEVICE_HEADER])

        assertFalse(NightpostBrainRoute.mayAsk(RoutingStrategy.CloudFirst, ownKey = "fake-own-key"))
        assertFalse(NightpostBrainRoute.mayAsk(RoutingStrategy.LocalOnly, ownKey = null))
        assertTrue(NightpostBrainRoute.mayAsk(RoutingStrategy.CloudFirst, ownKey = null))
        assertTrue(NightpostBrainRoute.mayAsk(RoutingStrategy.CloudFirst, ownKey = "  "))
    }

    @Test
    fun `добовий ліміт — окремі слова і жодного зайвого запиту, а «зачекай» лишається «зачекай»`() = runTest {
        val spent = server(client(brainCode = HttpStatusCode.TooManyRequests, brainBody = """{"error":"daily_limit"}"""))

        val first = (spent.complete(AIIntent.Text("hi there")).toList().single() as AIChunk.ErrorChunk).cause as AIError.CloudRejected
        assertEquals(429, first.httpCode)
        assertTrue(first.body, NightpostBrainRoute.isSpent(first.body))
        val again = (spent.complete(AIIntent.Text("hi there")).toList().single() as AIChunk.ErrorChunk).cause as AIError.CloudRejected
        assertTrue(NightpostBrainRoute.isSpent(again.body))
        assertEquals("ліміт відомий — сервер не мучимо щоходу", 1, posts().size)
        assertFalse(spent.isUp())
        assertEquals(setOf(Route.NIGHTPOST_SERVER), spent.availableRoutes())

        assertFalse(NightpostBrainRoute.isSpent("""{"error":"rate_limited"}"""))
        assertFalse(NightpostBrainRoute.isSpent("not json"))
        assertTrue(NightpostBrainRoute.isSpent("""{"error":"daily_budget"}"""))

        seen.clear()
        val busy = server(client(brainCode = HttpStatusCode.TooManyRequests, brainBody = """{"error":"rate_limited"}"""))
        busy.complete(AIIntent.Text("one")).toList()
        busy.complete(AIIntent.Text("two")).toList()
        assertEquals("хвилинна черга не вимикає маршрут до півночі", 2, posts().size)
    }

    @Test
    fun `статус, що називає причину, ставить ліміт до першого питання`() = runTest {
        val route = server(client(status = """{"brain":false,"models":[],"daily":60,"reason":"daily_budget"}"""))

        val err = (route.complete(AIIntent.Text("hello")).toList().single() as AIChunk.ErrorChunk).cause as AIError.CloudRejected

        assertTrue(err.body, NightpostBrainRoute.isSpent(err.body))
        assertTrue("питання пішло, хоч ліміт відомий зі статусу", posts().isEmpty())
    }

    @Test
    fun `зламаний власний ключ каже «ключ не годиться» і не веде питання через наш сервер`() = runTest {
        // Справжні складники: хмарний маршрут під RetryingAIRouter (як у контейнері), правило
        // допуску з glue-коду застосунку і ланцюг. Сторінка безпеки: «не через нас».
        val http = HttpClient(MockEngine { req ->
            seen += req
            if (req.url.host == "generativelanguage.googleapis.com")
                respond("""{"error":{"message":"API key not valid"}}""", HttpStatusCode.Unauthorized,
                    headersOf("Content-Type", "application/json"))
            else respond("""{"brain":true}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }) { expectSuccess = false }
        val key = "fake-own-key"
        val own = RetryingAIRouter(GeminiCloudRoute(apiKeyProvider = { key }, httpClient = http))
        val route = server(http, allowed = NightpostBrainRoute.mayAsk(RoutingStrategy.CloudFirst, key))

        val out = BrainChain(listOf(pcAbsent, own, route)).complete(AIIntent.Text("what can you do")).toList()

        val err = out.single() as AIChunk.ErrorChunk
        assertEquals(401, (err.cause as AIError.CloudRejected).httpCode)
        assertEquals(Route.CLOUD_GEMINI, err.route)
        assertTrue("питання пішло на наш сервер: ${seen.map { it.url }}", seen.none { it.url.host == "brain.test" })
    }

    @Test
    fun `ланцюг не питає сервера про розум, коли відповість ПК чи власний ключ`() = runTest {
        val http = client()
        val pcUp = PcBrainRoute(reachable = { true }, ask = { _, _ -> PcBrainRoute.Reply(200, "{}") })
        val own = GeminiCloudRoute(apiKeyProvider = { "fake-own-key" }, httpClient = http)

        assertEquals(setOf(Route.PC_NODE, Route.CLOUD_GEMINI), BrainChain(listOf(pcUp, own, server(http))).availableRoutes())
        assertEquals(setOf(Route.CLOUD_GEMINI), BrainChain(listOf(pcAbsent, own, server(http))).availableRoutes())
        assertTrue("статус питали, хоч розум уже є: ${seen.map { it.url }}", statusGets().isEmpty())

        val bare = GeminiCloudRoute(apiKeyProvider = { null }, httpClient = http)
        assertEquals(setOf(Route.NIGHTPOST_SERVER), BrainChain(listOf(pcAbsent, bare, server(http))).availableRoutes())
        assertEquals(1, statusGets().size)
    }

    @Test
    fun `мережа впала посеред питання — людина чує «немає мережі», а не «помилка 0», і сервер не гасне`() = runTest {
        val flaky = HttpClient(MockEngine { req ->
            if (req.url.encodedPath.endsWith("/status")) respond("""{"brain":true}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            else throw java.io.IOException("Unable to resolve host")
        }) { expectSuccess = false }
        val route = server(flaky)

        val err = route.complete(AIIntent.Text("hello")).toList().single() as AIChunk.ErrorChunk

        assertTrue(err.cause.toString(), err.cause is AIError.NoNetwork)
        assertEquals(Route.NIGHTPOST_SERVER, err.route)
        assertEquals("мережа впала, а сервер Nightpost не мертвий", setOf(Route.NIGHTPOST_SERVER), route.availableRoutes())
    }

    @Test
    fun `номер установки — 128 випадкових біт у base64url, і один на апарат`() {
        val a = NightpostBrainRoute.newInstallId()
        val b = NightpostBrainRoute.newInstallId()
        assertTrue(a, Regex("[A-Za-z0-9_-]{22}").matches(a))
        assertNotEquals(a, b)

        var stored: String? = null
        val first = NightpostBrainRoute.keepInstallId(read = { stored }, write = { stored = it })
        val again = NightpostBrainRoute.keepInstallId(read = { stored }, write = { error("переписано") })
        assertEquals(first, again)
        assertTrue(Regex("[A-Za-z0-9_-]{22,64}").matches(first))
    }

    @Test
    fun `хмара за замовчуванням ходить туди ж і так само, як до сервера`() = runTest {
        val http = client()
        GeminiCloudRoute(apiKeyProvider = { "fake-own-key" }, httpClient = http).complete(AIIntent.Text("hi")).toList()

        val post = posts().single()
        val chatModel = BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/$chatModel:streamGenerateContent?key=fake-own-key&alt=sse",
            post.url.toString(),
        )
        assertEquals(null, post.headers[NightpostBrainRoute.DEVICE_HEADER])
    }

    @Test
    fun `заголовки замість ключа — запит іде без ключа, навіть коли ключ є`() = runTest {
        val http = client()
        val out = GeminiCloudRoute(
            apiKeyProvider = { "fake-own-key" },
            httpClient = http,
            endpointBase = "https://brain.test/api/brain",
            headers = { mapOf("X-Test" to "1") },
        ).complete(AIIntent.Text("hi")).toList()

        assertEquals("I can plan a meetup.", out.filterIsInstance<AIChunk.Final>().single().text)
        val post = posts().single()
        val chatModel = BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT)
        assertEquals("https://brain.test/api/brain/v1beta/models/$chatModel:streamGenerateContent?alt=sse", post.url.toString())
        assertEquals("1", post.headers["X-Test"])
    }

    private fun routeOf(c: AIChunk): Route = when (c) {
        is AIChunk.Token -> c.route
        is AIChunk.Final -> c.route
        is AIChunk.ToolCall -> c.route
        is AIChunk.ErrorChunk -> c.route
        is AIChunk.Notice -> c.route
        is AIChunk.Grounding -> c.route
    }
}
