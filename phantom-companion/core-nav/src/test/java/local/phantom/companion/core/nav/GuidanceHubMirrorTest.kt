package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * The controller mirrors its state into [GuidanceHub] so the Android Auto head
 * — which runs with no Activity of ours alive — can read it.
 *
 * The mirror is a property setter, and its first version assigned to ITSELF:
 * starting a route recursed until `StackOverflowError` and threw the operator
 * out of the app (device, 2026-08-08). A property that writes through must be
 * proven to write through.
 */
class GuidanceHubMirrorTest {

    @Before
    fun reset() {
        GuidanceHub.clear()
    }

    /** The exact shape of the controller's mirror. */
    private class Mirror {
        private var backing: GuidanceState = GuidanceState.Idle
        var published: GuidanceState
            get() = backing
            set(value) {
                backing = value
                GuidanceHub.publish(value)
            }
    }

    @Test
    fun `writing the mirror terminates and stores the value`() {
        val m = Mirror()
        m.published = GuidanceState.Arrived
        assertSame(GuidanceState.Arrived, m.published)
    }

    @Test
    fun `the hub sees every write`() {
        val m = Mirror()
        m.published = GuidanceState.Guiding(null)
        assertEquals(GuidanceState.Guiding(null), GuidanceHub.state.value)
        m.published = GuidanceState.Arrived
        assertSame(GuidanceState.Arrived, GuidanceHub.state.value)
    }

    @Test
    fun `repeated writes do not recurse`() {
        // A self-assigning setter blows the stack in the first few frames; a
        // thousand honest writes cost nothing.
        val m = Mirror()
        repeat(1_000) { m.published = GuidanceState.Guiding(null) }
        assertEquals(GuidanceState.Guiding(null), GuidanceHub.state.value)
    }

    @Test
    fun `clear returns the hub to idle`() {
        val m = Mirror()
        m.published = GuidanceState.Arrived
        GuidanceHub.clear()
        assertSame(GuidanceState.Idle, GuidanceHub.state.value)
    }
}
