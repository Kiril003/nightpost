package local.phantom.companion.core.solana

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDayTest {

    private val zone = ZoneId.of("Europe/Kyiv")
    private val en = Locale.ENGLISH
    private val today = LocalDate.of(2026, 10, 15)
    private val now = today.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()

    private fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).atZone(zone).toEpochSecond()

    @Test fun todayAndYesterdayAreNamedByTheLocalDayNotTwentyFourHours() {
        assertEquals(DayHeader.Today, ActivityDay.header(at(today, 0, 30), now, zone, en))
        assertEquals(DayHeader.Today, ActivityDay.header(at(today, 14, 2), now, zone, en))
        assertEquals(DayHeader.Yesterday, ActivityDay.header(at(today.minusDays(1), 23, 59), now, zone, en))
        assertEquals(DayHeader.Yesterday, ActivityDay.header(at(today.minusDays(1), 0, 1), now, zone, en))
    }

    @Test fun olderDaysCarryTheDateInTheAppLocale() {
        assertEquals(DayHeader.On("11 Oct"), ActivityDay.header(at(today.minusDays(4), 9, 0), now, zone, en))
        val uk = ActivityDay.header(at(today.minusDays(4), 9, 0), now, zone, Locale.forLanguageTag("uk"))
        assertTrue("$uk", uk is DayHeader.On && uk.text.startsWith("11 ") && uk.text != "11 Oct")
        val cs = ActivityDay.header(at(today.minusDays(4), 9, 0), now, zone, Locale.forLanguageTag("cs"))
        assertTrue("$cs", cs is DayHeader.On && cs.text.contains("11") && cs.text != "11 Oct")
    }

    @Test fun timeIsTwentyFourHoursWithLeadingZero() {
        assertEquals("14:02", ActivityDay.time(at(today, 14, 2), zone, en))
        assertEquals("02:05", ActivityDay.time(at(today, 2, 5), zone, en))
        assertEquals("00:00", ActivityDay.time(at(today, 0, 0), zone, Locale.forLanguageTag("uk")))
        assertNotEquals("no AM or PM", "2:05 AM", ActivityDay.time(at(today, 2, 5), zone, en))
    }
}
