package local.phantom.companion.ui.messenger

import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.Route
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Модель дописує до відповіді службовий рядок ⟦NEXT⟧ з трьома підказками (SystemPrompts);
 * людина його не бачить ні в рядку, що росте, ні в збереженому листі. Знімається в одному місці,
 * [SpokenFold], бо звідси йдуть і живий рядок (`shown`), і лист у журнал (`text`).
 */
class SpokenFoldTrailerTest {

    private fun token(s: String) = AIChunk.Token(s, Route.CLOUD_GEMINI)

    @Test
    fun `the final reply carries no trailer`() {
        val fold = SpokenFold()
        fold.feed(token("Sunsets are red because of scattering.\n⟦NEXT⟧ why are sunsets red | why not violet | show the forecast"))
        assertEquals("Sunsets are red because of scattering.", fold.text)
        assertEquals("Sunsets are red because of scattering.", fold.shown)
    }

    @Test
    fun `the live row never shows a half-arrived marker`() {
        val fold = SpokenFold()
        val seen = mutableListOf<String>()
        listOf("Sunsets are red.", "\n", "⟦", "NE", "XT", "⟧ why ", "not violet | x").forEach {
            fold.feed(token(it))
            seen += fold.shown
        }
        assertEquals(listOf("Sunsets are red.").distinct(), seen.distinct())
        assertFalse(seen.any { '⟦' in it || "NEXT" in it })
    }

    @Test
    fun `a final chunk is cleaned the same way, with or without tool calls`() {
        val plain = SpokenFold()
        plain.feed(AIChunk.Final("Готово.\n⟦NEXT⟧ ще | інше | далі", 3, Route.CLOUD_GEMINI, 1))
        assertEquals("Готово.", plain.text)

        val withCall = SpokenFold()
        withCall.feed(AIChunk.ToolCall("weather.now", "{}", "c1", Route.CLOUD_GEMINI))
        withCall.feed(AIChunk.Final("Гляну.\n⟦NEXT⟧ а | б | в", 3, Route.CLOUD_GEMINI, 1))
        assertEquals("Гляну.", withCall.text)
    }

    @Test
    fun `the memory sources line that BrainChain appends after the trailer survives`() {
        val fold = SpokenFold()
        fold.feed(AIChunk.Final("Ти пив каву зранку.\n⟦NEXT⟧ що ще | де кава | нагадай\n\nЗ памʼяті: «кава»", 3, Route.CLOUD_GEMINI, 1))
        assertEquals("Ти пив каву зранку.\n\nЗ памʼяті: «кава»", fold.text)
    }

    @Test
    fun `a cut marker followed by white space or a call is still not words`() {
        val cut = SpokenFold()
        cut.feed(AIChunk.Final("Готово.\n⟦NE\n", 3, Route.CLOUD_GEMINI, 1))
        assertEquals("Готово.", cut.text)

        val beforeCall = SpokenFold()
        beforeCall.feed(token("Ось.\n⟦NE\n```json\n{\"type\":\"map\"}"))
        assertEquals("Ось.", beforeCall.shown)
    }

    @Test
    fun `every word the chat gets passes through the fold`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "feature-stream").isDirectory }
            ?: error("не знайшла кореня сховища")
        val agency = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/AgencySessionManager.kt").readText()
        val stream = agency.substringAfter("private suspend fun stream(").substringBefore("/** Один виклик моделі")
        val board = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/AssistantLiveBoard.kt").readText()
            .substringAfter("suspend fun stream(")
        assertTrue("живий рядок іде не з fold.shown", "push(messageId, Phase.WRITING, fold.shown)" in board)
        assertTrue("готовий текст іде не з fold.text", "board.stream(messageId" in stream && "ToolRounds.Pass(text = fold.text" in stream)
        val dialogue = agency.substringAfter("fun dispatchPhantomDialogue(").substringBefore("private suspend fun stream(")
        assertTrue("лист у журнал іде не з результату проходу", "body = outcome.text," in dialogue && "stream(turn, messageId, voice)" in dialogue)
    }

    @Test
    fun `a bracket that is not the marker stays in the words`() {
        val fold = SpokenFold()
        fold.feed(token("Масив ⟦a, b⟧ у математиці та ⟦NEX у тексті."))
        assertEquals("Масив ⟦a, b⟧ у математиці та ⟦NEX у тексті.", fold.text)
    }
}
