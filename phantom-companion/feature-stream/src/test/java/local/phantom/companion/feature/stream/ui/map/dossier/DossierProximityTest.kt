package local.phantom.companion.feature.stream.ui.map.dossier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.feature.stream.ui.map.MapText

/** P1 — «відстань · ETA» піку: рахується локально, підписана як оцінка. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class DossierProximityTest {

    @Before
    fun bind() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    /** Хрещатик → Майдан Незалежності, ~350 м по прямій. */
    private val khreshchatyk = 50.44730 to 30.52200
    private val maidan = 50.45010 to 30.52410

    @Test
    fun `гаверсинус дає відстань у метрах`() {
        val meters = haversineMeters(khreshchatyk.first, khreshchatyk.second, maidan.first, maidan.second)
        assertTrue("очікували ~350 м, отримали $meters", meters in 300.0..400.0)
    }

    @Test
    fun `нульова відстань не ламає корінь`() {
        assertEquals(0.0, haversineMeters(50.0, 30.0, 50.0, 30.0), 0.0001)
    }

    @Test
    fun `метри округлюються до десятків, кілометри до десятих`() {
        assertEquals("280 м", formatDistance(283.0))
        assertEquals("1,4 км", formatDistance(1_412.0))
        assertEquals("12 км", formatDistance(12_400.0))
    }

    @Test
    fun `ETA ніколи не нульова`() {
        assertEquals(1, walkEtaMinutes(5.0))
    }

    @Test
    fun `ETA враховує міський обхід`() {
        // 1000 м прямою × 1.3 / 1.35 м-с ≈ 16 хв.
        assertEquals(16, walkEtaMinutes(1_000.0))
    }

    private val words = WalkWords(
        minutes = { "$it m" },
        hours = { "$it h" },
        hoursMinutes = { h, m -> "$h h $m m" },
    )

    @Test
    fun `до години хвилини, до трьох годин години з хвилинами, далі години`() {
        assertEquals("16 m", walkEtaText(16, words))
        assertEquals("1 h 12 m", walkEtaText(72, words))
        assertEquals("2 h", walkEtaText(120, words))
        // «≈ 437 хв пішки» на кадрі 06.09 — це сім годин, а не число для ділення в голові.
        assertEquals("7 h", walkEtaText(437, words))
    }

    @Test
    fun `близькість несе метри й хвилини разом`() {
        val p = proximity(khreshchatyk, maidan.first, maidan.second)!!
        assertTrue("~350 м, отримали ${p.meters}", p.meters in 300.0..400.0)
        assertTrue("~5 хв, отримали ${p.walkMinutes}", p.walkMinutes in 4..6)
    }

    @Test
    fun `без позиції оператора відстань не вигадується`() {
        assertNull(proximity(null, maidan.first, maidan.second))
    }
}
