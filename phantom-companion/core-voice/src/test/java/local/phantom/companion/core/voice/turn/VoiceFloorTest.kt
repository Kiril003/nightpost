package local.phantom.companion.core.voice.turn

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFloorTest {

    @After
    fun tearDown() = VoiceFloor.release(VoiceFloor.PHANTOM_CHAT)

    @Test
    fun `while PHANTOM's chat holds the floor, other listeners stay quiet`() {
        assertFalse(VoiceFloor.heldByOther(null))
        VoiceFloor.claim(VoiceFloor.PHANTOM_CHAT)
        assertTrue(VoiceFloor.heldByOther(null))
        assertFalse(VoiceFloor.heldByOther(VoiceFloor.PHANTOM_CHAT))
        VoiceFloor.release("someone-else")
        assertTrue(VoiceFloor.heldByOther(null))
        VoiceFloor.release(VoiceFloor.PHANTOM_CHAT)
        assertFalse(VoiceFloor.heldByOther(null))
    }
}
