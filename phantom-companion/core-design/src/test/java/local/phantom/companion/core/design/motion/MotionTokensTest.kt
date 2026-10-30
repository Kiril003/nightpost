package local.phantom.companion.core.design.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W1.5-3 — pin each MotionTokens entry to its DESIGN_BRIEF §VI value.
 *
 * SpringSpec doesn't expose its construction args directly, but its
 * fields are visible — assertions read them via reflection-free
 * property access. Future drift back to Material's default
 * `spring(stiffness = 1500f)` (Spring.StiffnessMedium) gets caught.
 */
class MotionTokensTest {

    @Test fun `snap stiffness is 180`() =
        assertEquals(180f, MotionTokens.SNAP_STIFFNESS)

    @Test fun `snap damping ratio is 0_75`() =
        assertEquals(0.75f, MotionTokens.SNAP_DAMPING_RATIO)

    @Test fun `breathing stiffness is 120`() =
        assertEquals(120f, MotionTokens.BREATHING_STIFFNESS)

    @Test fun `breathing damping ratio is 0_85`() =
        assertEquals(0.85f, MotionTokens.BREATHING_DAMPING_RATIO)

    @Test fun `fadeQuart duration is 600 ms`() =
        assertEquals(600, MotionTokens.FADE_QUART_DURATION_MS)

    @Test fun `snapSpringFloat carries the canonical stiffness and damping`() {
        val spec: SpringSpec<Float> = MotionTokens.snapSpringFloat
        assertEquals(180f, spec.stiffness)
        assertEquals(0.75f, spec.dampingRatio)
    }

    @Test fun `snapSpringOffset carries the canonical stiffness damping and threshold`() {
        val spec: SpringSpec<Offset> = MotionTokens.snapSpringOffset
        assertEquals(180f, spec.stiffness)
        assertEquals(0.75f, spec.dampingRatio)
        // visibilityThreshold field — Offset(0.001, 0.001).
        val threshold = spec.visibilityThreshold
        assertEquals(Offset(0.001f, 0.001f), threshold)
    }

    @Test fun `breathingSpringFloat carries the canonical stiffness and damping`() {
        val spec: SpringSpec<Float> = MotionTokens.breathingSpringFloat
        assertEquals(120f, spec.stiffness)
        assertEquals(0.85f, spec.dampingRatio)
    }

    @Test fun `fadeQuart is 600ms tween with the easeInOutQuart curve`() {
        val spec: TweenSpec<Float> = MotionTokens.fadeQuart
        assertEquals(600, spec.durationMillis)
        // Easing is the same instance constant we exposed; pin it.
        assertEquals(MotionTokens.EaseInOutQuart, spec.easing)
    }

    // ── Μ1 — the Meridian's clocks (PROTEUS §2.2–2.3) ──────────────────

    @Test fun `unfold duration is the spec's 220 ms on the quart curve`() {
        assertEquals(220, MotionTokens.UNFOLD_DURATION_MS)
        val spec: TweenSpec<Float> = MotionTokens.unfoldQuart
        assertEquals(220, spec.durationMillis)
        assertEquals(MotionTokens.EaseInOutQuart, spec.easing)
    }

    @Test fun `the meridian pulses at the presence orb's thinking tempo`() {
        // 1.4s — the same clock LivePresenceHeader thinks at. If one side
        // is retuned, the line and the orb stop reading as one body.
        assertEquals(1_400, MotionTokens.pulseSlow.durationMillis)
    }

    @Test fun `one spark sweep takes 1200 ms`() =
        assertEquals(1_200, MotionTokens.sweep.durationMillis)

    @Test fun `the crease opens in the spec's 180 ms on the quart curve`() {
        assertEquals(180, MotionTokens.CREASE_DURATION_MS)
        assertEquals(180, MotionTokens.creaseQuart.durationMillis)
        assertEquals(MotionTokens.EaseInOutQuart, MotionTokens.creaseQuart.easing)
    }
}
