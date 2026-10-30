package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val T0 = 1_790_000_000_000L

class PeerWhereAccessTest {

    private class Node(val id: String) {
        val dh: X25519.Keypair = dhKeypair()
        val dhPubB64: String get() = b64(dh.publicKey)
        fun keyWith(other: Node): ByteArray =
            PeerChannel.derive(dh.privateKey, other.dhPubB64, id, other.id)!!
    }

    private val a = Node("aaaa1111")
    private val b = Node("bbbb2222")
    private val c = Node("cccc3333")

    private fun letter(to: Node, body: String) = PeerMessage(
        id = "6e3c1a70-0f51-4a3b-9d2e-71b0c4e5a9f2",
        fromId = a.id,
        toId = to.id,
        body = body,
        sentAtMs = T0,
        signature = "s".repeat(86),
    )

    private fun bodyFromBeacon(p: PeerWhere.Precision): String {
        val s = PeerWhereBeacon.grant(PeerWhereBeacon.State(), whereFriend(b.id), p, T0)!!.state
        return PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(50.45, 30.52, 5, T0), T0).out.single().body
    }

    @Test
    fun `той, кому не дали, не відкриває жодної точності`() {
        PeerWhere.Precision.entries.forEach { p ->
            val frame = PeerEnvelope.seal(letter(b, bodyFromBeacon(p)), a.keyWith(b))!!.frame
            val opened = PeerEnvelope.open(frame) { b.keyWith(a) }
            assertEquals(p, (PeerWhere.decode(opened!!.body) as PeerWhere.Position).precision)
            assertNull("третій з ключем до відправника відкрив чужу точку", PeerEnvelope.open(frame) { c.keyWith(a) })
            assertNull("третій з ключем до одержувача відкрив чужу точку", PeerEnvelope.open(frame) { c.keyWith(b) })
        }
    }

    @Test
    fun `кожному одержувачу — свій конверт під його ключем`() {
        val body = bodyFromBeacon(PeerWhere.Precision.DISTRICT)
        val toB = PeerEnvelope.seal(letter(b, body), a.keyWith(b))!!.frame
        val toC = PeerEnvelope.seal(letter(c, body), a.keyWith(c))!!.frame
        assertNull(PeerEnvelope.open(toC) { b.keyWith(a) })
        assertNull(PeerEnvelope.open(toB) { c.keyWith(a) })
        assertNotNull(PeerEnvelope.open(toC) { c.keyWith(a) })
    }

    @Test
    fun `дозвіл кожному взаємно доданому зі спільним ключем, без звірки камерою`() {
        val s = PeerWhereBeacon.State()
        assertNotNull(PeerWhereBeacon.grant(s, whereFriend("ok"), nowMs = T0))
        assertNotNull("незвірений контакт", PeerWhereBeacon.grant(s, whereFriend("x", proven = false), nowMs = T0))
        assertNotNull(PeerWhereBeacon.grant(s, whereFriend("v").copy(keySource = PeerIdentity.KeySource.REMOTE), nowMs = T0))
        assertNull("нас у їхньому реєстрі нема", PeerWhereBeacon.grant(s, whereFriend("y", introduced = false), nowMs = T0))
        assertNull("ключ підмінили", PeerWhereBeacon.grant(s, whereFriend("z").copy(keyChangedAtMs = 5L), nowMs = T0))
        assertNull("незвіреному підмінили ключ", PeerWhereBeacon.grant(s, whereFriend("u", proven = false).copy(keyChangedAtMs = 5L), nowMs = T0))
        assertNull("ключа немає", PeerWhereBeacon.grant(s, whereFriend("w").copy(dhPubKey = ""), nowMs = T0))
    }

    @Test
    fun `той, кому не дали, не отримує нічого — ні на рух, ні на питання`() {
        var s = PeerWhereBeacon.grant(PeerWhereBeacon.State(), whereFriend(b.id), nowMs = T0)!!.state
        val sent = mutableListOf<String>()
        var t = T0
        repeat(40) { i ->
            t += 60_000L
            sent += PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(50.0 + i * 0.05, 30.0, 5, t), t).also { s = it.state }.out.map { it.peerId }
            sent += PeerWhereBeacon.onAsk(s, c.id, t).also { s = it.state }.out.map { it.peerId }
            sent += PeerWhereBeacon.onTick(s, t).also { s = it.state }.out.map { it.peerId }
        }
        assertTrue(sent.isNotEmpty())
        assertEquals(setOf(b.id), sent.toSet())
    }

    @Test
    fun `після підміни ключа і після відклику — тиша`() {
        var s = PeerWhereBeacon.grant(PeerWhereBeacon.State(), whereFriend(b.id), nowMs = T0)!!.state
        s = PeerWhereBeacon.grant(s, whereFriend(c.id), nowMs = T0)!!.state
        s = PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(50.45, 30.52, 5, T0), T0).state
        s = PeerWhereBeacon.prune(s, listOf(whereFriend(b.id).copy(keyChangedAtMs = 9L), whereFriend(c.id)))
        val revoked = PeerWhereBeacon.revoke(s, c.id, T0 + 1)
        assertTrue(PeerWhere.decode(revoked.out.single().body) is PeerWhere.Erase)
        s = revoked.state
        val far = T0 + 3_600_000L
        val out = PeerWhereBeacon.onFix(s, PeerWhereBeacon.Fix(48.0, 35.0, 5, far), far).out +
            PeerWhereBeacon.onAsk(s, b.id, far).out + PeerWhereBeacon.onAsk(s, c.id, far).out
        assertEquals(emptyList<PeerWhereBeacon.Out>(), out)
    }

    @Test
    fun `чужий лист не лягає у приймач`() {
        val body = bodyFromBeacon(PeerWhere.Precision.EXACT)
        val inbox = PeerWhereInbox.absorb(PeerWhereInbox.Inbox(), a.id, body, T0, allowed = false)
        assertTrue(inbox.standing.isEmpty())
    }
}
