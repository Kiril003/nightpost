package local.phantom.companion.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mesh service can be refused or killed while the process lives on, and then
 * the 15-minute relay worker is the only thing that still runs.
 */
class PeerRelayWorkerTest {

    private val server = MemRelay()
    private val a = MemPhone("Аня", server.Transport())
    private val b = MemPhone("Богдан", server.Transport())

    @After
    fun tearDown() {
        a.mesh.stop()
        server.shutdown()
    }

    @Test
    fun `a letter written while the mesh service is down is in the relay after one worker round`() = runBlocking {
        a.mesh.accept(b.invite)
        // An earlier worker round woke the process: identity is loaded, the service never started.
        a.mesh.collectRelay()
        val letter = a.mesh.send(b.id, "ти де?")!!
        delay(200)
        assertEquals(PeerMessage.State.QUEUED, a.mesh.outbox.value.single { it.id == letter.id }.state)

        PeerRelayWorker.round(a.mesh)

        assertEquals(PeerMessage.State.HELD, a.mesh.outbox.value.single { it.id == letter.id }.state)
        assertTrue("the relay holds the letter", server.stored().isNotEmpty())
        assertEquals("a background process keeps no live socket", 0, server.sockets())
    }
}
