package local.phantom.companion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkStartTest {

    @Test
    fun `boot starts the link only below 35`() {
        assertTrue(LinkStart.fromBoot(34))
        assertFalse(LinkStart.fromBoot(35))
        assertFalse(LinkStart.fromBoot(36))
    }

    @Test
    fun `foreground starts it only when down`() {
        assertTrue(LinkStart.fromForeground(false))
        assertFalse(LinkStart.fromForeground(true))
    }
}
