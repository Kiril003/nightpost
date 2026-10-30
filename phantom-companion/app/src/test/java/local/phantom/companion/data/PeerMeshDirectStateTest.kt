package local.phantom.companion.data

import android.content.ContextWrapper
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerBeacon
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Питання до локального помічника: знак «не пішло» й «пішло знов» міняє той самий рядок журналу. */
class PeerMeshDirectStateTest {

    private class MemSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private val signing = Ed25519.generateKeypair()
    private val dh = X25519.generateKeypair()
    private val pubB64 = java.util.Base64.getEncoder().encodeToString(signing.publicKey)
    private val dhB64 = java.util.Base64.getEncoder().encodeToString(dh.publicKey)
    private val myId = PeerIntro.fingerprint(pubB64)

    private val mesh = PeerMesh(
        roster = PeerRosterStore(MemSlot()),
        selfId = { myId },
        privateKey = { signing.privateKey },
        publicKey = { pubB64 },
        dhPrivateKey = { dh.privateKey },
        dhPublicKey = { dhB64 },
        beacon = PeerBeacon(object : ContextWrapper(null) {}),
        relayOpen = { null },
        online = { true },
        onArrived = { },
        onTyping = { },
        nowMs = { 1_000L },
    )

    @After
    fun tearDown() = mesh.stop()

    private fun ask(id: String) = PeerMessage(id = id, fromId = myId, toId = "peer_phantom_agent", body = "Why is the sky blue?", sentAtMs = 1_000L)

    @Test
    fun `failing and retrying a question never adds a second row`() {
        mesh.ingestDirect(ask("msg_q1"))
        mesh.ingestDirect(ask("msg_q2"))
        assertEquals(listOf(PeerMessage.State.DELIVERED, PeerMessage.State.DELIVERED), mesh.outbox.value.map { it.state })

        mesh.markDirect("msg_q1", PeerMessage.State.FAILED)
        assertEquals(listOf("msg_q1", "msg_q2"), mesh.outbox.value.map { it.id })
        assertEquals(listOf(PeerMessage.State.FAILED, PeerMessage.State.DELIVERED), mesh.outbox.value.map { it.state })

        mesh.markDirect("msg_q1", PeerMessage.State.DELIVERED)
        assertEquals(listOf("msg_q1", "msg_q2"), mesh.outbox.value.map { it.id })
        assertEquals(listOf(PeerMessage.State.DELIVERED, PeerMessage.State.DELIVERED), mesh.outbox.value.map { it.state })
    }
}
