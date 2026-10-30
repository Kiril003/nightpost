package local.phantom.companion.feature.ambient.service

import android.view.Gravity
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3-E1 — pure JVM tests for the Whisper Line overlay layout.
 *
 * The four flag bits the prompt specifies (FLAG_NOT_FOCUSABLE,
 * FLAG_NOT_TOUCHABLE, FLAG_LAYOUT_NO_LIMITS, FLAG_LAYOUT_INSET_DECOR)
 * must all be set; absent any of them and the strip either eats taps,
 * steals keyboard focus, or gets clamped inside the status-bar area.
 * Pinning the bitmask here protects against a future "let's clean up
 * these flags" refactor that loses one.
 *
 * Stripe height is 1.5dp per docs/PRESENCE_AND_CHAT.md §I.2; we
 * verify it never rounds below 1px (a zero-height view is invisible
 * even to the system overlay accountant).
 */
class WhisperLineLayoutSpecTest {

    @Test
    fun `flag bitmask carries all four required overlay flags`() {
        val flags = WhisperLineLayoutSpec.OVERLAY_FLAGS
        assertTrue(
            "FLAG_NOT_FOCUSABLE must be set",
            flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
        )
        assertTrue(
            "FLAG_NOT_TOUCHABLE must be set",
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0,
        )
        assertTrue(
            "FLAG_LAYOUT_NO_LIMITS must be set",
            flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0,
        )
        assertTrue(
            "FLAG_LAYOUT_INSET_DECOR must be set",
            flags and WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR != 0,
        )
    }

    @Test
    fun `gravity anchors the ribbon to the top start edge`() {
        // Top-anchored so the strip lives at the very top of the
        // screen; START rather than CENTER_HORIZONTAL because the
        // ribbon is full-width and gravity-x is irrelevant for a
        // MATCH_PARENT layout — but START is the convention.
        val g = WhisperLineLayoutSpec.OVERLAY_GRAVITY
        assertTrue("TOP gravity bit must be set", g and Gravity.TOP != 0)
        assertTrue("START gravity bit must be set", g and Gravity.START != 0)
        assertEquals("no extra gravity bits", Gravity.TOP or Gravity.START, g)
    }

    @Test
    fun `stripe height never rounds below one pixel`() {
        // Even on impossibly low-density displays the stripe must be
        // visible — coerceAtLeast(1) protects against a 0-height view.
        assertEquals(1, WhisperLineLayoutSpec.stripeHeightPx(density = 0.0001f))
        assertEquals(1, WhisperLineLayoutSpec.stripeHeightPx(density = 0.5f))
        assertEquals(1, WhisperLineLayoutSpec.stripeHeightPx(density = 1.0f))
        // 1.5dp on mdpi (1.0): 1.5 → coerced to 1px (toInt truncates).
        // 1.5dp on hdpi (1.5): 2.25 → 2px.
        assertEquals(2, WhisperLineLayoutSpec.stripeHeightPx(density = 1.5f))
        // 1.5dp on xhdpi (2.0): 3 → 3px.
        assertEquals(3, WhisperLineLayoutSpec.stripeHeightPx(density = 2.0f))
        // 1.5dp on xxhdpi (3.0): 4.5 → 4px.
        assertEquals(4, WhisperLineLayoutSpec.stripeHeightPx(density = 3.0f))
        // 1.5dp on xxxhdpi (4.0): 6px.
        assertEquals(6, WhisperLineLayoutSpec.stripeHeightPx(density = 4.0f))
    }

    @Test
    fun `stripe height is monotonic in density`() {
        var last = WhisperLineLayoutSpec.stripeHeightPx(density = 0.5f)
        for (d in listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f)) {
            val px = WhisperLineLayoutSpec.stripeHeightPx(density = d)
            assertTrue("density $d must yield px >= prior", px >= last)
            last = px
        }
    }

    @Test
    fun `stripe height stays constant across the 1_5dp design contract`() {
        // Anchor: any change to STRIPE_HEIGHT_DP must be deliberate —
        // updating this test is the trip wire that forces the design
        // brief to be re-read. 1.5dp comes from PRESENCE_AND_CHAT.md §I.2.
        assertEquals(1.5f, WhisperLineLayoutSpec.STRIPE_HEIGHT_DP, 0.0001f)
    }

    @Test
    fun `flags differ from FloatingOrb defaults so a copy paste regression is caught`() {
        // Defensive: FloatingOrb uses FLAG_NOT_TOUCH_MODAL, the whisper
        // line uses FLAG_NOT_TOUCHABLE — different semantics. This test
        // is a sanity tripwire for someone who blindly clones one
        // service's flag mask into the other.
        val orbStyle = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        assertNotEquals(orbStyle, WhisperLineLayoutSpec.OVERLAY_FLAGS)
    }
}
