package local.phantom.companion.core.tool.impl

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `web.search` — DDG Lite parser + honest degradation. The parser is a
 * pure function tested against canned markup; execute() is driven by
 * MockEngine.
 */
class WebSearchToolTest {

    private val stubContext = Application()

    private fun ctx() = ToolContext(
        appContext = stubContext,
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = setOf(ToolPermission.NETWORK),
    )

    private val liteHtml = """
        <html><body><form><table>
        <tr><td valign="top">1.&nbsp;</td>
        <td><a rel="nofollow" href="https://uk.wikipedia.org/wiki/%D0%9A%D0%B8%D1%97%D0%B2" class='result-link'>Київ — <b>Вікіпедія</b></a></td></tr>
        <tr><td>&nbsp;</td><td class='result-snippet'>Ки́їв — столиця та найбільше місто України&#39;s...</td></tr>
        <tr><td>&nbsp;</td><td><span class='link-text'>uk.wikipedia.org/wiki/Київ</span></td></tr>
        <tr><td valign="top">2.&nbsp;</td>
        <td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkyivcity.gov.ua%2F&amp;rut=abc" class="result-link">Офіційний портал Києва</a></td></tr>
        <tr><td>&nbsp;</td><td class="result-snippet">Новини &amp; послуги міста</td></tr>
        </table></form></body></html>
    """.trimIndent()

    @Test
    fun `parser extracts titles urls snippets`() {
        val hits = WebSearchTool.parseDdgLite(liteHtml)
        assertEquals(2, hits.size)
        assertEquals("Київ — Вікіпедія", hits[0].title)
        assertTrue(hits[0].url.startsWith("https://uk.wikipedia.org/wiki/"))
        assertTrue(hits[0].snippet.contains("столиця"))
        // uddg redirect must be unwrapped to the real target URL.
        assertEquals("Офіційний портал Києва", hits[1].title)
        assertEquals("https://kyivcity.gov.ua/", hits[1].url)
        assertEquals("Новини & послуги міста", hits[1].snippet)
    }

    @Test
    fun `parser degrades to empty on markup drift`() {
        assertTrue(WebSearchTool.parseDdgLite("<html><body>щось інше</body></html>").isEmpty())
    }

    @Test
    fun `execute returns structured results`() = runTest {
        val engine = MockEngine {
            respond(content = liteHtml, headers = headersOf("Content-Type", "text/html"))
        }
        val tool = WebSearchTool(HttpClient(engine))
        val res = tool.execute(
            args = buildJsonObject { put("query", "Київ") },
            context = ctx(),
            emit = {},
        )
        val ok = res as ToolResult.Ok
        val results = ok.structured!!["results"]!!.jsonArray
        assertEquals(2, results.size)
        assertEquals(
            "https://kyivcity.gov.ua/",
            results[1].jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `bot wall surfaces honest web_blocked error`() = runTest {
        val engine = MockEngine {
            respond(content = "<html>Unfortunately, bots use DuckDuckGo too — anomaly detected</html>")
        }
        val tool = WebSearchTool(HttpClient(engine))
        val res = tool.execute(
            args = buildJsonObject { put("query", "test") },
            context = ctx(),
            emit = {},
        )
        assertEquals("web_blocked", (res as ToolResult.Err).code)
    }

    @Test
    fun `http failure surfaces honest web_unavailable error`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
        val tool = WebSearchTool(HttpClient(engine))
        val res = tool.execute(
            args = buildJsonObject { put("query", "test") },
            context = ctx(),
            emit = {},
        )
        assertEquals("web_unavailable", (res as ToolResult.Err).code)
    }

    @Test
    fun `blank query is bad_args`() = runTest {
        val engine = MockEngine { respond(content = liteHtml) }
        val tool = WebSearchTool(HttpClient(engine))
        val res = tool.execute(
            args = buildJsonObject { put("query", "  ") },
            context = ctx(),
            emit = {},
        )
        assertEquals("bad_args", (res as ToolResult.Err).code)
    }
}
