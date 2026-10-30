package local.phantom.companion.self

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderSpeechTest {

    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    // Thu 2026-10-15 10:00 Europe/Kyiv (UTC+3)
    private val nowMs: Long = d(2026, 10, 15, 10, 0)

    private fun d(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(kyiv).toInstant().toEpochMilli()

    private fun d(month: Int, day: Int, hour: Int, minute: Int): Long =
        d(2026, month, day, hour, minute)

    private fun assertAsk(expectedText: String, expectedAtMs: Long, input: String) {
        val ask = ReminderSpeech.match(input, nowMs, kyiv)
        assertNotNull("expected match for '$input'", ask)
        assertEquals("text for '$input'", expectedText, ask!!.text)
        assertEquals("atMs for '$input'", expectedAtMs, ask.atMs)
    }

    @Test
    fun `matches English and Ukrainian reminder requests and caps text at 500 chars`() {
        assertAsk("Call back", nowMs + 20 * 60_000L, "Remind me in 20 minutes to call back")
        assertAsk("Call back", nowMs + 20 * 60_000L, "remind me to call back in 20 minutes")
        assertAsk("Send the report", d(10, 16, 9, 0), "Remind me tomorrow at 9 to send the report")
        assertAsk("Buy milk", d(10, 15, 18, 30), "Set a reminder at 18:30 to buy milk")
        assertAsk("Подзвонити мамі", nowMs + 20 * 60_000L, "Нагадай мені через 20 хвилин подзвонити мамі")
        assertAsk("Зустріч", nowMs + 3_600_000L, "Нагадай через годину про зустріч")
        assertAsk("Купити молоко", d(10, 16, 9, 0), "Нагадай завтра о 9 купити молоко")
        assertAsk("Забрати посилку", d(10, 15, 18, 30), "Постав нагадування о 18:30: забрати посилку")
        assertAsk("Зустріч", d(10, 16, 9, 0), "Нагадай мені, що завтра о 9 зустріч")

        val longInput = "Remind me in 20 minutes to " + "a".repeat(600)
        val longAsk = ReminderSpeech.match(longInput, nowMs, kyiv)
        assertNotNull(longAsk)
        assertEquals(500, longAsk!!.text.length)
        assertEquals("A" + "a".repeat(499), longAsk.text)
    }

    @Test
    fun `formats whenLabel in Ukrainian and English and rejects third-person, promises, questions and incomplete asks`() {
        assertEquals(
            "сьогодні 10:20",
            ReminderSpeech.whenLabel(nowMs + 20 * 60_000L, nowMs, kyiv, english = false),
        )
        assertEquals(
            "today 10:20",
            ReminderSpeech.whenLabel(nowMs + 20 * 60_000L, nowMs, kyiv, english = true),
        )

        val rejected = listOf(
            "Нагадай Олі про зустріч",
            "Нагадай Олі завтра о 9 про зустріч",
            "Нагадаю Олі фото завтра",
            "Remind Olia about the photo tomorrow",
            "remind me to call mom",
            "Remind me in 20 minutes",
            "Do I have a reminder at 18:30?",
            "What are my reminders?",
        )
        for (phrase in rejected) {
            assertNull("must not match '$phrase'", ReminderSpeech.match(phrase, nowMs, kyiv))
        }
    }

    @Test
    fun `a sentence-final period or exclamation after the time phrase still matches`() {
        assertAsk("Call back", nowMs + 20 * 60_000L, "Remind me to call back in 20 minutes.")
        assertAsk("Забрати посилку", d(10, 15, 18, 30), "Нагадай мені забрати посилку о 18:30!")
        assertAsk("Call back", d(10, 15, 18, 30), "Remind me at 18:30 to call back.")
    }
}
