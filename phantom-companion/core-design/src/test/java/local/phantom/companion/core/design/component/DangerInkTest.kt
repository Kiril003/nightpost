package local.phantom.companion.core.design.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destructive confirm of [PhantomDialog] ([DangerButton]) is the one
 * button whose word must be read before it is pressed. It used to be `onAct`
 * in both palettes — by night that is a near-white word on the light coral
 * #FF7D71, 2.2:1 (shot `phantom-dialog-night`, 04.10).
 */
class DangerInkTest {

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun assertReads(name: String, c: PhantomColors) {
        val ratio = contrast(dangerInk(c), c.danger)
        assertTrue("$name: the word on the danger fill reads $ratio:1, needs 4.5:1", ratio >= 4.5f)
    }

    @Test
    fun `the destructive word reads on Night`() = assertReads("Ніч", NightColors)

    @Test
    fun `the destructive word reads on Day`() = assertReads("День", DayColors)

    @Test
    fun `by day the word stays the spec's light ink`() {
        // §7.8 «label #fff» — wherever the light ink does read, it is the one used.
        assertEquals(DayColors.onAct, dangerInk(DayColors))
    }
}
