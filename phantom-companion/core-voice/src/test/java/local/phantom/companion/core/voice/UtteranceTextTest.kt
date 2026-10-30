package local.phantom.companion.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Що бачить людина, поки говорить, і що отримує модель, коли вона змовкла.
 *
 * Розпізнавач закриває сегмент на півсекунді тиші всередині фрази. Усе, що
 * тут перевіряється, — що ця пауза не стирає сказане до неї.
 */
class UtteranceTextTest {

    @Test
    fun `a pause inside the phrase does not erase what came before it`() {
        val u = UtteranceText()

        // Перший сегмент дослухано й закрито — Vosk почав партіал з нуля.
        u.commit("перевір маршрут")

        assertEquals(
            "після паузи на екрані має лишитись уся фраза, а не її хвіст",
            "перевір маршрут до харкова",
            u.withPending("до харкова"),
        )
    }

    @Test
    fun `the moment a segment closes the phrase is still whole`() {
        val u = UtteranceText()
        u.commit("передай на трьохсотого")

        // Vosk щойно закрився: partial порожній, репліка — ні.
        assertEquals("передай на трьохсотого", u.withPending(""))
    }

    @Test
    fun `closing the utterance joins every segment with the open tail`() {
        val u = UtteranceText()
        u.commit("перший")
        u.commit("другий")

        assertEquals("перший другий хвіст", u.drain("хвіст"))
    }

    @Test
    fun `a drained utterance does not leak into the next one`() {
        val u = UtteranceText()
        u.commit("попередня репліка")
        u.drain("кінець")

        assertEquals(
            "нова репліка не має починатися зі слів попередньої",
            "нова",
            u.withPending("нова"),
        )
    }

    @Test
    fun `whitespace never becomes a word`() {
        val u = UtteranceText()
        u.commit("   ")
        u.commit(" слово ")

        assertEquals("слово", u.withPending("   "))
        assertEquals("слово", u.drain(""))
    }
}
