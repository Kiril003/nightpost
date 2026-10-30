package local.phantom.companion.core.voice.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/** Облік почутого: модель не посилається на те, чого людина не дослухала. */
class HeardLedgerTest {

    private val full = "Перевірив маршрут. Найближче укриття за 200 метрів. Дорога туди відкрита."
    private val mark: (String) -> String = { "$it [перебито]" }

    @Test
    fun `sentences split on their ends`() {
        assertEquals(3, Sentences.split(full).size)
        assertEquals(listOf("Так!", "А далі?", "Добре…", "Кінець"), Sentences.split("Так! А далі? Добре… Кінець"))
    }

    @Test
    fun `only sentences the person heard to the end count`() {
        assertEquals("Перевірив маршрут.", Sentences.heardWhole(full, "Перевірив маршрут. Найближче укриття за"))
        assertEquals("", Sentences.heardWhole(full, "Перевірив мар"))
        // Синтез читає числа словами — рахуємо речення, а не літери.
        assertEquals(
            "Перевірив маршрут. Найближче укриття за 200 метрів.",
            Sentences.heardWhole(full, "Перевірив маршрут. Найближче укриття за двісті метрів."),
        )
    }

    @Test
    fun `the prompt sees the interrupted reply only up to what was heard`() {
        val l = HeardLedger()
        l.interrupted("m1", full, "Перевірив маршрут. Найбли")
        assertEquals("Перевірив маршрут. [перебито]", l.forPrompt("m1", full, mark))
        assertEquals("інше", l.forPrompt("m2", "інше", mark))
    }

    @Test
    fun `a reply heard to the end is not marked as interrupted`() {
        val l = HeardLedger()
        l.interrupted("m1", full, "Перевірив маршрут. Найближче укриття за двісті метрів. Дорога туди відкрита.")
        assertEquals(full, l.forPrompt("m1", full, mark))
    }

    @Test
    fun `the ledger keeps only the recent replies`() {
        val l = HeardLedger(capacity = 2)
        l.interrupted("a", full, "Перевірив маршрут.")
        l.interrupted("b", full, "Перевірив маршрут.")
        l.interrupted("c", full, "Перевірив маршрут.")
        assertEquals(full, l.forPrompt("a", full, mark))
    }
}
