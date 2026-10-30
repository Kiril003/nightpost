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
import local.phantom.companion.core.net.peer.PeerSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Блокування на справжніх дверях [PeerMesh]: сокет [PeerLink] і сховок.
 *
 * Кожна перевірка має пару — той самий кадр від незаблокованого доходить, —
 * бо «не дійшло» саме по собі доводить лише, що не працює нічого.
 */
class PeerMeshBlockTest {

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

    @Test
    fun `лист заблокованого через сокет не лягає в журнал і не має квитанції`() = runBlocking {
        blocks.block(pest.id)
        val port = port()

        val refused = sendOverLan(port, pest, "m-pest", "я знаю, де ти")
        val passed = sendOverLan(port, friend, "m-friend", "привіт")

        assertNotEquals(PeerLink.Outcome.Acked, refused)
        assertEquals(PeerLink.Outcome.Acked, passed)
        assertFalse(mesh.outbox.value.any { it.id == "m-pest" })
        assertTrue(mesh.outbox.value.any { it.id == "m-friend" })
        assertEquals(listOf("m-friend"), arrived.map { it.id })
        assertTrue("заблокований не стає «незнайомцем» на екрані", mesh.strangers.value.isEmpty())
    }

    @Test
    fun `розблокований знову доходить тими самими дверима`() = runBlocking {
        blocks.block(pest.id)
        val port = port()
        assertNotEquals(PeerLink.Outcome.Acked, sendOverLan(port, pest, "m-1", "раз"))

        blocks.unblock(pest.id)
        assertEquals(PeerLink.Outcome.Acked, sendOverLan(port, pest, "m-2", "два"))
        assertEquals(listOf("m-2"), mesh.outbox.value.map { it.id })
    }

    @Test
    fun `вітання заблокованого не дає ключа зустрічі`() = runBlocking {
        blocks.block(pest.id)
        val port = port()
        val link = PeerLink()

        val fromPest = PeerSession.offer(pest.id, me.id, pest.signing.privateKey)!!
        val fromFriend = PeerSession.offer(friend.id, me.id, friend.signing.privateKey)!!

        assertTrue(link.hello("127.0.0.1", port, fromPest.frame) is PeerLink.Outcome.Refused)
        assertTrue(link.hello("127.0.0.1", port, fromFriend.frame) is PeerLink.Outcome.Met)
    }

    @Test
    fun `знайомство заблокованого не вписує його назад`() = runBlocking {
        blocks.block(pest.id)
        val port = port()
        val offer = PeerIntro.offer(
            me = pest.invite,
            toId = me.id,
            theirDhPubB64 = me.dhPubB64,
            mySigningKey = pest.signing.privateKey,
            myDhPrivate = pest.dh.privateKey,
        )!!
        val before = roster.peers.value.single { it.id == pest.id }

        assertTrue(PeerLink().intro("127.0.0.1", port, offer.frame) is PeerLink.Outcome.Refused)
        assertEquals(before, roster.peers.value.single { it.id == pest.id })

        blocks.unblock(pest.id)
        assertTrue(PeerLink().intro("127.0.0.1", port, offer.frame) is PeerLink.Outcome.Met)
    }

    @Test
    fun `лист заблокованого лежить у сховку нерозпечатаний і без квитанції`() = runBlocking {
        blocks.block(pest.id)
        fun sender(from: Phone) = PeerMeshRelay(
            whoAmI = { from.id },
            decoySecretOf = { ByteArray(32) { 9 } },
            roster = { listOf(me.id) },
            pairKeyOf = { PeerChannel.derive(from.dh.privateKey, me.dhPubB64, from.id, me.id) },
            signingKeyOf = { me.signing.publicKey },
            readLog = { emptyList() },
            writeLog = {},
            store = { true },
            open = { relay },
            nowMs = { NOW },
        )
        assertTrue(sender(pest).handOff(letter(pest, "r-pest", "я знаю, де ти")))
        assertTrue(sender(friend).handOff(letter(friend, "r-friend", "привіт")))
        val pestCopy = relay.store.keys.first()
        val friendCopy = relay.store.keys.last()

        val report = mesh.collectRelay()

        assertEquals(1, report.arrived)
        assertFalse(mesh.outbox.value.any { it.id == "r-pest" })
        assertTrue(mesh.outbox.value.any { it.id == "r-friend" })
        assertEquals(listOf("r-friend"), arrived.map { it.id })
        assertTrue("копія заблокованого не спалена", pestCopy in relay.store)
        assertFalse(friendCopy in relay.store)
        // Поруч із нею — рівно одна нова копія: квитанція другові, не йому.
        assertEquals(2, relay.store.size)
        assertTrue(PeerOutbox.conversation(mesh.outbox.value, pest.id).isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L

        fun b64(bytes: ByteArray): String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
