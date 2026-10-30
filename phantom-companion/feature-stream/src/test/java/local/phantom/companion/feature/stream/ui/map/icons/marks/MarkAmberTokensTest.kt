package local.phantom.companion.feature.stream.ui.map.icons.marks

import androidx.compose.ui.graphics.toArgb
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.feature.stream.ui.map.BREATH_AMBER
import local.phantom.companion.feature.stream.ui.map.world.WorldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Вигляд D: бурштин мітки укриття й кола тривоги — токени D, не старий #F2A93B. */
class MarkAmberTokensTest {

    @Test
    fun `shelter, route goal and the alert circle take the D amber`() {
        assertEquals(NightColors.accent.toArgb(), MarkPalette.accent(isDark = true).of(MarkTone.SIGNAL))
        assertEquals(DayColors.accentLine.toArgb(), MarkPalette.accent(isDark = false).of(MarkTone.SIGNAL))
        assertEquals(NightColors.accent.toArgb(), WorldFigures.palette(WorldKind.SHELTER, true, accent = false).of(MarkTone.SIGNAL))
        assertEquals(DayColors.accentLine.toArgb(), MarkPalette.accent(isDark = true).of(MarkTone.PLATE))
        assertEquals(NightColors.accent.toArgb(), BREATH_AMBER)
    }

    @Test
    fun `the amber plate keeps 3 to 1 on the bone plate`() {
        assertTrue(contrast(MarkPalette.AMBER_DAY, MarkPalette.BONE) >= 3.0)
    }

    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val (x, y) = lum(a) to lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
