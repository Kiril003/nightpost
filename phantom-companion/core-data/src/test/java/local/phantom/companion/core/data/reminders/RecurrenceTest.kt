package local.phantom.companion.core.data.reminders

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }.timeInMillis

    private val day = 86_400_000L

    @Test
    fun `no rule never repeats`() {
        assertNull(Recurrence.next(null, at(2026, 7, 1), at(2026, 7, 1)))
        assertNull(Recurrence.next("", at(2026, 7, 1), at(2026, 7, 1)))
        assertFalse(Recurrence.repeats(null))
    }

    @Test
    fun `daily lands on the next day at the same time`() {
        val start = at(2026, 7, 1)
        val next = Recurrence.next("FREQ=DAILY", start, start, utc)
        assertEquals(at(2026, 7, 2), next)
    }

    @Test
    fun `rrule prefix and lower case are accepted`() {
        val start = at(2026, 7, 1)
        assertEquals(at(2026, 7, 2), Recurrence.next("RRULE:freq=daily", start, start, utc))
    }

    @Test
    fun `interval is honoured`() {
        val start = at(2026, 7, 1)
        assertEquals(at(2026, 7, 4), Recurrence.next("FREQ=DAILY;INTERVAL=3", start, start, utc))
    }

    @Test
    fun `a long-stale reminder skips forward past now, not to the next step`() {
        val start = at(2026, 1, 1)
        val now = at(2026, 7, 1)
        val next = Recurrence.next("FREQ=DAILY", start, now, utc)!!
        assertTrue(next > now)
        assertTrue(next - now <= day)
    }

    @Test
    fun `weekly keeps the weekday`() {
        val start = at(2026, 7, 1)
        val next = Recurrence.next("FREQ=WEEKLY", start, start, utc)!!
        assertEquals(at(2026, 7, 8), next)
    }

    @Test
    fun `monthly and yearly step their own field`() {
        val start = at(2026, 7, 15)
        assertEquals(at(2026, 8, 15), Recurrence.next("FREQ=MONTHLY", start, start, utc))
        assertEquals(at(2027, 7, 15), Recurrence.next("FREQ=YEARLY", start, start, utc))
    }

    @Test
    fun `until stops the series`() {
        val start = at(2026, 7, 1)
        assertNull(Recurrence.next("FREQ=DAILY;UNTIL=20260701T235959Z", start, at(2026, 7, 1, 23), utc))
        assertEquals(
            at(2026, 7, 2),
            Recurrence.next("FREQ=DAILY;UNTIL=20260731T000000Z", start, start, utc),
        )
    }

    @Test
    fun `rules we cannot keep refuse instead of guessing`() {
        val start = at(2026, 7, 1)
        assertNull(Recurrence.next("FREQ=HOURLY", start, start, utc))
        // A BYDAY list does not follow its own start date.
        assertNull(Recurrence.next("FREQ=DAILY;BYDAY=MO,WE,FR", start, start, utc))
        // COUNT would run forever under plain stepping.
        assertNull(Recurrence.next("FREQ=DAILY;COUNT=3", start, start, utc))
        assertFalse(Recurrence.repeats("FREQ=HOURLY"))
    }

    @Test
    fun `a single weekday on a weekly rule is redundant, not unsupported`() {
        val start = at(2026, 7, 1)
        assertEquals(at(2026, 7, 8), Recurrence.next("FREQ=WEEKLY;BYDAY=WE", start, start, utc))
        assertTrue(Recurrence.repeats("FREQ=WEEKLY;INTERVAL=2"))
    }
}
