package local.phantom.companion.core.net.map

import local.phantom.companion.core.net.map.OpeningHours.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningHoursTest {

    @Test
    fun `24-7 is always open with no closing time`() {
        val s = OpeningHours.status("24/7", dayIso = 3, minuteOfDay = 3 * 60)
        assertEquals(Status.Open(untilMinuteOfDay = null), s)
    }

    @Test
    fun `simple weekday range open and closed`() {
        val raw = "Mo-Fr 08:00-20:00"
        assertEquals(
            Status.Open(untilMinuteOfDay = 20 * 60),
            OpeningHours.status(raw, dayIso = 1, minuteOfDay = 12 * 60),
        )
        // Saturday — closed all day, no opening time today.
        assertEquals(
            Status.Closed(opensAtMinuteOfDay = null),
            OpeningHours.status(raw, dayIso = 6, minuteOfDay = 12 * 60),
        )
        // Monday before opening — closed but opens at 08:00.
        assertEquals(
            Status.Closed(opensAtMinuteOfDay = 8 * 60),
            OpeningHours.status(raw, dayIso = 1, minuteOfDay = 7 * 60),
        )
    }

    @Test
    fun `lunch break splits the day`() {
        val raw = "Mo-Su 08:00-13:00,14:00-20:00"
        assertTrue(OpeningHours.status(raw, 2, 12 * 60) is Status.Open)
        assertEquals(
            Status.Closed(opensAtMinuteOfDay = 14 * 60),
            OpeningHours.status(raw, 2, 13 * 60 + 30),
        )
        assertTrue(OpeningHours.status(raw, 2, 15 * 60) is Status.Open)
    }

    @Test
    fun `later off rule overrides earlier day range`() {
        val raw = "Mo-Su 09:00-21:00; Su off"
        assertTrue(OpeningHours.status(raw, 3, 12 * 60) is Status.Open)
        assertEquals(
            Status.Closed(opensAtMinuteOfDay = null),
            OpeningHours.status(raw, 7, 12 * 60),
        )
    }

    @Test
    fun `overnight range spans midnight`() {
        val raw = "Mo-Su 22:00-06:00"
        // 23:00 — inside the pre-midnight half.
        assertTrue(OpeningHours.status(raw, 5, 23 * 60) is Status.Open)
        // 03:00 — carried over from yesterday's span.
        val early = OpeningHours.status(raw, 6, 3 * 60)
        assertEquals(Status.Open(untilMinuteOfDay = 6 * 60), early)
        // 12:00 — closed between the halves.
        assertTrue(OpeningHours.status(raw, 6, 12 * 60) is Status.Closed)
    }

    @Test
    fun `day list and no-day-prefix rules`() {
        assertTrue(OpeningHours.status("Sa,Su 10:00-18:00", 7, 11 * 60) is Status.Open)
        assertTrue(OpeningHours.status("Sa,Su 10:00-18:00", 2, 11 * 60) is Status.Closed)
        // No day prefix = every day.
        assertTrue(OpeningHours.status("09:00-18:00", 4, 10 * 60) is Status.Open)
    }

    @Test
    fun `unparseable syntax is Unknown not a guess`() {
        assertEquals(Status.Unknown, OpeningHours.status("Mo-Fr sunrise-sunset", 1, 12 * 60))
        assertEquals(Status.Unknown, OpeningHours.status("PH off", 1, 12 * 60))
        assertEquals(Status.Unknown, OpeningHours.status("Jan-Mar 08:00-17:00", 1, 12 * 60))
        assertEquals(Status.Unknown, OpeningHours.status(null, 1, 12 * 60))
        assertEquals(Status.Unknown, OpeningHours.status("  ", 1, 12 * 60))
    }

    @Test
    fun `wrapping day range Sa-Mo`() {
        val raw = "Sa-Mo 10:00-16:00"
        assertTrue(OpeningHours.status(raw, 7, 12 * 60) is Status.Open)  // Sunday
        assertTrue(OpeningHours.status(raw, 1, 12 * 60) is Status.Open)  // Monday
        assertTrue(OpeningHours.status(raw, 3, 12 * 60) is Status.Closed) // Wednesday
    }

    @Test
    fun `midnight end 00-00 reads as end of day`() {
        val raw = "Mo-Su 10:00-00:00"
        assertEquals(
            Status.Open(untilMinuteOfDay = 24 * 60),
            OpeningHours.status(raw, 2, 23 * 60),
        )
    }

    @Test
    fun `fmtMinute renders wall clock`() {
        assertEquals("08:05", OpeningHours.fmtMinute(8 * 60 + 5))
        assertEquals("00:00", OpeningHours.fmtMinute(24 * 60))
    }
}
