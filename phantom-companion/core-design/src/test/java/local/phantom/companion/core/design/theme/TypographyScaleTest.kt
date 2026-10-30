package local.phantom.companion.core.design.theme

import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ТВІД T4 — the text-size dial's contract:
 *  - scale 1.0 reproduces exactly the values TypographyComplianceTest
 *    pins (the default must never drift the baseline);
 *  - a larger scale multiplies every role's size uniformly;
 *  - out-of-band scales clamp to the legible window;
 *  - letter-spacing (optical tracking) does NOT scale.
 */
class TypographyScaleTest {

    @Test fun `default scale equals the pinned baseline`() {
        val t = phantomTypography(1.0f)
        assertEquals(36.sp, t.displayLarge.fontSize)
        assertEquals(16.sp, t.bodyLarge.fontSize)
        assertEquals(14.sp, t.labelLarge.fontSize) // subhead since the bridge (03.10)
    }

    @Test fun `no-arg overload is exactly scale one`() {
        val a = phantomTypography()
        val b = phantomTypography(1.0f)
        assertEquals(a.displayLarge.fontSize, b.displayLarge.fontSize)
        assertEquals(a.bodyLarge.fontSize, b.bodyLarge.fontSize)
    }

    @Test fun `scaling multiplies every size uniformly`() {
        val t = phantomTypography(1.25f)
        assertEquals((36f * 1.25f).sp, t.displayLarge.fontSize)
        assertEquals((20f * 1.25f).sp, t.headlineLarge.fontSize)
        assertEquals((16f * 1.25f).sp, t.bodyLarge.fontSize)
        assertEquals((14f * 1.25f).sp, t.bodyMedium.fontSize)
        assertEquals((12f * 1.25f).sp, t.labelSmall.fontSize)
    }

    @Test fun `scale clamps to the legible window`() {
        val tiny = phantomTypography(0.2f)
        assertEquals((16f * FONT_SCALE_MIN).sp, tiny.bodyLarge.fontSize)
        val huge = phantomTypography(9f)
        assertEquals((16f * FONT_SCALE_MAX).sp, huge.bodyLarge.fontSize)
    }

    @Test fun `tracking is an optical constant and does not scale`() {
        val t = phantomTypography(FONT_SCALE_MAX)
        assertEquals((-0.5).sp, t.displayLarge.letterSpacing)
        assertEquals(0.01.em, t.labelSmall.letterSpacing)
    }
}
