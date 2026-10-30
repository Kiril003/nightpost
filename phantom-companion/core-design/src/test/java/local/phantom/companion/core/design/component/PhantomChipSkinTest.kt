package local.phantom.companion.core.design.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import local.phantom.companion.core.design.theme.DISABLED_INK_ALPHA
import local.phantom.companion.core.design.theme.PhantomElevation
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.design.tokens.PhantomColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three defects the 35 hand-rolled chips shared, pinned so the
 * thirty-sixth cannot bring them back.
 */
class PhantomChipSkinTest {

    // Since the native theme bridge (03.10) the chip reads the v1.2 tokens;
    // the five old palettes are two token sets.
    private val palettes: List<PhantomColors> = listOf(NightColors, DayColors)

    private val PhantomColors.id: String get() = if (isDark) "night" else "day"

    private val tones = ChipTone.entries
    private val emphases = ChipEmphasis.entries

    /** What survives a red filter, greyscale, and a colour-blind operator. */
    private data class Achromatic(val mark: Boolean, val weight: FontWeight, val edge: Dp)

    private fun achromatic(s: ChipSkin) = Achromatic(s.mark, s.weight, s.edgeWidth)

    @Test
    fun `selection is legible with every colour stripped out`() {
        for (theme in palettes) {
            for (tone in tones) {
                for (emphasis in emphases) {
                    val on = chipSkin(theme, tone, emphasis, selected = true, enabled = true)
                    val off = chipSkin(theme, tone, emphasis, selected = false, enabled = true)
                    assertNotEquals(
                        "${theme.id}/$tone/$emphasis: a selected chip and an unselected one " +
                            "differ only by colour. That is invisible at night, under a red " +
                            "filter, and to a colour-blind operator.",
                        achromatic(on),
                        achromatic(off),
                    )
                }
            }
        }
    }

    @Test
    fun `the check mark appears when and only when the chip is selected`() {
        for (theme in palettes) {
            for (tone in tones) {
                for (emphasis in emphases) {
                    assertTrue(
                        "${theme.id}/$tone/$emphasis: selected chip draws no mark",
                        chipSkin(theme, tone, emphasis, selected = true, enabled = true).mark,
                    )
                    assertTrue(
                        "${theme.id}/$tone/$emphasis: unselected chip draws a mark",
                        !chipSkin(theme, tone, emphasis, selected = false, enabled = true).mark,
                    )
                    assertTrue(
                        "${theme.id}/$tone/$emphasis: a chip outside any selection " +
                            "(selected = null) draws a selection mark",
                        !chipSkin(theme, tone, emphasis, selected = null, enabled = true).mark,
                    )
                }
            }
        }
    }

    /**
     * `accent` measures 1.9:1 as text on «День». Three of the chips this
     * primitive replaces coloured their selected label with the fill anyway,
     * and `ToggleChip` used a hardcoded `0xFFF4AF25` that does not move with
     * the theme at all.
     */
    @Test
    fun `the accent fill is never used as the chip's ink`() {
        for (theme in palettes) {
            for (emphasis in listOf(ChipEmphasis.Quiet, ChipEmphasis.Outline)) {
                val skin = chipSkin(theme, ChipTone.Accent, emphasis, selected = true, enabled = true)
                assertEquals(
                    "${theme.id}/$emphasis: accent ink must be accentText, the ink-weight token",
                    theme.accentText,
                    skin.ink,
                )
                assertNotEquals(
                    "${theme.id}/$emphasis: the accent FILL is being read as text",
                    theme.accent,
                    skin.ink,
                )
            }
        }
    }

    @Test
    fun `ink on a filled chip wins its contrast against the fill`() {
        for (theme in palettes) {
            for (tone in tones) {
                val skin = chipSkin(theme, tone, ChipEmphasis.Solid, selected = null, enabled = true)
                assertTrue(
                    "${theme.id}/$tone: solid chip draws its label in its own fill colour",
                    skin.ink != skin.fill,
                )
                assertTrue(
                    "${theme.id}/$tone: solid chip ink is ${contrast(skin.ink, skin.fill)}:1 " +
                        "against its fill — under 4.5:1 it cannot be read",
                    contrast(skin.ink, skin.fill) >= 4.5,
                )
            }
        }
    }

    @Test
    fun `a disabled chip recedes and stops floating`() {
        for (theme in palettes) {
            for (tone in tones) {
                for (emphasis in emphases) {
                    val off = chipSkin(theme, tone, emphasis, selected = true, enabled = false)
                    assertEquals(
                        "${theme.id}/$tone/$emphasis: a disabled chip keeps live ink",
                        theme.text.copy(alpha = DISABLED_INK_ALPHA),
                        off.ink,
                    )
                    assertEquals(
                        "${theme.id}/$tone/$emphasis: a disabled chip is still lifted off " +
                            "the page, which reads as available",
                        PhantomElevation.Flat,
                        off.elevation,
                    )
                }
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return ((hi + 0.05) / (lo + 0.05)).toDouble()
    }
}
