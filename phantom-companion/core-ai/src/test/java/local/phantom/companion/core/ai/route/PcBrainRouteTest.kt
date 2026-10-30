package local.phantom.companion.core.ai.route

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.InlineAttachment
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcBrainRouteTest {

    private val asked = mutableListOf<Pair<String, String?>>()

    private fun route(
        reachable: Boolean = true,
        cloudCanRunTools: Boolean = false,
        reply: () -> PcBrainRoute.Reply,
    ) = PcBrainRoute(
        reachable = { reachable },
        ask = { content, session -> asked += content to session; reply() },
        yieldToolTurns = { cloudCanRunTools },
        nowMs = { 0L },
    )

    private val ok = PcBrainRoute.Reply(200, """{"message":{"role":"assistant","content":"Привіт з ПК"},"session_id":"s-1","auto_tts":false}""")

    @Test
    fun `недосяжний ПК не називає себе розумом`() = runTest {
        assertTrue(route(reachable = false) { ok }.availableRoutes().isEmpty())
        assertEquals(setOf(Route.PC_NODE), route { ok }.availableRoutes())
    }

    @Test
    fun `відповідь ПК приходить цілою і підписана PC_NODE`() = runTest {
        val out = route { ok }.complete(AIIntent.Text("привіт")).toList()
        assertEquals(
            listOf(
                AIChunk.Token("Привіт з ПК", Route.PC_NODE, isFinal = true),
                AIChunk.Final("Привіт з ПК", tokensUsed = 0, route = Route.PC_NODE, durationMs = 0),
            ),
            out,
        )
    }

    @Test
    fun `розмова з ПК тримає одну сесію`() = runTest {
        val r = route { ok }
        r.complete(AIIntent.Text("раз")).toList()
        r.complete(AIIntent.Text("два")).toList()
        assertNull(asked[0].second)
        assertEquals("s-1", asked[1].second)
    }

    @Test
    fun `503 від ПК — помилка ПК з його поясненням, не вигаданий текст`() = runTest {
        val out = route { PcBrainRoute.Reply(503, """{"detail":"AI unavailable: no provider"}""") }
            .complete(AIIntent.Text("привіт")).toList()
        val cause = (out.single() as AIChunk.ErrorChunk).cause as AIError.PcUnavailable
        assertEquals(503, cause.httpCode)
        assertEquals("AI unavailable: no provider", cause.detail)
    }

    @Test
    fun `обрив звʼязку — помилка, а не порожня відповідь`() = runTest {
        val out = route { throw java.io.IOException("reset") }.complete(AIIntent.Text("привіт")).toList()
        assertEquals(0, ((out.single() as AIChunk.ErrorChunk).cause as AIError.PcUnavailable).httpCode)
    }

    @Test
    fun `порожній вміст — не відповідь`() = runTest {
        val out = route { PcBrainRoute.Reply(200, """{"message":{"content":"  "}}""") }.complete(AIIntent.Text("привіт")).toList()
        assertTrue((out.single() as AIChunk.ErrorChunk).cause is AIError.PcUnavailable)
    }

    @Test
    fun `хід з інструментами телефона віддає хмарі, коли вона може ними керувати`() = runTest {
        val tools = listOf(ToolDefinition("phone.flashlight", "", JsonObject(emptyMap())))
        val out = route(cloudCanRunTools = true) { ok }.complete(AIIntent.Text("увімкни ліхтарик", tools = tools)).toList()
        assertTrue(out.isEmpty())
        assertTrue(asked.isEmpty())
        val alone = route(cloudCanRunTools = false) { ok }.complete(AIIntent.Text("увімкни ліхтарик", tools = tools)).toList()
        assertEquals(Route.PC_NODE, (alone.last() as AIChunk.Final).route)
    }

    @Test
    fun `вкладення ПК не приймає, і про це сказано`() = runTest {
        val out = route { ok }.complete(
            AIIntent.Text("що на фото", attachments = listOf(InlineAttachment("image/jpeg", "AAAA"))),
        ).toList()
        assertEquals(415, ((out.single() as AIChunk.ErrorChunk).cause as AIError.PcUnavailable).httpCode)
        assertTrue(asked.isEmpty())
    }
}
