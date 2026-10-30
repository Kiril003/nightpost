package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetHereFirstTest {
    @Test
    fun `meet here leads only while the meetup draft waits for a pin`() {
        assertTrue(meetFirst(anchoring = false, meetPicking = true, canMeet = true))
        assertFalse(meetFirst(anchoring = false, meetPicking = false, canMeet = true))
        assertFalse(meetFirst(anchoring = true, meetPicking = true, canMeet = true))
        assertFalse(meetFirst(anchoring = false, meetPicking = true, canMeet = false))
    }
}
