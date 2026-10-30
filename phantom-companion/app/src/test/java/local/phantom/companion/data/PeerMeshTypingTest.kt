package local.phantom.companion.data

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerBeacon
import local.phantom.companion.core.net.peer.PeerChannel
import local.phantom.companion.core.net.peer.PeerEnvelope
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerLink
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerReceipt
import local.phantom.companion.core.net.peer.PeerRelayTransport
import local.phantom.companion.core.net.peer.PeerSealing
import local.phantom.companion.core.net.peer.PeerTyping
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Друкує…» на справжніх дверях [PeerMesh]: ні журналу, ні черги, ні сховка. */
class PeerMeshTypingTest {

    private class MemSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private class Phone(name: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val pubB64: String = b64(signing.publicKey)
        val dhPubB64: String = b64(dh.publicKey)
        val id: String = PeerIntro.fingerprint(pubB64)
        val invite = PeerInvite.Invite(
            peerId = id,
            name = name,
            pubKey = pubB64,
            dhPubKey = dhPubB64,
            host = "127.0.0.1",
            port = 4444,
        )

        fun row() = PeerIdentity(
            id = id,
            name = invite.name,
            kind = PeerIdentity.Kind.PERSON,
            pubKey = pubB64,
            dhPubKey = dhPubB64,
            addedAtMs = 1L,
        )
    }

    private class FakeRelay : PeerRelayTransport {
        val store = LinkedHashMap<String, Pair<String, ByteArray>>()
        private var seq = 0

        override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
            store["blob-${seq++}"] = tag to blob
            return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD)
        }

        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? {
            val asked = tags.toSet()
            return PeerRelayTransport.Pickup(
                store.filter { it.value.first in asked }
                    .map { PeerRelayTransport.Blob(it.key, it.value.first, it.value.second) },
            )
        }

        override suspend fun burn(ids: List<String>): Boolean {
            ids.forEach { store.remove(it) }
            return true
        }
    }

    private val me = Phone("Кирило")
    private val pest = Phone("Назойливий")
    private val friend = Phone("Друг")

    private val relay = FakeRelay()
    private val blocks = PeerBlockStore(MemSlot())
    private val roster = PeerRosterStore(MemSlot()).apply {
        upsert(pest.row())
        upsert(friend.row())
    }
    private val arrived = java.util.concurrent.CopyOnWriteArrayList<PeerMessage>()
    private val typed = java.util.concurrent.CopyOnWriteArrayList<PeerMessage>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mesh = PeerMesh(
        roster = roster,
        selfId = { me.id },
        privateKey = { me.signing.privateKey },
        publicKey = { me.pubB64 },
        dhPrivateKey = { me.dh.privateKey },
        dhPublicKey = { me.dhPubB64 },
        beacon = PeerBeacon(object : ContextWrapper(null) {}),
        relayOpen = { relay },
        online = { true },
        onArrived = { arrived += it },
        onTyping = { typed += it },
        nowMs = { NOW },
        blocked = { blocks.blocked.value },
    )

    @After
    fun tearDown() {
        mesh.stop()
        scope.cancel()
    }

    private fun pairKey(from: Phone) =
        PeerChannel.derive(from.dh.privateKey, me.dhPubB64, from.id, me.id)!!

    private fun letter(from: Phone, id: String, body: String) = PeerSealing.seal(
        PeerMessage(id = id, fromId = from.id, toId = me.id, body = body, sentAtMs = NOW),
        from.signing.privateKey,
    )

    private suspend fun port(): Int {
        mesh.start(scope)
        repeat(100) {
            mesh.myInvite.value?.port?.takeIf { it > 0 }?.let { return it }
            delay(20)
        }
        error("сітка не піднялась на сокеті")
    }

    private suspend fun sendOverLan(port: Int, from: Phone, id: String, body: String): PeerLink.Outcome {
        val message = letter(from, id, body)
        val key = pairKey(from)
        val sealed = PeerEnvelope.seal(message, key)!!
        val receipt = PeerReceipt.of(key, id, from.id, me.id, sealed.nonceB64)!!
        return PeerLink().send("127.0.0.1", port, receipt, sealed.frame)
    }

    private val on = PeerTyping.encode(PeerTyping.Typing(true))!!

    @Test
    fun `друк друга доходить повз журнал і шторку`() = runBlocking {
        val port = port()

        assertEquals(PeerLink.Outcome.Acked, sendOverLan(port, friend, "t-1", on))

        assertEquals(listOf("t-1"), typed.map { it.id })
        assertTrue(mesh.outbox.value.isEmpty())
        assertTrue(arrived.isEmpty())
    }

    @Test
    fun `друк заблокованого не доходить`() = runBlocking {
        blocks.block(pest.id)
        val port = port()

        assertNotEquals(PeerLink.Outcome.Acked, sendOverLan(port, pest, "t-pest", on))
        assertEquals(PeerLink.Outcome.Acked, sendOverLan(port, friend, "t-friend", on))

        assertEquals(listOf("t-friend"), typed.map { it.id })
    }

    @Test
    fun `заблокованому друк не йде`() = runBlocking {
        blocks.block(pest.id)
        mesh.start(scope)

        assertFalse(mesh.sendTyping(pest.id, on))
        assertTrue(mesh.outbox.value.isEmpty())
        assertTrue(relay.store.isEmpty())
    }

    @Test
    fun `офлайн друк не стає в чергу і не йде в сховок`() = runBlocking {
        mesh.start(scope)

        assertFalse(mesh.sendTyping(friend.id, on))
        assertFalse(mesh.sendTyping(friend.id, "друкую"))

        assertTrue(mesh.outbox.value.isEmpty())
        assertTrue(relay.store.isEmpty())
    }

    @Test
    fun `друк зі сховка не лягає в журнал`() = runBlocking {
        val courier = PeerMeshRelay(
            whoAmI = { friend.id },
            decoySecretOf = { ByteArray(32) { 9 } },
            roster = { listOf(me.id) },
            pairKeyOf = { PeerChannel.derive(friend.dh.privateKey, me.dhPubB64, friend.id, me.id) },
            signingKeyOf = { me.signing.publicKey },
            readLog = { emptyList() },
            writeLog = {},
            store = { true },
            open = { relay },
            nowMs = { NOW },
        )
        assertTrue(courier.handOff(letter(friend, "r-typ", on)))

        mesh.collectRelay()

        assertTrue(mesh.outbox.value.isEmpty())
        assertTrue(typed.isEmpty())
        assertTrue(arrived.isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L

        fun b64(bytes: ByteArray): String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
