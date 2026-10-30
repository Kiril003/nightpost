package local.phantom.companion.core.design.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measure rule, without a composition. Edge-to-edge is right on a portrait
 * phone; rotated, the same rule gives a line the eye cannot track back.
 */
class ReadingGutterTest {

    /** Mirrors [readingGutter]; the composable only supplies the width. */
    private fun gutter(screenWidthDp: Int, base: Float): Float {
        val slack = (screenWidthDp - 560) / 2
        return if (slack > base) slack.toFloat() else base
    }

    @Test
    fun `a portrait phone keeps its own gutter`() {
        assertEquals(14f, gutter(412, base = 14f), 0f)
        assertEquals(14f, gutter(360, base = 14f), 0f)
    }

    @Test
    fun `a narrow phone never gets a negative margin`() {
        // 320dp - 560 is negative; the base must win, or the list would be
        // laid out with inverted padding.
        assertTrue(gutter(320, base = 14f) >= 14f)
    }

    @Test
    fun `landscape turns surplus width into margin, not line length`() {
        val g = gutter(892, base = 14f)
        assertEquals(166f, g, 0f)
        val measure = 892 - 2 * g
        assertEquals("the text column must stay at the measure", 560f, measure, 1f)
    }

    @Test
    fun `the measure stops growing on a wide tablet`() {
        val measure = 1280 - 2 * gutter(1280, base = 14f)
        assertEquals(560f, measure, 1f)
    }

    @Test
    fun `the switch happens exactly where the measure is reached`() {
        // 588 = 560 + 2*14: the last width at which the base still applies.
        assertEquals(14f, gutter(588, base = 14f), 0f)
        assertTrue(gutter(600, base = 14f) > 14f)
    }
}
