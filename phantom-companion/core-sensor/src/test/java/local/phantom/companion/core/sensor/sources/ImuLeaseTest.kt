package local.phantom.companion.core.sensor.sources

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lease arithmetic, on its own.
 *
 * [ImuSource] itself needs a real `SensorManager`, so the rule it encodes is
 * mirrored here as the plain counter it is. What must hold, and what the device
 * measurement of 2026-08-07 proved was not holding: sensors live while somebody
 * is holding them, and the LAST release turns them off. Two consumers must not
 * be able to switch each other's sensors off, and an unbalanced release must not
 * drive the count below zero — a negative lease would make the next acquire fail
 * to register and silently kill dead reckoning.
 */
class ImuLeaseTest {

    /** The exact rule from [ImuSource.acquire] / [ImuSource.release]. */
    private class Lease {
        var count = 0
            private set
        var live = false
            private set

        fun acquire() {
            count++
            if (!live) live = true
        }

        fun release() {
            if (count == 0) return
            count--
            if (count == 0) live = false
        }

        fun stop() {
            count = 0
            live = false
        }
    }

    @Test
    fun `the first acquire starts and the last release stops`() {
        val l = Lease()
        assertEquals(false, l.live)
        l.acquire()
        assertEquals(true, l.live)
        l.release()
        assertEquals(false, l.live)
    }

    @Test
    fun `a second consumer keeps the body alive when the first lets go`() {
        // The map is open (screen lease) and a drive is running (navigation
        // lease). Closing the screen must not stop the reckoner mid-drive.
        val l = Lease()
        l.acquire()
        l.acquire()
        l.release()
        assertEquals("one holder left — sensors must stay live", true, l.live)
        l.release()
        assertEquals(false, l.live)
    }

    @Test
    fun `an unbalanced release cannot drive the count negative`() {
        // A negative count would make the next acquire arrive at zero-plus-one
        // without registering, and the operator would be navigating on a body
        // that is switched off — with nothing on screen to say so.
        val l = Lease()
        l.release()
        l.release()
        assertEquals(0, l.count)
        l.acquire()
        assertEquals(true, l.live)
        assertEquals(1, l.count)
    }

    @Test
    fun `hard stop drops every lease`() {
        val l = Lease()
        l.acquire()
        l.acquire()
        l.stop()
        assertEquals(0, l.count)
        assertEquals(false, l.live)
    }
}
