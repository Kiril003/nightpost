package local.phantom.companion.feature.stream.ui.map.icons

import local.phantom.companion.core.data.poi.PlaceTier
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceTokenSizeTest {

    @Test
    fun `an ordinary place reads at arm's length`() {
        val cut = PlaceToken.cutOf(PlaceTier.ORDINARY, TokenShape.PLATE)
        assertTrue("face ${cut.sizeDp}dp", cut.sizeDp >= 24f)
        assertTrue("glyph ${cut.glyphDp}dp", cut.glyphDp >= 14f)
    }

    @Test
    fun `importance still steps up and every token fits its bitmap`() {
        for (shape in TokenShape.values()) {
            val cuts = listOf(PlaceTier.ORDINARY, PlaceTier.SUSTAIN, PlaceTier.CRITICAL).map { PlaceToken.cutOf(it, shape) }
            for (c in cuts) assertTrue("$shape $c", c.sizeDp + c.sideDp + 3f <= PlaceToken.BITMAP_DP)
            if (shape != TokenShape.SLAB) assertTrue("$shape $cuts", cuts.zipWithNext().all { (a, b) -> b.sizeDp > a.sizeDp })
        }
    }
}
