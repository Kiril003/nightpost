package local.phantom.companion.core.net.backpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackpackTest {

    @Test fun `після тривоги план тайлів разом із маяком і сховком — не більше 2 МБ`() {
        for ((lat, lon) in listOf(50.45 to 30.52, 49.99 to 36.23, 46.48 to 30.73, 51.5 to 31.3)) {
            val p = Backpack.plan(lat, lon, Backpack.Trigger.ALERT)
            assertNotNull(p)
            assertTrue("${p!!.estimateBytes} + запас > 2 МБ", p.estimateBytes + Backpack.RESERVED_BYTES <= Backpack.QUICK_BYTES)
            assertTrue(p.contains(lat, lon))
            assertTrue("мапа без вулиць нічого не дає внизу", p.maxZoom >= 15)
        }
    }

    @Test fun `уночі пакет більший, але теж у своєму бюджеті`() {
        val quick = Backpack.plan(50.45, 30.52, Backpack.Trigger.ALERT)!!
        val night = Backpack.plan(50.45, 30.52, Backpack.Trigger.NIGHT)!!
        assertTrue(night.radiusM > quick.radiusM)
        assertTrue(night.estimateBytes <= Backpack.NIGHT_BYTES)
        assertTrue(night.minZoom < quick.minZoom)
    }

    @Test fun `кількість тайлів рахується як у XYZ`() {
        // Увесь світ на рівні 2 — 16 тайлів.
        assertEquals(16L, Backpack.tileCount(85.0, 179.9, -85.0, -180.0, 2))
        // Рівень 0 — один тайл на весь світ.
        assertEquals(1L, Backpack.tileCount(50.4501, 30.5201, 50.4500, 30.5200, 0))
        // Крихітний прямокутник — не більше двох тайлів по кожній осі, навіть на межі.
        assertTrue(Backpack.tileCount(50.4501, 30.5201, 50.4500, 30.5200, 14) <= 4L)
        // Кожен рівень учетверо: той самий район на z+1 — приблизно вчетверо більше.
        val z14 = Backpack.tileCount(50.50, 30.60, 50.40, 30.40, 14)
        val z15 = Backpack.tileCount(50.50, 30.60, 50.40, 30.40, 15)
        assertTrue(z15 in (3 * z14)..(5 * z14))
    }

    @Test fun `без справжньої позиції плану немає`() {
        assertNull(Backpack.plan(91.0, 30.0, Backpack.Trigger.ALERT))
        assertNull(Backpack.plan(50.0, 181.0, Backpack.Trigger.NIGHT))
    }
}
