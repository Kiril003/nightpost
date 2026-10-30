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
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerLink
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerReceipt
import local.phantom.companion.core.net.peer.PeerSealing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ключ із чужого заголовка гілки — лише в межах її стелі (картка msg-x 05).
 * [PeerGroup.decode] пускає до 50 імен заради простору, а знайома класична
 * гілка 33-го не має, тож і ключа з такого заголовка реєстр не бере.
 */
class PeerMeshGroupHeaderCapTest {

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
        val invite = PeerInvite.Invite(peerId = id, name = name, pubKey = pubB64, dhPubKey = dhPubB64, host = "", port = 0)

        fun row() = PeerIdentity(
            id = id,
            name = invite.name,
            kind = PeerIdentity.Kind.PERSON,
            pubKey = pubB64,
            dhPubKey = dhPubB64,
            addedAtMs = 1L,
        )
    }

    private val me = Phone("Кирило")
    private val friend = Phone("Друг")
    private val roster = PeerRosterStore(MemSlot()).apply { upsert(friend.row()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mesh = PeerMesh(
        roster = roster,
        selfId = { me.id },
        privateKey = { me.signing.privateKey },
        publicKey = { me.pubB64 },
        dhPrivateKey = { me.dh.privateKey },
        dhPublicKey = { me.dhPubB64 },
        beacon = PeerBeacon(object : ContextWrapper(null) {}),
        online = { true },
        nowMs = { NOW },
    )

    @After
    fun tearDown() {
        mesh.stop()
        scope.cancel()
    }

    private suspend fun port(): Int {
        mesh.start(scope)
        repeat(100) {
            mesh.myInvite.value?.port?.takeIf { it > 0 }?.let { return it }
            delay(20)
        }
        error("сітка не піднялась на сокеті")
    }

    private suspend fun send(port: Int, id: String, body: String, at: Long) {
        val message = PeerSealing.seal(
            PeerMessage(id = id, fromId = friend.id, toId = me.id, body = body, sentAtMs = at),
            friend.signing.privateKey,
        )
        val key = PeerChannel.derive(friend.dh.privateKey, me.dhPubB64, friend.id, me.id)!!
        val sealed = PeerEnvelope.seal(message, key)!!
        val receipt = PeerReceipt.of(key, id, friend.id, me.id, sealed.nonceB64)!!
        assertEquals(PeerLink.Outcome.Acked, PeerLink().send("127.0.0.1", port, receipt, sealed.frame))
    }

    private fun filler(n: Int) = (1..n).map { "f%015d".format(it) }

    private fun knows(p: Phone) = roster.peers.value.any { it.id == p.id && it.dhPubKey.isNotBlank() }

    private fun addOf(newcomer: Phone, members: List<String>): String {
        val act = PeerGroup.Act.Add(newcomer.id, PeerInvite.envelopeOf(newcomer.invite))
        return PeerGroup.encode(PeerGroup.Group(GROUP, "Виїзд", members), PeerGroup.encodeAct(act))
    }

    @Test
    fun `класична гілка не бере ключа з заголовка на 33 імені`() = runBlocking {
        val port = port()
        val base = listOf(friend.id, me.id)
        send(port, "g-open", PeerGroup.encode(PeerGroup.Group(GROUP, "Виїзд", base), "привіт"), NOW)
        val stranger = Phone("Чужий")
        send(port, "g-wide", addOf(stranger, base + filler(PeerGroup.MAX_MEMBERS - 1)), NOW + 1)
        assertTrue(mesh.outbox.value.any { it.id == "g-wide" })
        assertFalse("ключ із заголовка понад стелю потрапив у реєстр", knows(stranger))
    }

    @Test
    fun `той самий акт у межах стелі ключ дає`() = runBlocking {
        val port = port()
        val base = listOf(friend.id, me.id)
        send(port, "g-open", PeerGroup.encode(PeerGroup.Group(GROUP, "Виїзд", base), "привіт"), NOW)
        val newcomer = Phone("Новий")
        send(port, "g-add", addOf(newcomer, base + filler(PeerGroup.MAX_MEMBERS - 3) + newcomer.id), NOW + 1)
        assertTrue("ключ доданого в межах стелі не дійшов", knows(newcomer))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val GROUP = "grp123grp123"

        fun b64(bytes: ByteArray): String =
            java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
