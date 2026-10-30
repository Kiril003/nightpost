package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Що сервер може дізнатись зі сховка — і чого не може. Кожне твердження з
 * обіцянки перевіряється тут, бо обіцянка приватності, яку ніхто не перевіряв,
 * коштує рівно стільки ж, скільки її відсутність.
 */
class PeerRelayTest {

    private val pairKey = ByteArray(32) { it.toByte() }
    private val a = "peer-a"
    private val b = "peer-b"
    private val decoySecret = ByteArray(32) { 9 }

    private fun keyA() = PeerRelay.relayKey(pairKey, a, b)!!
    private fun keyB() = PeerRelay.relayKey(pairKey, b, a)!!

    @Test
    fun `both sides derive the same relay key without agreeing who goes first`() {
        assertTrue(keyA().contentEquals(keyB()))
    }

    @Test
    fun `the relay key is not the pair key`() {
        assertFalse(keyA().contentEquals(pairKey))
    }

    @Test
    fun `a peer with no key gets no key`() {
        assertNull(PeerRelay.relayKey(ByteArray(0), a, b))
        assertNull(PeerRelay.relayKey(pairKey, "", b))
    }

    @Test
    fun `a mailbox name is 43 url-safe characters and nothing else`() {
        val tag = PeerRelay.msgTag(keyA(), a, b, 20_000L)!!
        assertEquals(PeerRelay.TAG_CHARS, tag.length)
        assertTrue(PeerRelay.isTag(tag))
    }

    @Test
    fun `tomorrow's mailbox is unlinkable to today's`() {
        val today = PeerRelay.msgTag(keyA(), a, b, 20_000L)!!
        val tomorrow = PeerRelay.msgTag(keyA(), a, b, 20_001L)!!
        assertNotEquals(today, tomorrow)
    }

    @Test
    fun `letters and receipts of one pair live in different mailboxes`() {
        assertNotEquals(
            PeerRelay.msgTag(keyA(), a, b, 20_000L),
            PeerRelay.ackTag(keyA(), a, b, 20_000L),
        )
    }

    @Test
    fun `both ends compute the same ack mailbox for one direction`() {
        assertEquals(
            PeerRelay.ackTag(keyA(), a, b, 20_000L),
            PeerRelay.ackTag(keyB(), a, b, 20_000L),
        )
    }

    @Test
    fun `a decoy is shaped exactly like a real mailbox`() {
        val decoys = PeerRelay.decoyTags(8, decoySecret, 20_000L)
        assertEquals(8, decoys.size)
        assertTrue(decoys.all { PeerRelay.isTag(it) })
        assertEquals(8, decoys.toSet().size)
    }

    @Test
    fun `every blob is one of three lengths whatever was said`() {
        val key = keyA()
        val tag = PeerRelay.msgTag(key, a, b, 20_000L)!!
        val sizes = listOf("", "коротко", "х".repeat(5_000), "х".repeat(30_000))
            .mapNotNull { PeerRelay.wrap(it, key, tag)?.blob?.size }
        assertEquals(4, sizes.size)
        assertTrue(sizes.all { it in PeerRelay.BLOB_SIZES })
        assertEquals(3, sizes.toSet().size)
    }

    @Test
    fun `the three accepted lengths are derived from the buckets, never typed twice`() {
        assertEquals(listOf(4_124, 16_412, 69_660), PeerRelay.BLOB_SIZES)
    }

    @Test
    fun `a short word goes in the small bucket only when the server has said it takes one`() {
        val key = keyA()
        val tag = PeerRelay.msgTag(key, a, b, 20_000L)!!
        assertEquals(540, PeerRelay.SMALL_BLOB_SIZE)
        assertEquals(4_124, PeerRelay.wrap("ок", key, tag)!!.blob.size)
        val small = PeerRelay.wrap("ок", key, tag, small = true)!!
        assertEquals(PeerRelay.SMALL_BLOB_SIZE, small.blob.size)
        assertEquals("ок", PeerRelay.unwrap(small.blob, key, tag))
        assertEquals(4_124, PeerRelay.wrap("х".repeat(600), key, tag, small = true)!!.blob.size)
    }

    @Test
    fun `a letter too big for the largest bucket is refused rather than shrunk`() {
        val key = keyA()
        val tag = PeerRelay.msgTag(key, a, b, 20_000L)!!
        assertNull(PeerRelay.wrap("х".repeat(70_000), key, tag))
    }

    @Test
    fun `what was wrapped comes back exactly`() {
        val key = keyA()
        val tag = PeerRelay.msgTag(key, a, b, 20_000L)!!
        val wrapped = PeerRelay.wrap("слово, і кома", key, tag)!!
        assertEquals("слово, і кома", PeerRelay.unwrap(wrapped.blob, key, tag))
    }

    @Test
    fun `a blob moved to another mailbox does not open`() {
        val key = keyA()
        val mine = PeerRelay.msgTag(key, a, b, 20_000L)!!
        val other = PeerRelay.msgTag(key, a, b, 20_001L)!!
        val wrapped = PeerRelay.wrap("слово", key, mine)!!
        assertNull(PeerRelay.unwrap(wrapped.blob, key, other))
    }

    @Test
    fun `a blob of the wrong length is not read at all`() {
        val key = keyA()
        val tag = PeerRelay.msgTag(key, a, b, 20_000L)!!
        val wrapped = PeerRelay.wrap("слово", key, tag)!!
        assertNull(PeerRelay.unwrap(wrapped.blob.copyOf(wrapped.blob.size - 1), key, tag))
    }

    @Test
    fun `the nonce the receipt binds is the one the server carried`() {
        val key = keyA()
        val tag = PeerRelay.msgTag(key, a, b, 20_000L)!!
        val wrapped = PeerRelay.wrap("слово", key, tag)!!
        assertEquals(wrapped.nonceB64, PeerRelay.nonceOf(wrapped.blob))
    }

    @Test
    fun `two wraps of one letter never repeat a nonce`() {
        val key = keyA()
        val tag = PeerRelay.msgTag(key, a, b, 20_000L)!!
        assertNotEquals(
            PeerRelay.wrap("слово", key, tag)!!.nonceB64,
            PeerRelay.wrap("слово", key, tag)!!.nonceB64,
        )
    }

    @Test
    fun `a poll always asks for exactly sixty-four names, even with one friend`() {
        val plan = PeerRelay.plan(
            myId = a,
            mailboxes = listOf(PeerRelay.Mailbox(b, keyA())),
            nowMs = 1_700_000_000_000L,
            rng = java.util.Random(7),
            decoySecret = decoySecret,
        )
        assertEquals(PeerRelay.FETCH_TAGS, plan.tags.size)
        assertTrue(plan.tags.all { PeerRelay.isTag(it) })
        // Шість справжніх: слово й квитанція на кожну з трьох діб.
        assertEquals(6, plan.slots.size)
    }

    @Test
    fun `a poll with nobody in the roster is all decoys`() {
        val plan = PeerRelay.plan(a, emptyList(), 1_700_000_000_000L, rng = java.util.Random(7), decoySecret = decoySecret)
        assertEquals(PeerRelay.FETCH_TAGS, plan.tags.size)
        assertTrue(plan.slots.isEmpty())
    }

    @Test
    fun `a big crew does not shrink the request, the window rotates instead`() {
        val boxes = (1..20).map { PeerRelay.Mailbox("peer-$it", PeerRelay.relayKey(pairKey, a, "peer-$it")!!) }
        val first = PeerRelay.plan(a, boxes, 1_700_000_000_000L, round = 0L, rng = java.util.Random(7), decoySecret = decoySecret)
        val second = PeerRelay.plan(a, boxes, 1_700_000_000_000L, round = 1L, rng = java.util.Random(7), decoySecret = decoySecret)
        assertEquals(PeerRelay.FETCH_TAGS, first.tags.size)
        assertEquals(PeerRelay.FETCH_TAGS, second.tags.size)
        assertNotEquals(first.slots.keys, second.slots.keys)
    }

    @Test
    fun `today is asked before a drifted clock's yesterday`() {
        val now = 1_700_000_000_000L
        val today = PeerRelay.epochOf(now)
        val boxes = (1..20).map { PeerRelay.Mailbox("peer-$it", PeerRelay.relayKey(pairKey, a, "peer-$it")!!) }
        val plan = PeerRelay.plan(a, boxes, now, round = 0L, rng = java.util.Random(7), decoySecret = decoySecret)
        // Двадцятеро — це 40 сьогоднішніх скриньок, і жодна з них не має випасти
        // за межі першого заходу заради вчорашніх.
        assertEquals(40, plan.slots.values.count { it.epoch == today })
    }

    @Test
    fun `the nearest phone wins, the relay is only the way out`() {
        assertEquals(PeerRelay.Way.DIRECT, PeerRelay.first(hasLanAddress = true, online = true))
        assertEquals(PeerRelay.Way.DIRECT, PeerRelay.first(hasLanAddress = true, online = false))
        assertEquals(PeerRelay.Way.RELAY, PeerRelay.first(hasLanAddress = false, online = true))
        assertEquals(PeerRelay.Way.WAIT, PeerRelay.first(hasLanAddress = false, online = false))
    }

    @Test
    fun `a direct attempt that missed falls to the relay only when there is internet`() {
        assertEquals(PeerRelay.Way.RELAY, PeerRelay.fallback(online = true))
        assertEquals(PeerRelay.Way.WAIT, PeerRelay.fallback(online = false))
    }

    @Test
    fun `a phone with no contacts makes no relay traffic at all`() {
        assertFalse(PeerRelay.pollingWanted(0))
        assertTrue(PeerRelay.pollingWanted(1))
    }

    @Test
    fun `a receipt names its letter through the nonce and nothing else`() {
        val held = PeerMessage(
            id = "m-1",
            fromId = a,
            toId = b,
            body = "слово",
            sentAtMs = 10L,
            state = PeerMessage.State.HELD,
            heldNonce = "bm9uY2UtMTIz",
        )
        val receipt = PeerReceipt.of(pairKey, "m-1", a, b, "bm9uY2UtMTIz")!!
        assertEquals("m-1", PeerRelay.receiptOwner(listOf(held), receipt, pairKey))
    }

    @Test
    fun `a receipt for another copy of the same letter is not believed`() {
        val held = PeerMessage(
            id = "m-1",
            fromId = a,
            toId = b,
            body = "слово",
            sentAtMs = 10L,
            state = PeerMessage.State.HELD,
            heldNonce = "bm9uY2UtMTIz",
        )
        val stale = PeerReceipt.of(pairKey, "m-1", a, b, "aW5zaGUtbm9uY2U")!!
        assertNull(PeerRelay.receiptOwner(listOf(held), stale, pairKey))
    }
}
