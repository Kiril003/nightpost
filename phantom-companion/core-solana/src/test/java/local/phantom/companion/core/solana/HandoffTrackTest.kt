package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffTrackTest {

    private fun run(start: NearbyState?, nearbyNow: Boolean, vararg states: NearbyState): HandoffPhase =
        states.fold(HandoffTrack.start(start, nearbyNow)) { t, s -> t.next(s) }.phase

    @Test
    fun `пошук, а потім квитанція — передано`() {
        assertEquals(HandoffPhase.SIGNED, run(NearbyState.IDLE, true))
        assertEquals(HandoffPhase.SEARCHING, run(NearbyState.IDLE, true, NearbyState.SEARCHING))
        assertEquals(HandoffPhase.HANDED, run(NearbyState.IDLE, true, NearbyState.SEARCHING, NearbyState.HANDED))
    }

    @Test
    fun `«передано» від минулої купюри не зараховується цій`() {
        assertEquals(HandoffPhase.SIGNED, run(NearbyState.HANDED, true))
        assertEquals(HandoffPhase.SIGNED, run(NearbyState.HANDED, true, NearbyState.HANDED))
        assertEquals(HandoffPhase.HANDED, run(NearbyState.HANDED, true, NearbyState.SEARCHING, NearbyState.HANDED))
    }

    @Test
    fun `пошук без квитанції — не знайшли, а нова спроба шукає знову`() {
        assertEquals(HandoffPhase.NOT_FOUND, run(NearbyState.IDLE, true, NearbyState.SEARCHING, NearbyState.LISTENING))
        assertEquals(HandoffPhase.SEARCHING, run(NearbyState.IDLE, true, NearbyState.SEARCHING, NearbyState.IDLE, NearbyState.SEARCHING))
    }

    @Test
    fun `передане лишається переданим, хоч телефон потім слухає поруч`() {
        assertEquals(HandoffPhase.HANDED, run(NearbyState.IDLE, true, NearbyState.SEARCHING, NearbyState.HANDED, NearbyState.LISTENING, NearbyState.SEARCHING))
    }

    @Test
    fun `без дороги поруч купюра просто лежить у розмові`() {
        assertEquals(HandoffPhase.QUEUED, run(NearbyState.OFF, true))
        assertEquals(HandoffPhase.QUEUED, run(NearbyState.IDLE, false))
        assertEquals(HandoffPhase.QUEUED, run(null, true))
        assertEquals(HandoffPhase.QUEUED, run(NearbyState.IDLE, true, NearbyState.NEEDS_PERMISSION))
    }
}
