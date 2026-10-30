package local.phantom.companion.ui.messenger

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.Route
import local.phantom.companion.ui.messenger.WebMessengerAssistantLive.Flush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A5: the answer grows in the Nightpost chat as it streams; a failure lands on the question, never as an answer. */
class AssistantLiveStreamTest {

    private val chat = AgencySessionManager.PHANTOM_AGENT_ID

    private fun tok(text: String) = AIChunk.Token(text, Route.NIGHTPOST_SERVER)

    private class Seen(val flushes: MutableList<Flush> = mutableListOf(), val atMs: MutableList<Long> = mutableListOf())

    private fun TestScope.watch(board: AssistantLiveBoard): Seen {
        val seen = Seen()
        backgroundScope.launch {
            WebMessengerAssistantLive.flushes(board.feed, now = { testScheduler.currentTime }).collect {
                seen.flushes += it
                seen.atMs += testScheduler.currentTime
            }
        }
        runCurrent()
        return seen
    }

    private fun TestScope.board() = AssistantLiveBoard(now = { testScheduler.currentTime })

    // advanceUntilIdle stops once no foreground task is left, so the page collector in backgroundScope never ran.
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(5 * WebMessengerAssistantLive.FLUSH_MS)
        runCurrent()
    }

    @Test
    fun `a three-chunk reply reaches the web as growing text and ends as one done`() = runTest {
        val board = board()
        val seen = watch(board)
        board.begin(chat, "m1", questionId = "q1")
        val fold = board.stream("m1") {
            flow {
                delay(400)
                emit(tok("Prague "))
                delay(250)
                emit(tok("is the "))
                delay(250)
                emit(tok("capital."))
            }
        }
        runCurrent()
        board.end("m1")
        settle()

        val f = seen.flushes
        assertEquals(f.toString(), "thinking", f.first().phase)
        val writes = f.filter { it.phase == "writing" }
        assertTrue("live token events: $f", writes.size >= 2)
        writes.zipWithNext().forEach { (a, b) ->
            assertTrue("text must grow: $writes", b.text.startsWith(a.text) && b.text.length > a.text.length)
        }
        assertEquals("Prague", writes.first().text)
        val last = f.last()
        assertEquals("done", last.phase)
        assertEquals("m1", last.messageId)
        assertEquals("Prague is the capital.", last.text)
        assertEquals("Prague is the capital.", fold.text)
        assertTrue(f.all { it.messageId == "m1" })
    }

    @Test
    fun `a reply whose end lands right after its last words still reaches the page whole`() = runTest {
        val answer = "Prague is the capital."
        val streamed = board()
        val seenStreamed = watch(streamed)
        streamed.begin(chat, "m4", questionId = "q4")
        streamed.stream("m4") {
            flow {
                delay(400)
                emit(tok("Prague "))
                delay(10)
                emit(tok("is the capital."))
            }
        }
        streamed.end("m4")

        val whole = board()
        val seenWhole = watch(whole)
        whole.begin(chat, "m5", questionId = "q5")
        whole.stream("m5") {
            flow {
                delay(300)
                emit(AIChunk.Final(answer, tokensUsed = 5, route = Route.NIGHTPOST_SERVER, durationMs = 300))
            }
        }
        whole.end("m5")
        settle()

        for (f in listOf(seenStreamed.flushes, seenWhole.flushes)) {
            assertEquals("$f", "done", f.last().phase)
            assertEquals("$f", answer, f.last().text)
        }
    }

    @Test
    fun `a fast stream reaches the page at least every 300 ms without a flush per token`() = runTest {
        val board = board()
        val seen = watch(board)
        board.begin(chat, "m3", questionId = "q3")
        board.stream("m3") {
            flow {
                delay(100)
                repeat(40) {
                    emit(tok("w$it "))
                    delay(15)
                }
            }
        }
        runCurrent()
        board.end("m3")
        settle()

        val writes = seen.flushes.indices.filter { seen.flushes[it].phase == "writing" }
        assertTrue("${seen.flushes}", writes.size >= 2)
        val times = writes.map { seen.atMs[it] }
        times.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a} ms between flushes: $times", b - a <= 300) }
        assertTrue("a flush per token floods the page: ${writes.size}", writes.size <= 600 / 200 + 2)
        assertEquals((0 until 40).joinToString(" ") { "w$it" }, seen.flushes.last().text)
    }

    @Test
    fun `a failure after partial words marks the question and never becomes an answer`() = runTest {
        val board = board()
        val seen = watch(board)
        board.begin(chat, "m2", questionId = "q2")
        val fold = board.stream("m2") {
            flow {
                delay(300)
                emit(tok("Par"))
                delay(300)
                emit(AIChunk.ErrorChunk(AIError.NoNetwork(), route = Route.NIGHTPOST_SERVER))
            }
        }
        assertNotNull(fold.failure)
        board.fail("m2", "No network")
        settle()

        val last = seen.flushes.last()
        assertEquals("failed", last.phase)
        assertEquals("q2", last.questionId)
        assertEquals("m2", last.messageId)
        assertEquals("No network", last.reason)
        assertEquals("Par", last.text)
        assertTrue("a failed turn emitted an answer: ${seen.flushes}", seen.flushes.none { it.phase == "done" })
    }

    @Test
    fun `a group reply is not streamed into the Nightpost chat`() = runTest {
        val board = board()
        val seen = watch(board)
        board.begin("group_1", "g1")
        board.push("g1", AgencySessionManager.Phase.WRITING, "hi all")
        runCurrent()
        board.end("g1")
        settle()
        assertEquals(emptyList<Flush>(), seen.flushes)
    }

    @Test
    fun `the payload is the JSON the web door reads`() {
        val j = Json.parseToJsonElement(
            WebMessengerAssistantLive.json(Flush("m9", "failed", "Par\"t", questionId = "q9", reason = "No network")),
        ).jsonObject
        assertEquals(chat, j["chat_id"]?.jsonPrimitive?.content)
        assertEquals("m9", j["message_id"]?.jsonPrimitive?.content)
        assertEquals("failed", j["phase"]?.jsonPrimitive?.content)
        assertEquals("Par\"t", j["text"]?.jsonPrimitive?.content)
        assertEquals("q9", j["question_id"]?.jsonPrimitive?.content)
        assertEquals("No network", j["reason"]?.jsonPrimitive?.content)
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("repo root not found")

    private fun src(rel: String) = File(root, "app/src/main/java/local/phantom/companion/$rel").readText()

    @Test
    fun `the live answer is reachable from the chat send to the page`() {
        val agency = src("ui/messenger/AgencySessionManager.kt")
        val dispatch = agency.substringAfter("fun dispatchPhantomDialogue(").substringBefore("private suspend fun stream(")
        assertTrue("the turn does not name its question", "beginLive(PHANTOM_AGENT_ID, messageId, questionId)" in dispatch)
        val stream = agency.substringAfter("private suspend fun stream(").substringBefore("private suspend fun runCall(")
        assertTrue("the model pass does not grow the live line", "board.stream(messageId" in stream)
        assertTrue("live state is not the board's", "val live: StateFlow<Live?> = board.live" in agency)
        assertTrue("the page reads a conflated state", "val liveFeed: SharedFlow<Live?> = board.feed" in agency)

        val host = src("ui/messenger/WebMessengerHost.kt")
        val push = host.substringAfter("WebMessengerAssistantLive.flushes(container.agencyManager.liveFeed)").substringBefore("LaunchedEffect(")
        assertTrue("the host never pushes live words to the page", "window.__phantomAssistantLive && window.__phantomAssistantLive(" in push)
        assertTrue("the probe cannot see what reached the page", "WebMessengerAssistantLive.shown.value = " in push)
    }
}
