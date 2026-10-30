package local.phantom.companion.core.tool.impl

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asked for «18:00 today» the model sent an epoch meaning 21:00 local,
 * then — after being handed the UTC offset outright — 20:00. It is bad at
 * this arithmetic and the device is not, so the device does it.
 */
class ResolveLocalTimeTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(y, mo - 1, d, h, min)
        }.timeInMillis

    private fun fieldsOf(ms: Long): List<Int> = Calendar.getInstance().apply { timeInMillis = ms }.let {
        listOf(
            it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH),
            it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE),
        )
    }

    @Test
    fun `HH mm lands at that wall-clock time today`() {
        val now = at(2026, 7, 26, 2, 8)
        assertEquals(listOf(2026, 7, 26, 18, 0), fieldsOf(resolveLocalTime("18:00", now)!!))
    }

    @Test
    fun `a time already past today rolls to tomorrow`() {
        val now = at(2026, 7, 26, 20, 30)
        assertEquals(listOf(2026, 7, 27, 18, 0), fieldsOf(resolveLocalTime("18:00", now)!!))
    }

    @Test
    fun `an explicit date is taken as given`() {
        val now = at(2026, 7, 26, 2, 8)
        assertEquals(listOf(2026, 7, 27, 9, 0), fieldsOf(resolveLocalTime("2026-07-27 09:00", now)!!))
        assertEquals(listOf(2026, 12, 31, 23, 45), fieldsOf(resolveLocalTime("2026-12-31T23:45", now)!!))
    }

    @Test
    fun `the result is always in the future, never silently overdue`() {
        val now = at(2026, 7, 26, 2, 8)
        assertTrue(resolveLocalTime("18:00", now)!! > now)
        assertTrue(resolveLocalTime("01:00", now)!! > now)
    }

    @Test
    fun `nonsense is refused rather than guessed`() {
        val now = at(2026, 7, 26, 2, 8)
        assertNull(resolveLocalTime("завтра ввечері", now))
        assertNull(resolveLocalTime("25:00", now))
        assertNull(resolveLocalTime("18:70", now))
        assertNull(resolveLocalTime("", now))
    }
}
