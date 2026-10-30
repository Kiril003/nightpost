package local.phantom.companion.core.net.peer

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L

/**
 * Двері: скринька, яку може назвати кожен, хто тримає код запрошення, а
 * відчинити лист у ній — лише власник коду.
 */
class PeerRelayDoorTest {

    private class Phone(val id: String) {
        val dh = X25519.generateKeypair()
        val dhPubB64: String = java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    }

    private val b = Phone("phone-b")
    private val a = Phone("phone-a")
    private val today = PeerRelay.epochOf(NOW)

    @Test
    fun `door tag is deterministic, 43 chars and a valid tag`() {
        val one = PeerRelay.doorTag(b.dhPubB64, b.id, today)
        val two = PeerRelay.doorTag(b.dhPubB64, b.id, today)
        assertNotNull(one)
        assertEquals(one, two)
        assertEquals(PeerRelay.TAG_CHARS, one!!.length)
        assertTrue(PeerRelay.isTag(one))
    }

    @Test
    fun `padded and unpadded invite keys name the same door`() {
        val padded = java.util.Base64.getEncoder().encodeToString(b.dh.publicKey)
        assertEquals(PeerRelay.doorTag(padded, b.id, today), PeerRelay.doorTag(b.dhPubB64, b.id, today))
        assertEquals(
            PeerRelay.doorTag(b.dhPubB64, b.id, today),
            PeerRelay.doorTagOf(PeerRelay.doorKey(b.dh.publicKey, b.id)!!, today),
        )
    }

    @Test
    fun `door tag differs per epoch and per person`() {
        val tags = (today - 1..today + 1).map { PeerRelay.doorTag(b.dhPubB64, b.id, it) }
        assertEquals(3, tags.toSet().size)
        assertNotEquals(PeerRelay.doorTag(b.dhPubB64, b.id, today), PeerRelay.doorTag(a.dhPubB64, a.id, today))
        assertNotEquals(PeerRelay.doorTag(b.dhPubB64, b.id, today), PeerRelay.doorTag(b.dhPubB64, a.id, today))
    }

    @Test
    fun `door tag is never a pair mailbox tag`() {
        val pair = PeerChannel.derive(a.dh.privateKey, b.dhPubB64, a.id, b.id)!!
        val relayKey = PeerRelay.relayKey(pair, a.id, b.id)!!
        val pairTags = (today - 1..today + 1).flatMap { e ->
            listOf(
                PeerRelay.msgTag(relayKey, a.id, b.id, e),
                PeerRelay.msgTag(relayKey, b.id, a.id, e),
                PeerRelay.ackTag(relayKey, a.id, b.id, e),
                PeerRelay.ackTag(relayKey, b.id, a.id, e),
            )
        }.toSet()
        val doors = (today - 1..today + 1).flatMap { e ->
            listOf(PeerRelay.doorTag(b.dhPubB64, b.id, e), PeerRelay.doorTag(a.dhPubB64, a.id, e))
        }
        doors.forEach { assertTrue(it !in pairTags) }
    }

    @Test
    fun `garbage keys name no door`() {
        assertNull(PeerRelay.doorTag("", b.id, today))
        assertNull(PeerRelay.doorTag("not-base64!!", b.id, today))
        assertNull(PeerRelay.doorTag(b.dhPubB64, "", today))
        assertNull(PeerRelay.doorTag(b.dhPubB64, "a\u001Fb", today))
    }

    @Test
    fun `a stored door letter has a size the server takes and only the owner opens it`() {
        val tag = PeerRelay.doorTag(b.dhPubB64, b.id, today)!!
        val blob = PeerRelay.sealDoor("ось я", b.dhPubB64, tag)!!
        assertTrue(PeerRelay.isBlobSize(blob.size))
        assertEquals("ось я", PeerRelay.openDoor(blob, b.dh.privateKey, tag))
        assertNull("чужий ключ не відчиняє", PeerRelay.openDoor(blob, a.dh.privateKey, tag))
        val other = PeerRelay.doorTag(b.dhPubB64, b.id, today + 1)!!
        assertNull("лист, перекладений в іншу скриньку, не відчиняється", PeerRelay.openDoor(blob, b.dh.privateKey, other))
    }

    @Test
    fun `small bucket only when the server announced it`() {
        val tag = PeerRelay.doorTag(b.dhPubB64, b.id, today)!!
        assertEquals(PeerRelay.SMALL_BLOB_SIZE, PeerRelay.sealDoor("так", b.dhPubB64, tag, small = true)!!.size)
        assertEquals(PeerRelay.BLOB_SIZES.first(), PeerRelay.sealDoor("так", b.dhPubB64, tag)!!.size)
    }

    @Test
    fun `a live frame fits the server live limit, an oversize one is refused`() {
        val tag = PeerRelay.doorTag(b.dhPubB64, b.id, today)!!
        val frame = "x".repeat(6_000)
        val live = PeerRelay.sealLive(frame, b.dhPubB64, tag)!!
        assertTrue(live.size <= PeerRelay.LIVE_MAX_BYTES)
        assertTrue(java.util.Base64.getEncoder().encodeToString(live).length <= 16 * 1024)
        assertEquals(frame, PeerRelay.openDoor(live, b.dh.privateKey, tag))
        assertNull(PeerRelay.sealLive("x".repeat(PeerRelay.LIVE_MAX_BYTES), b.dhPubB64, tag))
    }

    @Test
    fun `courier drops into their door for today`() = runBlocking {
        val seen = mutableListOf<Pair<String, ByteArray>>()
        val relay = object : PeerRelayTransport {
            override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
                seen += tag to blob
                return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD, NOW + PeerRelay.TTL_MS)
            }
            override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? = null
            override suspend fun burn(ids: List<String>): Boolean = true
        }
        val courier = PeerRelayCourier(relay) { NOW }

        val handoff = courier.dropDoor(b.dhPubB64, b.id, "ось я")

        assertTrue(handoff is PeerRelayCourier.Handoff.Held)
        val (tag, blob) = seen.single()
        assertEquals(PeerRelay.doorTag(b.dhPubB64, b.id, today), tag)
        assertEquals("ось я", PeerRelay.openDoor(blob, b.dh.privateKey, tag))
    }

    @Test
    fun `a key with no door is a refusal, not a missing channel`() {
        val courier = PeerRelayCourier(NoServer) { NOW }
        assertEquals(PeerRelayCourier.Live.REFUSED, courier.sendLive("not-base64!!", b.id, "кадр"))
        assertEquals(PeerRelayCourier.Live.NO_CHANNEL, courier.sendLive(b.dhPubB64, b.id, "кадр"))
    }

    @Test
    fun `a knock is a pure function of its nonce, so a nonce rebuilt after restart never seals a second text`() {
        val signing = Ed25519.generateKeypair()
        val pub = java.util.Base64.getEncoder().withoutPadding().encodeToString(signing.publicKey)
        val me = PeerInvite.Invite(peerId = a.id, name = "Аня", pubKey = pub, dhPubKey = a.dhPubB64, host = "", port = 0)
        val nonce = PeerRelay.knockNonce(a.dh.privateKey, b.id, today, me.name)!!
        assertEquals(12, nonce.size)
        assertTrue(nonce.contentEquals(PeerRelay.knockNonce(a.dh.privateKey, b.id, today, me.name)!!))

        val one = PeerIntro.offer(me, b.id, b.dhPubB64, signing.privateKey, a.dh.privateKey, nonce)!!
        val two = PeerIntro.offer(me, b.id, b.dhPubB64, signing.privateKey, a.dh.privateKey, nonce)!!
        assertEquals("той самий нонс — той самий текст до байта", one.frame, two.frame)
        assertEquals(java.util.Base64.getEncoder().encodeToString(nonce), one.nonceB64)

        val others = listOf(
            PeerRelay.knockNonce(a.dh.privateKey, b.id, today + 1, me.name),
            PeerRelay.knockNonce(a.dh.privateKey, b.id, today, "Аня К."),
            PeerRelay.knockNonce(a.dh.privateKey, "phone-c", today, me.name),
            PeerRelay.knockNonce(b.dh.privateKey, b.id, today, me.name),
        )
        others.forEach { assertNotEquals(one.nonceB64, java.util.Base64.getEncoder().encodeToString(it!!)) }
    }

    private object NoServer : PeerRelayTransport {
        override suspend fun drop(tag: String, blob: ByteArray) = PeerRelayTransport.Dropped(PeerRelayTransport.Drop.NO_CONTACT)
        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? = null
        override suspend fun burn(ids: List<String>): Boolean = false
    }
}
