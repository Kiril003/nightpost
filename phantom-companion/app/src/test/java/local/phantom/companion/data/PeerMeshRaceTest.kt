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
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRelayTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Адреса з mDNS ще «свіжа», а телефона там уже немає: сокет приймає зʼєднання
 * й мовчить. Раніше сховок чекав, поки пряма спроба вичерпає свої 8 с.
 */
class PeerMeshRaceTest {

    private class MemSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private class Relay : PeerRelayTransport {
        val drops = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
            drops += tag
            return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD)
        }
        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup =
            PeerRelayTransport.Pickup(emptyList())
        override suspend fun burn(ids: List<String>): Boolean = true
    }

    private val now = System.currentTimeMillis()
    private val meSigning = Ed25519.generateKeypair()
    private val meDh = X25519.generateKeypair()
    private val meId = PeerIntro.fingerprint(b64(meSigning.publicKey))
    private val friendSigning = Ed25519.generateKeypair()
    private val friendDh = X25519.generateKeypair()
    private val friendId = PeerIntro.fingerprint(b64(friendSigning.publicKey))

    // Слухає, приймає зʼєднання і нічого не відповідає — як телефон, що заснув посеред Wi-Fi.
    private val silent = java.net.ServerSocket(0)
    private val relay = Relay()
    private val roster = PeerRosterStore(MemSlot()).apply {
        upsert(
            PeerIdentity(
                id = friendId,
                name = "Друг",
                kind = PeerIdentity.Kind.PERSON,
                pubKey = b64(friendSigning.publicKey),
                dhPubKey = b64(friendDh.publicKey),
                transport = "127.0.0.1:${silent.localPort}",
                addedAtMs = 1L,
            ),
        )
        sawNearby(friendId, now)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mesh = PeerMesh(
        roster = roster,
        selfId = { meId },
        privateKey = { meSigning.privateKey },
        publicKey = { b64(meSigning.publicKey) },
        dhPrivateKey = { meDh.privateKey },
        dhPublicKey = { b64(meDh.publicKey) },
        beacon = PeerBeacon(object : ContextWrapper(null) {}),
        relayOpen = { relay },
        online = { true },
        nowMs = { System.currentTimeMillis() },
    )

    @After
    fun tearDown() {
        mesh.stop()
        scope.cancel()
        silent.close()
    }

    @Test
    fun `a silent neighbour does not hold the letter back from the relay`() = runBlocking {
        mesh.start(scope)
        repeat(200) { if (mesh.selfIdFlow.value.isNotEmpty()) return@repeat; delay(10) }
        delay(100)
        val sentAt = System.nanoTime()
        val line = mesh.send(friendId, "ти тут?")!!
        var heldMs = -1L
        while ((System.nanoTime() - sentAt) / 1_000_000 < 10_000) {
            if (mesh.outbox.value.single { it.id == line.id }.state == PeerMessage.State.HELD) {
                heldMs = (System.nanoTime() - sentAt) / 1_000_000
                break
            }
            delay(10)
        }
        println("silent LAN neighbour: send→HELD $heldMs ms")
        assertTrue("лист не дійшов до сховка", heldMs >= 0)
        assertTrue("send→HELD $heldMs мс", heldMs < 1_500)
        assertEquals("рівно одна копія в сховку", 1, relay.drops.size)
    }

    private companion object {
        fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
