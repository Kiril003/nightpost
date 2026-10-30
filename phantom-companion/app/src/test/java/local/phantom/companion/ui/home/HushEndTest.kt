package local.phantom.companion.ui.home

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HushEndTest {

    private val zone = ZoneId.of("Europe/Kyiv")
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `today shows the clock, another day shows the date, forever shows nothing`() {
        val now = at(29, 10, 0)
        assertEquals("14:30", hushEndText(at(29, 14, 30), now, zone, Locale.ENGLISH))
        assertEquals("30 Oct, 08:05", hushEndText(at(30, 8, 5), now, zone, Locale.ENGLISH))
        assertNull(hushEndText(null, now, zone, Locale.ENGLISH))
    }
}
