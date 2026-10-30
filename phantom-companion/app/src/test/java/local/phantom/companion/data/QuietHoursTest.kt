package local.phantom.companion.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietHoursTest {

    private val night = QuietHours(enabled = true, except = setOf("mom", "oksana"))
    private fun t(h: Int, m: Int) = LocalTime.of(h, m)

    @Test
    fun `межі ночі через північ`() {
        assertFalse(night.hushes("ihor", t(22, 59)))
        assertTrue(night.hushes("ihor", t(23, 0)))
        assertTrue(night.hushes("ihor", t(0, 0)))
        assertTrue(night.hushes("ihor", t(6, 59)))
        assertFalse(night.hushes("ihor", t(7, 0)))
        assertFalse(night.hushes("ihor", t(12, 0)))
    }

    @Test
    fun `денне вікно без переходу через північ`() {
        val day = QuietHours(enabled = true, from = t(13, 0), to = t(15, 0))
        assertFalse(day.covers(t(12, 59)))
        assertTrue(day.covers(t(13, 0)))
        assertTrue(day.covers(t(14, 59)))
        assertFalse(day.covers(t(15, 0)))
        assertFalse(day.covers(t(2, 0)))
    }

    @Test
    fun `люди з винятків дзвонять і вночі`() {
        assertFalse(night.hushes("mom", t(2, 0)))
        assertFalse(night.hushes("oksana", t(23, 30)))
        assertTrue(night.hushes("ihor", t(2, 0)))
    }

    @Test
    fun `вимкнена тиша не глушить нікого`() {
        val off = night.copy(enabled = false)
        assertFalse(off.hushes("ihor", t(2, 0)))
        assertFalse(off.hushes("ihor", t(23, 0)))
    }

    @Test
    fun `однакові межі — порожнє вікно, а не цілодобова тиша`() {
        assertFalse(night.copy(from = t(7, 0)).covers(t(7, 0)))
    }

    @Test
    fun `ніч рахується в поясі телефона, а не в UTC`() {
        val at = Instant.parse("2026-09-26T21:30:00Z")
        assertTrue(night.hushes("ihor", at, ZoneId.of("Europe/Kyiv")))
        assertFalse(night.hushes("ihor", at, ZoneId.of("UTC")))
    }

    @Test
    fun `час читається й пишеться як 23-00`() {
        assertEquals(t(7, 0), QuietHours.parse("7:00"))
        assertEquals(t(23, 0), QuietHours.parse("23:00"))
        assertNull(QuietHours.parse("24:00"))
        assertNull(QuietHours.parse("23:0"))
        assertNull(QuietHours.parse("вночі"))
        assertEquals("07:00", QuietHours.format(t(7, 0)))
    }
}
