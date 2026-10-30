package local.phantom.companion.core.tool.impl

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResearchToolTest {

    private val stubContext = Application()

    private fun ctx() = ToolContext(
        appContext = stubContext,
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = setOf(ToolPermission.NETWORK),
    )

    private fun args(question: String): JsonObject = buildJsonObject {
        put("question", JsonPrimitive(question))
    }

    private val liteHtml = """
        <html><body><table>
        <tr><td><a href="https://uk.wikipedia.org/wiki/A" class='result-link'>Стаття A</a></td></tr>
        <tr><td class='result-snippet'>сніпет A</td></tr>
        <tr><td><a href="https://example.org/b" class='result-link'>Стаття B</a></td></tr>
        <tr><td class='result-snippet'>сніпет B</td></tr>
        </table></body></html>
    """.trimIndent()

    private val pageHtml = """
        <html><head><style>.x{}</style></head><body>
        <script>var hidden = "не має потрапити";</script>
        <h1>Стаття A</h1><p>Перший абзац із фактами про питання оператора.</p>
        <p>${"Довгий змістовний текст. ".repeat(30)}</p>
        </body></html>
    """.trimIndent()

    private fun engine() = MockEngine { request ->
        val url = request.url.toString()
        when {
            url.contains("lite.duckduckgo.com") -> respond(
                content = liteHtml,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/html"),
            )
            url.startsWith("https://uk.wikipedia.org") -> respond(
                content = pageHtml,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "text/html; charset=utf-8"),
            )
            else -> respondError(HttpStatusCode.InternalServerError)
        }
    }

    @Test
    fun `pipeline decomposes, searches, reads pages and filters invented sources`() = runTest {
        val completeCalls = mutableListOf<Pair<String, String>>()
        val tool = DeepResearchTool(
            httpClient = HttpClient(engine()),
            complete = { system, user ->
                completeCalls += system to user
                if (system.contains("плановик")) {
                    """{"queries":["грань перша","грань друга"]}"""
                } else {
                    // Synth cites one REAL url and one INVENTED — the fake must be dropped.
                    """{"essence":"Суть одним реченням","answer":"Розгорнута відповідь.",
                        "sources":[{"title":"Стаття A","url":"https://uk.wikipedia.org/wiki/A"},
                                   {"title":"Фейк","url":"https://invented.example/xyz"}]}"""
                }
            },
        )
        val notices = mutableListOf<String>()
        val result = tool.execute(args("складне питання"), ctx()) { ev ->
            if (ev is ToolEvent.Notice) notices += ev.message
        }

        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        result as ToolResult.Ok

        // Decompose + synth = 2 model turns.
        assertEquals(2, completeCalls.size)
        // The synth turn must carry actual page text, not just snippets.
        assertTrue(completeCalls[1].second.contains("Перший абзац"))
        // Script/style content never reaches the corpus.
        assertFalse(completeCalls[1].second.contains("не має потрапити"))
        // Visible progress covered every stage.
        assertTrue(notices.any { it.contains("шукаю") })
        assertTrue(notices.any { it.contains("читаю") })
        assertTrue(notices.any { it.contains("синтезую") })

        val payload = Json.parseToJsonElement(result.output)
            .jsonObject["payload"]!!.jsonObject
        assertEquals("Суть одним реченням", payload["essence"]!!.jsonPrimitive.content)
        val sources = payload["sources"]!!.jsonArray
        assertEquals(1, sources.size) // invented URL filtered out (Law 1)
        assertEquals(
            "https://uk.wikipedia.org/wiki/A",
            sources[0].jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `search fully down is an honest research_unavailable`() = runTest {
        val tool = DeepResearchTool(
            httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
            complete = { _, _ -> """{"queries":["a"]}""" },
        )
        val result = tool.execute(args("питання"), ctx()) { }
        assertTrue(result is ToolResult.Err)
        assertEquals("research_unavailable", (result as ToolResult.Err).code)
        assertTrue(result.message.contains("НЕ вигадуй"))
    }

    @Test
    fun `broken decompose falls back to the raw question`() = runTest {
        var searchedQueries = 0
        val tool = DeepResearchTool(
            httpClient = HttpClient(MockEngine { request ->
                if (request.url.toString().contains("lite.duckduckgo.com")) {
                    searchedQueries++
                    respond(liteHtml, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                } else respondError(HttpStatusCode.NotFound)
            }),
            complete = { system, _ ->
                if (system.contains("плановик")) "не JSON"
                else """{"essence":"e","answer":"a","sources":[]}"""
            },
        )
        val result = tool.execute(args("моє точне питання"), ctx()) { }
        assertTrue(result is ToolResult.Ok)
        assertEquals(1, searchedQueries) // fell back to exactly the question itself
    }

    @Test
    fun `htmlToText strips scripts styles and tags`() {
        val text = DeepResearchTool.htmlToText(pageHtml)
        assertTrue(text.contains("Перший абзац"))
        assertFalse(text.contains("hidden"))
        assertFalse(text.contains("<p>"))
    }
}
