package local.phantom.companion.core.voice.supertonic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseChunkerTest {

    private val chunker = PhraseChunker()

    @Test
    fun `short utterance stays whole`() {
        assertEquals(listOf("Перевірив маршрут."), chunker.split("Перевірив маршрут."))
    }

    /**
     * The regression that cost the operator 7.8 seconds of silence: a
     * 137-char reply fit inside the old 140-char opener, so it was never
     * split, so nothing could be pipelined and the whole utterance had to
     * be synthesized before a single sample played. A typical assistant
     * reply MUST produce a short opener plus a tail.
     */
    @Test
    fun `a typical reply is split so the opener can play while the rest synthesizes`() {
        val reply = "Привіт. Я Фантом. Перевірив маршрут — найближче укриття " +
            "за двісті метрів, я підсвітив його на мапі. Тримайся подалі " +
            "від відкритих перехресть."
        assertTrue("fixture must be a realistic reply length", reply.length in 120..200)

        val segments = chunker.split(reply)

        assertTrue("a reply of this length must pipeline, not synthesize whole", segments.size >= 2)
        assertTrue(
            "opener must be short enough to be heard almost at once, was ${segments[0].length}",
            segments[0].length <= 60,
        )
    }

    @Test
    fun `blank input yields no segments`() {
        assertEquals(emptyList<String>(), chunker.split("   \n "))
    }

    @Test
    fun `first segment is cut short at a sentence boundary`() {
        val opener = "Тривога в області." // 18 chars — fast first audio
        val rest = "Найближче укриття за двісті метрів, я вже проклав маршрут і підсвітив його на мапі, тримайся подалі від відкритих перехресть. "
        val segments = chunker.split(opener + " " + rest.repeat(2))
        assertTrue(segments.size >= 2)
        assertEquals(opener, segments[0])
    }

    @Test
    fun `segments ramp up so later ones ride behind playback`() {
        val long = ("Слово багато разів, кома, і знову слово багато разів. ").repeat(30)
        val segments = chunker.split(long)
        val ramp = PhraseChunker.DEFAULT_RAMP
        assertTrue("opener stays short", segments[0].length <= ramp[0])
        assertTrue("second segment may grow", segments[1].length <= ramp[1])
        assertTrue("the ramp must actually climb", ramp[0] < ramp[1])
    }

    @Test
    fun `no segment exceeds the model ceiling`() {
        val long = ("Слово багато разів, кома, і знову слово багато разів. ").repeat(30)
        val segments = chunker.split(long)
        assertTrue(segments.all { it.length <= 300 })
        assertTrue(segments.all { it.isNotBlank() })
    }

    @Test
    fun `clause punctuation used when no sentence ender fits`() {
        val long = "перша частина довга, " +
            "друга частина теж досить довга і без крапок узагалі, " +
            "третя частина завершує думку без розділових знаків далі " +
            "і тягнеться поки не перевищить перший ліміт сегмента"
        val segments = chunker.split(long)
        assertTrue(segments.size >= 2)
        assertTrue("cut should land after a comma", segments[0].endsWith(","))
    }

    @Test
    fun `words are never torn`() {
        val long = ("непереривне " + "слово ".repeat(60)).trim()
        val segments = chunker.split(long)
        val rejoined = segments.joinToString(" ")
        assertEquals(long.replace(Regex("\\s+"), " "), rejoined.replace(Regex("\\s+"), " "))
        assertTrue(segments.all { !it.startsWith(" ") && !it.endsWith(" ") })
    }

    @Test
    fun `pathological unbroken token gets a hard cut instead of an infinite loop`() {
        val token = "а".repeat(700)
        val segments = chunker.split(token)
        assertTrue(segments.size >= 3)
        assertEquals(700, segments.sumOf { it.length })
    }
}
