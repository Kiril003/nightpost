package local.phantom.companion.ui.messenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.SystemPrompts
import local.phantom.companion.core.voice.stream.IncrementalSpeaker
import local.phantom.companion.self.SpeakingSink
import local.phantom.companion.self.VoiceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Огляд 14.10: C1 (ліміт сервера), C2 (голос після інструмента), C3 (стеля ланцюга). */
class ToolRoundsTest {

    private val ask = AIIntent.Text(prompt = "яка погода в Києві?")

    private fun call(name: String, id: String, route: Route) =
        AIChunk.ToolCall(name = name, arguments = "{}", callId = id, route = route)

    /** Модель, що щоразу просить ще інструмент, — найдорожчий випадок. */
    private fun greedy(route: Route, asked: MutableList<AIIntent.Text>): suspend (AIIntent.Text) -> ToolRounds.Pass = { turn ->
        asked += turn
        ToolRounds.Pass("", listOf(call("task.add", "c${asked.size}", route)), null, route)
    }

    @Test
    fun `a tool question costs the Nightpost server at most two requests`() = runTest {
        val asked = mutableListOf<AIIntent.Text>()
        var runs = 0
        val rounds = ToolRounds(
            pass = greedy(Route.NIGHTPOST_SERVER, asked),
            run = { _, calls -> runs++; calls.map { """{"title":"купити хліб"}""" } },
        )

        val out = rounds.answer(ask)

        assertEquals("запитів на сервер за одне питання", 2, asked.size)
        assertEquals(1, runs)
        assertTrue(out.toString(), out is ToolRounds.Outcome.Answer && "купити хліб" in out.text)
    }

    @Test
    fun `a plain server question without tools costs exactly one request`() = runTest {
        val asked = mutableListOf<AIIntent.Text>()
        val rounds = ToolRounds(
            pass = { turn -> asked += turn; ToolRounds.Pass("На звʼязку.", emptyList(), null, Route.NIGHTPOST_SERVER) },
            run = { _, _ -> fail("інструментів не просили") as Nothing },
        )

        val out = rounds.answer(ask)

        assertEquals("запитів на сервер за питання без інструментів", 1, asked.size)
        assertEquals(ToolRounds.Outcome.Answer("На звʼязку."), out)
    }

    @Test
    fun `own key chains up to the stated cap and then shows what the tools returned`() = runTest {
        val asked = mutableListOf<AIIntent.Text>()
        var runs = 0
        val rounds = ToolRounds(
            pass = greedy(Route.CLOUD_GEMINI, asked),
            run = { _, calls -> runs++; calls.map { """{"title":"крок $runs"}""" } },
        )

        val out = rounds.answer(ask)

        assertEquals(SystemPrompts.MAX_TOOL_ROUNDS + 1, asked.size)
        assertEquals(SystemPrompts.MAX_TOOL_ROUNDS, runs)
        assertTrue("мовчання замість зібраного: $out", out is ToolRounds.Outcome.Answer)
        val text = (out as ToolRounds.Outcome.Answer).text
        (1..SystemPrompts.MAX_TOOL_ROUNDS).forEach { assertTrue(text, "крок $it" in text) }
    }

    @Test
    fun `a proposal ends the turn without a second model pass`() = runTest {
        val asked = mutableListOf<AIIntent.Text>()
        var runs = 0
        val rounds = ToolRounds(
            pass = { turn ->
                asked += turn
                ToolRounds.Pass(
                    "Готую зустріч.",
                    listOf(call("meetup.create", "m1", Route.NIGHTPOST_SERVER)),
                    null,
                    Route.NIGHTPOST_SERVER,
                )
            },
            run = { _, calls ->
                runs++
                calls.map { """{"title":"Кава о 19:00"}""" }
            },
            endsTurn = { it.name == "meetup.create" },
        )

        val out = rounds.answer(ask)

        assertEquals(1, asked.size)
        assertEquals(1, runs)
        assertTrue(out.toString(), out is ToolRounds.Outcome.Answer)
        val text = (out as ToolRounds.Outcome.Answer).text
        assertTrue(text, "Готую зустріч." in text && "Кава о 19:00" in text)
    }

    @Test
    fun `every way a question fails ends as Failed with its cause`() = runTest {
        val first = IllegalStateException("first")
        val again = IllegalStateException("again")
        val calls = listOf(call("weather.now", "w", Route.CLOUD_GEMINI))
        fun of(vararg passes: ToolRounds.Pass): ToolRounds {
            var at = 0
            return ToolRounds(pass = { passes[at++] }, run = { _, c -> c.map { "ok" } })
        }

        assertEquals(ToolRounds.Outcome.Failed(first), of(ToolRounds.Pass("", emptyList(), first)).answer(ask))
        assertEquals(ToolRounds.Outcome.Failed(again), of(ToolRounds.Pass("", calls, null), ToolRounds.Pass("", emptyList(), again)).answer(ask))
        assertEquals(ToolRounds.Outcome.Failed(null), of(ToolRounds.Pass("", calls, null), ToolRounds.Pass(" ", emptyList(), null)).answer(ask))
    }

    @Test
    fun `the bridge line is said once per question, however many calls`() = runTest {
        val said = mutableListOf<String>()
        val sink = object : VoiceSink {
            override fun begin(messageId: String) = Unit
            override fun token(delta: String) = Unit
            override fun acting() { said += "acting" }
            override fun nextRound() { said += "next" }
            override fun done(text: String?, messageId: String) = Unit
            override fun failed(reason: String) = Unit
        }
        var pass = 0
        val rounds = ToolRounds(
            pass = {
                pass++
                when (pass) {
                    1 -> ToolRounds.Pass("", listOf(call("weather.now", "a", Route.CLOUD_GEMINI), call("geo.nearby", "b", Route.CLOUD_GEMINI)), null, Route.CLOUD_GEMINI)
                    2 -> ToolRounds.Pass("", listOf(call("reminder.add", "c", Route.CLOUD_GEMINI)), null, Route.CLOUD_GEMINI)
                    else -> ToolRounds.Pass("Готово.", emptyList(), null, Route.CLOUD_GEMINI)
                }
            },
            run = { _, calls -> calls.map { "ok" } },
            voice = sink,
        )

        rounds.answer(ask)

        assertEquals(listOf("next", "acting", "next"), said)
    }

    @Test
    fun `hands-free voice speaks the answer that comes after a tool call`() = runTest {
        val heard = mutableListOf<String>()
        val sink = speaking(this, heard)
        val answer = "У Києві сонячно. Парасолька не знадобиться."
        val rounds = ToolRounds(
            pass = streamed(
                sink,
                """{"type":"tool-call","tool":"weather.now","args":{"city":"Київ"}}""" to listOf(call("weather.now", "w", Route.NIGHTPOST_SERVER)),
                answer to emptyList(),
            ),
            run = { _, calls -> calls.map { """{"temperature":"сім"}""" } },
            voice = sink,
        )

        val out = rounds.answer(ask)
        sink.done((out as ToolRounds.Outcome.Answer).text, "m1")
        advanceUntilIdle()

        assertEquals(listOf(ACTING, "У Києві сонячно.", "Парасолька не знадобиться."), heard)
    }

    @Test
    fun `a call fenced as json is never read aloud`() = runTest {
        val heard = mutableListOf<String>()
        val sink = speaking(this, heard)
        val fenced = "Гляну.\n```json\n{\"type\":\"tool-call\",\"tool\":\"weather.now\",\"args\":{\"city\":\"Київ\"}}\n```"
        val rounds = ToolRounds(
            pass = streamed(
                sink,
                fenced to listOf(call("weather.now", "w", Route.NIGHTPOST_SERVER)),
                "Ще раз.\n```\n{ \"type\": \"tool-call\", \"tool\": \"weather.now\" }\n```" to listOf(call("weather.now", "w2", Route.NIGHTPOST_SERVER)),
            ),
            run = { _, calls -> calls.map { "Сонячно." } },
            voice = sink,
        )

        val out = rounds.answer(ask)
        sink.done((out as ToolRounds.Outcome.Answer).text, "m1")
        advanceUntilIdle()

        assertFalse("виклик пішов у синтез: $heard", heard.any { "json" in it || "weather" in it || "tool" in it || "{" in it })
        assertEquals(listOf("Гляну.", ACTING, "Ще раз.", "Сонячно."), heard)
    }

    private fun speaking(scope: CoroutineScope, heard: MutableList<String>): SpeakingSink {
        val speaker = IncrementalSpeaker(
            scope = scope,
            openSpan = { null },
            fallbackSpeak = {},
            onEnqueue = { heard += it },
        )
        speaker.open()
        return SpeakingSink(speaker, ACTING, "Код у розмові.").also { it.begin("m1") }
    }

    /** Кожен прохід пише свій текст у голос шматками, як мережа, а тоді віддає підсумок. */
    private fun streamed(sink: VoiceSink, vararg passes: Pair<String, List<AIChunk.ToolCall>>): suspend (AIIntent.Text) -> ToolRounds.Pass {
        var at = 0
        return { _ ->
            val (raw, calls) = passes[minOf(at++, passes.lastIndex)]
            raw.chunked(6).forEach(sink::token)
            val prose = if (calls.isEmpty()) raw else raw.substringBefore("```").substringBefore("{").trim()
            ToolRounds.Pass(prose, calls, null, calls.firstOrNull()?.route ?: Route.NIGHTPOST_SERVER)
        }
    }

    private companion object {
        const val ACTING = "Зараз гляну."
    }
}
