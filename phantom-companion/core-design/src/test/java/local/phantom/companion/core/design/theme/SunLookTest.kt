package local.phantom.companion.core.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomAccents
import local.phantom.companion.core.design.tokens.PhantomColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сонце, варіант B (власник 28.10): шість точок і вікна з tokens.json — ті самі,
 * що в `rampAt` вебу (themeRamp.test.ts звіряє ті самі краї й рядки моста).
 */
class SunLookTest {

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `phase to colour map is the owner's variant B`() {
        val canvas = mapOf(
            DayPhase.Night to "#111214",
            DayPhase.Dawn to "#F7E3D8",
            DayPhase.Morning to "#EDF0EA",
            DayPhase.Day to "#F6F1E7",
            DayPhase.Golden to "#F3DEB6",
            DayPhase.Dusk to "#22150F",
        )
        for (p in DayPhase.entries) {
            assertEquals(p.name, canvas.getValue(p), hex(p.colors().canvas))
            assertEquals(p.name, p.dark, p.colors().isDark)
            assertEquals(p.name, p.dark, resolveLivingTheme(p).isDark)
            assertEquals(p.name, p, resolveLivingTheme(p).dayPhase())
        }
        assertSame(NightColors, DayPhase.Night.colors())
        assertSame(DayColors, DayPhase.Day.colors())
        assertEquals(listOf("night", "dawn", "morning", "day", "golden", "dusk"), DayPhase.entries.map { it.web })
    }

    @Test
    fun `rising edges - night under -8, flip at -3, dawn to +10, noon from +25`() {
        assertEquals(SunLook.flat(DayPhase.Night), SunLook.at(-8.01, rising = true))
        assertEquals("night:dusk:0.500", SunLook.at(-5.5, rising = true).web)
        assertTrue(SunLook.at(-3.01, rising = true).dark)
        assertEquals(SunLook.flat(DayPhase.Dawn), SunLook.at(-3.0, rising = true))
        assertEquals(SunLook.flat(DayPhase.Dawn), SunLook.at(10.0, rising = true))
        assertEquals("dawn:morning:0.500", SunLook.at(12.0, rising = true).web)
        assertEquals(DayPhase.Morning, SunLook.at(18.0, rising = true).phase)
        assertFalse(SunLook.at(24.99, rising = true).isPole)
        assertTrue(SunLook.at(25.0, rising = true).isPole)
        assertEquals(SunLook.flat(DayPhase.Night), SunLook.at(Double.NaN, rising = true))
    }

    @Test
    fun `setting edges - golden from +12, flip at 0, dusk to -8, night under -14`() {
        assertTrue(SunLook.at(12.0, rising = false).isPole)
        assertEquals("golden:day:0.500", SunLook.at(6.0, rising = false).web)
        assertEquals(SunLook.flat(DayPhase.Golden), SunLook.at(0.0, rising = false))
        assertEquals(SunLook.flat(DayPhase.Dusk), SunLook.at(-0.01, rising = false))
        assertEquals(SunLook.flat(DayPhase.Dusk), SunLook.at(-8.0, rising = false))
        assertEquals("night:dusk:0.500", SunLook.at(-11.0, rising = false).web)
        assertEquals(SunLook.flat(DayPhase.Night), SunLook.at(-14.0, rising = false))
    }

    @Test
    fun `winter noon is reached 5 degrees under the day's peak`() {
        assertTrue(SunLook.at(11.5, rising = true, peakDeg = 16.5).isPole)
        assertTrue(SunLook.at(11.5, rising = false, peakDeg = 16.5).isPole)
        assertFalse(SunLook.at(11.5, rising = true, peakDeg = 60.0).isPole)
        assertFalse(SunLook.at(-3.0, rising = true, peakDeg = 16.5).dark)
    }

    @Test
    fun `the shell spec carries the stop and the bridge string`() {
        val look = SunLook.at(12.0, rising = true)
        val spec = resolveLivingTheme(look)
        assertEquals(look.colors, spec.palette)
        assertEquals(look, spec.sun)
        assertEquals("#F2EAE1", hex(spec.surfaceBase))
        assertEquals(DayPhase.Morning, spec.dayPhase())
        assertNull(resolveTheme(ThemeMode.Light, false, look).palette)
        assertNull(resolveTheme(ThemeMode.Dark, false, look).sun)
    }

    @Test
    fun `the palette's own amber keeps the stop's accent ink, a chosen accent swaps it`() {
        val dawn = DayPhase.Dawn.colors()
        assertEquals(dawn, dawn.withAccent(PhantomAccents.first().id))
        val other = PhantomAccents[1]
        assertEquals(other.day.accentText, dawn.withAccent(other.id).accentText)
    }

    @Test
    fun `AA holds at every stop and along both sides of the sun`() {
        fun check(where: String, c: PhantomColors) {
            for ((fg, bg) in listOf(
                c.text to c.canvas, c.text2 to c.canvas, c.text3 to c.canvas, c.text3 to c.surface,
                c.text3 to c.raised, c.accentText to c.canvas, c.onAccent to c.accent, c.onSelf2 to c.self,
            )) assertTrue("$where %.2f".format(ratio(fg, bg)), ratio(fg, bg) >= 4.5)
        }
        DayPhase.entries.forEach { check(it.name, it.colors()) }
        for (rising in listOf(true, false)) {
            var e = -16.0
            while (e <= 30.0) {
                check("$e° rising=$rising", SunLook.at(e, rising).colors)
                e += 0.5
            }
        }
    }
}
