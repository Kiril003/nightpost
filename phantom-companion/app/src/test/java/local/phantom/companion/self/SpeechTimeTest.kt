package local.phantom.companion.self

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechTimeTest {

    private val kyiv: ZoneId = ZoneId.of("Europe/Kyiv")
    // Thu 2026-10-15 10:00 Europe/Kyiv (UTC+3)
    private val nowMs: Long = d(2026, 10, 15, 10, 0)

    private fun d(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(kyiv).toInstant().toEpochMilli()

    private fun d(month: Int, day: Int, hour: Int, minute: Int): Long =
        d(2026, month, day, hour, minute)

    private fun assertAt(expectedMs: Long, input: String, baseMs: Long = nowMs) {
        val hit = SpeechTime.parse(input, baseMs, kyiv)
        assertNotNull("expected hit for '$input'", hit)
        assertEquals("atMs for '$input'", expectedMs, hit!!.atMs)
    }

    @Test
    fun `parses English relative, clock, weekday and date expressions`() {
        assertAt(nowMs + 20 * 60_000L, "in 20 minutes")
        assertAt(nowMs + 1 * 60_000L, "in 1 minute")
        assertAt(nowMs + 2 * 3_600_000L, "in 2 hours")
        assertAt(nowMs + 3_600_000L, "in an hour")
        assertAt(nowMs + 30 * 60_000L, "in half an hour")
        assertAt(d(10, 15, 18, 30), "at 18:30")
        assertAt(d(10, 16, 9, 0), "at 9")
        assertAt(d(10, 15, 21, 5), "at 9:05 pm")
        assertAt(d(10, 16, 0, 0), "at 12 am")
        assertAt(d(10, 15, 12, 0), "at 12 pm")
        assertAt(d(10, 16, 9, 0), "tomorrow at 9")

        val tomorrow = SpeechTime.parse("tomorrow", nowMs, kyiv)
        assertNotNull(tomorrow)
        assertEquals(d(10, 16, 9, 0), tomorrow!!.atMs)
        assertEquals(Line.WHEN_TOMORROW, tomorrow.whenLine)

        val today = SpeechTime.parse("today", nowMs, kyiv)
        assertNotNull(today)
        assertEquals(d(10, 15, 11, 0), today!!.atMs)
        assertEquals(Line.WHEN_TODAY, today.whenLine)

        assertAt(d(10, 20, 9, 0), "on 20 Oct")
        assertAt(d(10, 20, 9, 0), "on Oct 20")
        assertAt(d(10, 20, 14, 15), "on 20 Oct at 14:15")
        assertAt(d(2027, 10, 10, 9, 0), "on 10 Oct")
        assertAt(d(10, 16, 9, 0), "on friday")
    }

    @Test
    fun `parses Ukrainian relative, clock, weekday and date expressions`() {
        assertAt(nowMs + 20 * 60_000L, "через 20 хвилин")
        assertAt(nowMs + 5 * 60_000L, "через 5 хв")
        assertAt(nowMs + 2 * 3_600_000L, "через 2 години")
        assertAt(nowMs + 3_600_000L, "через годину")
        assertAt(nowMs + 30 * 60_000L, "через пів години")
        assertAt(d(10, 15, 18, 30), "о 18:30")
        assertAt(d(10, 16, 9, 0), "о 9")
        assertAt(d(10, 16, 9, 0), "завтра о 9")
        assertAt(d(10, 20, 9, 0), "20 жовтня")
        assertAt(d(10, 20, 14, 15), "20 жовтня о 14:15")

        val afterTomorrow = SpeechTime.parse("післязавтра", nowMs, kyiv)
        assertNotNull(afterTomorrow)
        assertEquals(d(10, 17, 9, 0), afterTomorrow!!.atMs)
        assertEquals(Line.WHEN_DAY_AFTER_TOMORROW, afterTomorrow.whenLine)

        assertAt(d(10, 16, 9, 0), "у п'ятницю")
    }

    @Test
    fun `handles DST transitions for relative elapsed time, fall-back overlap and spring-forward gap`() {
        val dstFallNight = d(2026, 10, 25, 0, 30)
        assertAt(dstFallNight + 7_200_000L, "in 2 hours", baseMs = dstFallNight)

        val dstFallEve = d(2026, 10, 24, 22, 0)
        val expectedOverlap = LocalDateTime.of(2026, 10, 25, 3, 30).atZone(kyiv).toInstant().toEpochMilli()
        assertAt(expectedOverlap, "tomorrow at 3:30", baseMs = dstFallEve)

        val dstSpringEve = d(2026, 3, 28, 22, 0)
        val expectedGap = LocalDateTime.of(2026, 3, 29, 3, 30).atZone(kyiv).toInstant().toEpochMilli()
        assertAt(expectedGap, "tomorrow at 3:30", baseMs = dstSpringEve)
    }

    @Test
    fun `cuts matched time phrase from remaining text and returns null for invalid inputs`() {
        val hit = SpeechTime.parse("call back in 20 minutes", nowMs, kyiv)
        assertNotNull(hit)
        assertEquals("call back", hit!!.remaining)
        assertEquals(nowMs + 20 * 60_000L, hit.atMs)

        for (invalid in listOf("call mom", "at 25:00", "at 9:75", "in 0 minutes", "in 10 meters")) {
            assertNull("expected null for '$invalid'", SpeechTime.parse(invalid, nowMs, kyiv))
        }
    }
}
