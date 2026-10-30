package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Підйом точки над аркушем: на південь, на чверть вікна, і ні на йоту без вікна. */
class SheetCameraLiftTest {

    @Test
    fun `метри на dp на z15 і 50 пн ш — близько півтора`() {
        val m = SheetCameraLift.metersPerDp(50.0, 15.0)
        assertTrue("очікували ~1.54 м/dp, отримали $m", m in 1.45..1.65)
    }

    @Test
    fun `центр камери йде на південь на чверть вікна`() {
        // 891dp полотна A52 × 0.25 ≈ 223dp ≈ 342 м на z15 ≈ 0.0031°.
        val lifted = SheetCameraLift.liftedLatitude(50.0, 15.0, 891.0)
        val shift = 50.0 - lifted
        assertTrue("зсув $shift° має бути ~0.003°", shift in 0.0025..0.0036)
    }

    @Test
    fun `без вікна точка лишається на місці`() {
        assertEquals(50.0, SheetCameraLift.liftedLatitude(50.0, 15.0, 0.0), 1e-12)
    }
}
