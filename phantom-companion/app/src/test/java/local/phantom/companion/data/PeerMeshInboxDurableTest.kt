package local.phantom.companion.data

import android.content.ContextWrapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.peer.PeerBeacon
import local.phantom.companion.core.net.peer.PeerChannel
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRelayTransport
import local.phantom.companion.core.net.peer.PeerSealing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** vc29: лист у шторці, сховок спалив копію, а на диск він так і не ліг — після перезапуску його немає ніде. */
class PeerMeshInboxDurableTest {

    private class MemSlot : PeerLogSlot {
        val cells = java.util.concurrent.ConcurrentHashMap<String, String>()
        val slowMs = java.util.concurrent.atomic.AtomicLong(0)
        val broken = AtomicBoolean(false)
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (broken.get()) error("диск не прийняв")
            Thread.sleep(slowMs.get())
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private class Phone(name: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val pubB64: String = b64(signing.publicKey)
        val dhPubB64: String = b64(dh.publicKey)
        val id: String = PeerIntro.fingerprint(pubB64)
        val name0 = name

        fun row() = PeerIdentity(
            id = id,
            name = name0,
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

    private val me = Phone("Я")
    private val friend = Phone("Тестувальник")
    private val relay = FakeRelay()
    private val slot = MemSlot()
    private val roster = PeerRosterStore(MemSlotRoster()).apply { upsert(friend.row()) }

    private val onDiskWhenAnnounced = CopyOnWriteArrayList<Boolean>()
    private val announced = CopyOnWriteArrayList<String>()

    private val mesh = PeerMesh(
        roster = roster,
        log = PeerOutboxStore(slot),
        selfId = { me.id },
        privateKey = { me.signing.privateKey },
        publicKey = { me.pubB64 },
        dhPrivateKey = { me.dh.privateKey },
        dhPublicKey = { me.dhPubB64 },
        beacon = PeerBeacon(object : ContextWrapper(null) {}),
        relayOpen = { relay },
        online = { true },
        onArrived = { m ->
            announced += m.id
            onDiskWhenAnnounced += PeerOutboxStore(slot).load().any { it.id == m.id }
        },
        nowMs = { NOW },
    )

    private class MemSlotRoster : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    @After
    fun tearDown() {
        mesh.stop()
    }

    private fun dropFromFriend(id: String, body: String) {
        val sender = PeerMeshRelay(
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
        val letter = PeerSealing.seal(
            PeerMessage(id = id, fromId = friend.id, toId = me.id, body = body, sentAtMs = NOW),
            friend.signing.privateKey,
        )
        runBlocking { assertTrue(sender.handOff(letter)) }
    }

    @Test(timeout = 30_000)
    fun `the shade rings only for a letter that is already on disk`() = runBlocking {
        slot.slowMs.set(300)
        dropFromFriend("m-1", "привіт, ти тут?")

        mesh.collectRelay()

        assertEquals(listOf("m-1"), announced)
        assertEquals("лист у шторці, а після перезапуску його немає", listOf(true), onDiskWhenAnnounced)
    }

    @Test(timeout = 30_000)
    fun `a letter the disk refused is neither announced nor burnt on the relay`() = runBlocking {
        slot.broken.set(true)
        dropFromFriend("m-2", "дійде?")
        val copy = relay.store.keys.single()

        mesh.collectRelay()

        assertTrue("сповіщення про лист, якого на диску немає", announced.isEmpty())
        assertTrue("копію на сервері спалено, хоча на диску листа немає", copy in relay.store)
        assertFalse(mesh.outbox.value.any { it.id == "m-2" })

        slot.broken.set(false)
        mesh.collectRelay()

        assertEquals("наступний прохід мусить і зберегти, і сповістити", listOf("m-2"), announced)
        assertEquals(listOf(true), onDiskWhenAnnounced)
        assertFalse(copy in relay.store)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L

        fun b64(bytes: ByteArray): String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
