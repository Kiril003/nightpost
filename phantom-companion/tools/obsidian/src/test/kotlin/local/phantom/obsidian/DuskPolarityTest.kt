package local.phantom.obsidian

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сутінки — настрій, а не друга мапа.
 *
 * Варіант, зроблений «на око», ламається двома способами, і обидва видно лише
 * на склі: земля тепліє разом із сигнальними ролями (тривога в сутінках стає
 * іншою тривогою), або тепло піднімає землю під будівлю і квартал зникає —
 * рівно та поразка 1.19:1, за яку вже платили на пристрої.
 */
class DuskPolarityTest {

    private val night = Palette(Polarity.NIGHT)
    private val dusk = Palette(Polarity.DUSK)

    @Test
    fun `dusk is night, shifted warm — not the day table`() {
        assertEquals(Tokens.color("ph.color.ink", Polarity.NIGHT), dusk.ink)
        assertNotEquals("сутінки взяли денну землю", Tokens.color("ph.color.ground", Polarity.DAY), dusk.ground)
        assertNotEquals("сутінки не відрізняються від ночі", night.ground, dusk.ground)
    }

    /** Ніч холодна (синього більше за червоне), сутінки теплі — навпаки. */
    @Test
    fun `the earth turns warm while night stays cold`() {
        for (role in listOf(night.ground to dusk.ground, night.surface to dusk.surface, night.border to dusk.border)) {
            val (n, d) = role
            assertTrue("нічна роль $n не холодна", blue(n) > red(n))
            assertTrue("сутінкова роль $d не потепліла: $n → $d", red(d) > red(n) && red(d) > blue(d))
        }
    }

    @Test
    fun `the signal roles never drift — an alert is one alert in both moods`() {
        assertEquals(night.ink, dusk.ink)
        assertEquals(night.inkMuted, dusk.inkMuted)
        assertEquals(night.alert, dusk.alert)
        assertEquals(night.danger, dusk.danger)
        assertEquals(night.info, dusk.info)
        assertEquals(night.accent, dusk.accent)
        assertEquals("дороги — світлові жили в обох настроях", night.roadMajor, dusk.roadMajor)
    }

    /**
     * Скрим несе альфу як частину значення. Підмішати в нього непрозорий
     * акцент «просто так» означало б затемнити мапу, а не потеплити.
     */
    @Test
    fun `warming the scrim leaves its opacity alone`() {
        assertEquals(alphaOf(night.scrim), alphaOf(dusk.scrim), 0.001)
        assertNotEquals(night.scrim, dusk.scrim)
    }

    /**
     * Той самий поріг, що й у нічному чорнилі: будівля мусить лишитись видимою
     * над землею. Тепло піднімає обидва боки, тож контраст має вижити.
     */
    @Test
    fun `the building survives the warm ground`() {
        val ink = CartoInk(dusk)
        val ratio = contrast(ink.building, ink.ground)
        assertTrue("будівля в сутінках $ratio:1 проти землі — квартал зникне", ratio >= 1.8)
        assertTrue("будівля переважила дорогу", contrast(ink.roadMotorway, ink.ground) > ratio)
    }

    @Test
    fun `every dusk colour still comes from the tokens`() {
        val used = CartoInk(dusk).all
        assertTrue(used.isNotEmpty())
        assertEquals(
            "сутінки і ніч мусять давати однакову КІЛЬКІСТЬ кольорів — розбіжність значить втрачений шар",
            CartoInk(night).all.size,
            used.size,
        )
    }

    /* ── Арифметика перевірки ────────────────────────────────────────────── */

    private fun red(c: String) = channel(c, 0)
    private fun blue(c: String) = channel(c, 2)

    private fun channel(c: String, i: Int): Int {
        require(c.startsWith("#")) { "очікується #RRGGBB, отримано $c" }
        return c.substring(1 + i * 2, 3 + i * 2).toInt(16)
    }

    private fun luminance(c: String): Double {
        val lin = (0..2).map { i ->
            val v = channel(c, i) / 255.0
            if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    }

    private fun contrast(a: String, b: String): Double {
        val x = luminance(a) + 0.05
        val y = luminance(b) + 0.05
        return if (x > y) x / y else y / x
    }
}
