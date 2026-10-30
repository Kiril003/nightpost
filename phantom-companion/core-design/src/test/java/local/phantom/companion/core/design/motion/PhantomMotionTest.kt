package local.phantom.companion.core.design.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Μ0 — pins the Metamorphosis motion grammar the way [MotionTokensTest]
 * pins the mint. The dolly's geometry is a contract: Back must be the
 * exact reverse of forward, or «returning» quietly degrades into
 * «re-opening» and nobody can say when it happened.
 */
class PhantomMotionTest {

    @Test
    fun `the chamber dolly scales are pinned`() {
        assertEquals(0.96f, PhantomMotion.CHAMBER_FAR_SCALE, 0f)
        assertEquals(1.04f, PhantomMotion.CHAMBER_NEAR_SCALE, 0f)
    }

    @Test
    fun `the dolly is symmetric around rest`() {
        // Incoming rises exactly as far as outgoing recedes. If someone
        // retunes one side, the pop leg stops being the reverse of the
        // forward leg and the depth illusion breaks.
        assertEquals(
            "far and near must sit symmetric around 1.0",
            1f - PhantomMotion.CHAMBER_FAR_SCALE,
            PhantomMotion.CHAMBER_NEAR_SCALE - 1f,
            0.0001f,
        )
    }

    @Test
    fun `the bloom scale is pinned`() {
        // Formalised from the values ConstellationDestination shipped with;
        // 1.3 is the Constellation's felt size, not a free parameter.
        assertEquals(1.3f, PhantomMotion.BLOOM_SCALE, 0f)
    }

    @Test
    fun `the camera rack is the canonical snap, not a fourth spring`() {
        // rackSpring is a *name*, not new physics. The day it stops being
        // the mint's snap spring is the day the continuum camera moves
        // differently from everything else that snaps — that needs an
        // argument in review, not a drive-by constant.
        assertSame(MotionTokens.snapSpringFloat, PhantomMotion.rackSpring)
    }
}
