package local.phantom.companion.core.ai.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.BrainProfile
import local.phantom.companion.core.ai.BrainProfiles
import local.phantom.companion.core.ai.ToolDefinition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class BrainProfileTest {

    private val seen = mutableListOf<HttpRequestData>()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private val sseHeaders = headersOf("Content-Type", "text/event-stream")
    private val sseBody = """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]}}]}""" + "\n\n"
    private val tool = ToolDefinition("clock", "read time", Json.parseToJsonElement("{}").jsonObject)

    @Before
    fun resetProfiles() {
        seen.clear()
        BrainProfiles.restore(null)
    }

    @After
    fun restoreDefaultProfiles() {
        BrainProfiles.restore(null)
    }

    private fun posts() = seen.filter { it.method == HttpMethod.Post }
    private fun modelOf(req: HttpRequestData) = req.url.encodedPath.substringAfter("/models/").substringBefore(":")

    private fun serverClient(
        statusBody: String = """{"brain":true}""",
        firstPostCode: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient {
        var postCalls = 0
        return HttpClient(MockEngine { req ->
            seen += req
            when {
                req.url.encodedPath.endsWith("/api/brain/status") ->
                    respond(statusBody, HttpStatusCode.OK, jsonHeaders)
                else -> {
                    postCalls++
                    if (postCalls == 1 && firstPostCode != HttpStatusCode.OK) {
                        respond("""{"error":"upstream"}""", firstPostCode, jsonHeaders)
                    } else {
                        respond(sseBody, HttpStatusCode.OK, sseHeaders)
                    }
                }
            }
        }) { expectSuccess = false }
    }

    private fun serverRoute(
        http: HttpClient,
        profilesStore: ((String) -> Unit)? = null,
    ) = NightpostBrainRoute(
        base = "https://brain.test",
        installId = { "AbCdEfGhIjKlMnOpQrStUv" },
        allowed = { true },
        httpClient = http,
        nowMs = { 0L },
        profilesStore = profilesStore ?: {},
    )

    @Test
    fun `a tool turn sends act and a plain turn sends chat`() = runTest {
        val route = serverRoute(serverClient())

        route.complete(AIIntent.Text(prompt = "find time", tools = listOf(tool))).toList()
        route.complete(AIIntent.Text(prompt = "hello")).toList()
        route.complete(AIIntent.Text(prompt = "speak", profile = BrainProfile.VOICE)).toList()

        assertEquals(3, posts().size)
        assertEquals("act", posts()[0].headers[NightpostBrainRoute.PROFILE_HEADER])
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.ACT), modelOf(posts()[0]))
        assertEquals("chat", posts()[1].headers[NightpostBrainRoute.PROFILE_HEADER])
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT), modelOf(posts()[1]))
        assertEquals("voice", posts()[2].headers[NightpostBrainRoute.PROFILE_HEADER])
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.VOICE), modelOf(posts()[2]))
    }

    @Test
    fun `the body never carries profile`() = runTest {
        val http = serverClient()
        val server = serverRoute(http)
        val cloud = GeminiCloudRoute(apiKeyProvider = { "own-key" }, httpClient = http)

        server.complete(AIIntent.Text(prompt = "hi", profile = BrainProfile.SUMMARY)).toList()
        cloud.complete(AIIntent.Text(prompt = "hi", tools = listOf(tool))).toList()

        assertEquals(2, posts().size)
        posts().forEach { req ->
            val text = (req.body as TextContent).text
            val parsed = json.parseToJsonElement(text).jsonObject
            assertFalse("request body must not contain profile: $text", parsed.containsKey("profile"))
        }
    }

    @Test
    fun `own key asks the model of its profile from the saved map, then the default`() = runTest {
        val http = serverClient()
        val cloud = GeminiCloudRoute(apiKeyProvider = { "own-key" }, httpClient = http)

        BrainProfiles.accept(json.parseToJsonElement("""{"profiles":{"chat":"x-model"}}""").jsonObject)
        cloud.complete(AIIntent.Text(prompt = "hi")).toList()
        assertEquals("x-model", modelOf(posts().last()))

        val persisted = BrainProfiles.saved()
        BrainProfiles.restore(null)
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT), BrainProfiles.modelFor(BrainProfile.CHAT))

        BrainProfiles.restore(persisted)
        assertEquals("x-model", BrainProfiles.modelFor(BrainProfile.CHAT))

        BrainProfiles.restore(null)
        cloud.complete(AIIntent.Text(prompt = "hi")).toList()
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT), modelOf(posts().last()))
    }

    @Test
    fun `a 5xx retries the same profile`() = runTest {
        val route = serverRoute(serverClient(firstPostCode = HttpStatusCode.ServiceUnavailable))

        val out = route.complete(AIIntent.Text(prompt = "act now", tools = listOf(tool))).toList()

        assertEquals("ok", out.filterIsInstance<AIChunk.Final>().single().text)
        assertEquals(2, posts().size)
        assertEquals(posts()[0].url.toString(), posts()[1].url.toString())
        assertEquals("act", posts()[0].headers[NightpostBrainRoute.PROFILE_HEADER])
        assertEquals("act", posts()[1].headers[NightpostBrainRoute.PROFILE_HEADER])
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.ACT), modelOf(posts()[0]))
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.ACT), modelOf(posts()[1]))
    }

    @Test
    fun `an old status without profiles keeps working`() = runTest {
        var savedSnapshot: String? = null
        val routeWithProfiles = serverRoute(
            serverClient(statusBody = """{"brain":true,"profiles":{"chat":"server-chat","unknown":"ignored","act":"  "}}"""),
            profilesStore = { savedSnapshot = it },
        )
        routeWithProfiles.availableRoutes()
        assertEquals("server-chat", BrainProfiles.modelFor(BrainProfile.CHAT))
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.ACT), BrainProfiles.modelFor(BrainProfile.ACT))
        assertEquals("server-chat", Json.parseToJsonElement(savedSnapshot!!).jsonObject["chat"]?.toString()?.trim('"'))

        BrainProfiles.restore(null)
        BrainProfiles.accept(json.parseToJsonElement("""{"brain":true}""").jsonObject)
        BrainProfile.entries.forEach { p ->
            assertEquals(BrainProfiles.DEFAULT.getValue(p), BrainProfiles.modelFor(p))
        }
    }

    @Test
    fun `a status keeps only profiles object entries with a well formed model id`() {
        BrainProfiles.accept(json.parseToJsonElement("""{"chat":"top-level","profiles":"text"}""").jsonObject)
        BrainProfiles.accept(json.parseToJsonElement("""{"profiles":{"chat":"a/b?key=1","act":7,"voice":"ok.model-1"}}""").jsonObject)
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.CHAT), BrainProfiles.modelFor(BrainProfile.CHAT))
        assertEquals(BrainProfiles.DEFAULT.getValue(BrainProfile.ACT), BrainProfiles.modelFor(BrainProfile.ACT))
        assertEquals("ok.model-1", BrainProfiles.modelFor(BrainProfile.VOICE))
    }

    @Test
    fun `no model names outside BrainProfiles`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "core-ai").isDirectory && File(it, "app").isDirectory }
        val dirs = listOf(
            File(root, "core-ai/src/main"),
            File(root, "app/src/main/java"),
        )
        val pattern = Regex(""""gemini-[0-9a-z.-]+"""")
        val offenders = mutableListOf<String>()
        for (dir in dirs) {
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val matches = pattern.findAll(file.readText()).map { it.value }.toList()
                    if (matches.isNotEmpty() && file.name != "BrainProfile.kt") {
                        offenders += "${file.relativeTo(root)}: $matches"
                    }
                }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
