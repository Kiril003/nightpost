package local.phantom.companion.core.net.peer

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Той самий шлях, яким лист іде насправді, тільки без сокета: телефон Б вимкнено,
 * телефон А пише, сервер тримає, Б прокидається — і А бачить «ДОСТАВЛЕНО».
 *
 * Перевіряється саме те, чого не видно з жодного окремого класу: що ключі двох
 * телефонів справді сходяться, що скриньку знаходять обидві сторони, що
 * квитанція привʼязана до тієї копії, яку віддав сервер, і що після всього на
 * сервері не лишається нічого.
 */
class PeerRelayCourierTest {

    /** Сервер, який уміє рівно те, що вміє справжній: тримати й віддавати байти. */
    private class FakeRelay : PeerRelayTransport {
        val store = LinkedHashMap<String, Pair<String, ByteArray>>()
        var reachable = true
        var full = false
        private var seq = 0

        /** Строк називає сервер, як справжній. */
        var heldUntilMs: Long? = null

        override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
            if (!reachable) return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.NO_CONTACT)
            if (full) return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.FULL)
            if (blob.size !in PeerRelay.BLOB_SIZES) {
                return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.REFUSED)
            }
            store["blob-${seq++}"] = tag to blob
            return PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD, heldUntilMs)
        }

        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? {
            if (!reachable) return null
            if (tags.size != PeerRelay.FETCH_TAGS) return null
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

    private class Phone(val id: String) {
        val signing = Ed25519.generateKeypair()
        val dh = X25519.generateKeypair()
        val dhPubB64: String = b64(dh.publicKey)
    }

    private val now = 1_700_000_000_000L

    private fun pairKey(mine: Phone, theirs: Phone): ByteArray =
        PeerChannel.derive(mine.dh.privateKey, theirs.dhPubB64, mine.id, theirs.id)!!

    private fun letter(from: Phone, to: Phone, body: String): PeerMessage =
        PeerSealing.seal(
            PeerMessage(
                id = "m-1",
                fromId = from.id,
                toId = to.id,
                body = body,
                sentAtMs = now,
            ),
            from.signing.privateKey,
        )

    @Test
    fun `a letter reaches a phone that was switched off, and the sender learns it landed`() = runBlocking {
        val relay = FakeRelay()
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val keyA = pairKey(a, b)
        val keyB = pairKey(b, a)
        val relayA = PeerRelay.relayKey(keyA, a.id, b.id)!!
        val relayB = PeerRelay.relayKey(keyB, b.id, a.id)!!
        val courierA = PeerRelayCourier(relay) { now }
        val courierB = PeerRelayCourier(relay) { now }

        // Б вимкнено: жодного сокета, лише сховок.
        val message = letter(a, b, "виходь на звʼязок")
        var outboxA = PeerOutbox.enqueue(emptyList(), message)
        val handoff = courierA.handOff(message, keyA, relayA)
        assertTrue(handoff is PeerRelayCourier.Handoff.Held)
        val held = handoff as PeerRelayCourier.Handoff.Held
        outboxA = PeerOutbox.markHeld(outboxA, message.id, held.nonceB64, held.heldUntilMs)

        // Слово в сховку — це НЕ доставка, і черга за нього більше не платить.
        assertEquals(PeerMessage.State.HELD, outboxA.first().state)
        assertTrue(PeerOutbox.due(outboxA, now + 10_000_000L).isEmpty())
        assertEquals(1, PeerOutbox.waiting(outboxA))

        // Б прокидається й питає свої скриньки.
        val planB = PeerRelay.plan(b.id, listOf(PeerRelay.Mailbox(a.id, relayB)), now, rng = java.util.Random(1), decoySecret = ByteArray(32) { 9 })
        val forB = courierB.collect(planB) { relayB }!!.letters
        assertEquals(1, forB.size)
        assertEquals(PeerRelay.Kind.LETTER, forB[0].kind)

        // Той самий шлях усередину, що й з дроту: інакше сховок був би дірою.
        val opened = PeerEnvelope.open(forB[0].payload) { keyB }
        assertNotNull(opened)
        assertTrue(PeerSealing.verify(opened!!, a.signing.publicKey, expectedFromId = a.id))
        assertEquals("виходь на звʼязок", opened.body)

        // Спочатку записали, потім квитанція, і лише потім спалили копію.
        assertTrue(courierB.acknowledge(opened, keyB, relayB, forB[0].outerNonceB64))
        courierB.burn(listOf(forB[0].blobId))

        // А наступного заходу забирає квитанцію й лише тоді каже «ДОСТАВЛЕНО».
        val planA = PeerRelay.plan(a.id, listOf(PeerRelay.Mailbox(b.id, relayA)), now, rng = java.util.Random(2), decoySecret = ByteArray(32) { 9 })
        val forA = courierA.collect(planA) { relayA }!!.letters
        assertEquals(1, forA.size)
        assertEquals(PeerRelay.Kind.RECEIPT, forA[0].kind)
        val owner = PeerRelay.receiptOwner(PeerOutbox.held(outboxA, b.id), forA[0].payload, keyA)
        assertEquals(message.id, owner)
        outboxA = PeerOutbox.markDelivered(outboxA, owner!!)
        courierA.burn(listOf(forA[0].blobId))

        assertEquals(PeerMessage.State.DELIVERED, outboxA.first().state)
        assertEquals(0, PeerOutbox.waiting(outboxA))
        assertTrue(relay.store.isEmpty())
    }

    @Test
    fun `a third phone holding the wrong key sees nothing but padding`() = runBlocking {
        val relay = FakeRelay()
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val c = Phone("phone-c")
        val keyA = pairKey(a, b)
        val relayA = PeerRelay.relayKey(keyA, a.id, b.id)!!
        val courier = PeerRelayCourier(relay) { now }
        val message = letter(a, b, "координати")
        assertTrue(courier.handOff(message, keyA, relayA) is PeerRelayCourier.Handoff.Held)

        // В знає ідентифікатори обох — і цього не досить ні на що.
        val strangerKey = PeerRelay.relayKey(pairKey(c, a), c.id, a.id)!!
        val planC = PeerRelay.plan(c.id, listOf(PeerRelay.Mailbox(a.id, strangerKey)), now, rng = java.util.Random(3), decoySecret = ByteArray(32) { 9 })
        assertTrue(courier.collect(planC) { strangerKey }!!.letters.isEmpty())
        assertEquals(1, relay.store.size)
    }

    @Test
    fun `an unreachable relay leaves the letter waiting on the phone, not failed`() = runBlocking {
        val relay = FakeRelay().apply { reachable = false }
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val keyA = pairKey(a, b)
        val relayA = PeerRelay.relayKey(keyA, a.id, b.id)!!
        val courier = PeerRelayCourier(relay) { now }
        val message = letter(a, b, "слово")
        assertEquals(
            PeerRelayCourier.Handoff.NoRelay,
            courier.handOff(message, keyA, relayA),
        )
        val outbox = PeerOutbox.enqueue(emptyList(), message)
        assertEquals(PeerMessage.State.QUEUED, outbox.first().state)
        assertEquals(1, PeerOutbox.due(outbox, now).size)
    }

    @Test
    fun `a full mailbox is a wait the letter survives`() = runBlocking {
        val relay = FakeRelay().apply { full = true }
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val keyA = pairKey(a, b)
        val relayA = PeerRelay.relayKey(keyA, a.id, b.id)!!
        val courier = PeerRelayCourier(relay) { now }
        assertTrue(courier.handOff(letter(a, b, "слово"), keyA, relayA) is PeerRelayCourier.Handoff.Wait)
        assertTrue(relay.store.isEmpty())
    }

    @Test
    fun `after seven days without a receipt the letter comes back to the queue, never dies`() {
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        var outbox = PeerOutbox.enqueue(emptyList(), letter(a, b, "слово"))
        outbox = PeerOutbox.markHeld(outbox, "m-1", "bm9uY2U", now + PeerRelay.TTL_MS)

        assertTrue(PeerOutbox.expiredHeld(outbox, now + PeerRelay.TTL_MS - 1L).isEmpty())
        val stale = PeerOutbox.expiredHeld(outbox, now + PeerRelay.TTL_MS + 1L)
        assertEquals(listOf("m-1"), stale)

        outbox = PeerOutbox.markExpiredHeld(outbox, "m-1")
        val back = outbox.first()
        assertEquals(PeerMessage.State.QUEUED, back.state)
        assertEquals(0, back.attempts)
        assertEquals("", back.heldNonce)
        assertEquals(1, PeerOutbox.due(outbox, now + PeerRelay.TTL_MS + 1L).size)
    }

    @Test
    fun `a group line with one copy still in the relay does not read as delivered`() {
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val group = PeerGroup.Group(
            id = "g1",
            name = "екіпаж",
            memberIds = listOf(a.id, b.id, "phone-c"),
        )
        val body = PeerGroup.encode(group, "рушаємо")
        val copies = listOf(b.id, "phone-c").mapIndexed { index, to ->
            PeerMessage(
                id = "g-copy-$index",
                fromId = a.id,
                toId = to,
                body = body,
                sentAtMs = now,
                state = if (index == 0) PeerMessage.State.DELIVERED else PeerMessage.State.HELD,
            )
        }
        val fan = PeerGroup.fanState(copies, "g1", now)
        assertEquals(1, fan.delivered)
        assertEquals(1, fan.waiting)
        assertFalse(fan.allDelivered)
        assertEquals(PeerMessage.State.HELD, PeerGroup.collapse(copies, "g1").first().state)
    }

    @Test
    fun `relay bookkeeping never rides inside the sealed letter`() {
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val keyA = pairKey(a, b)
        val keyB = pairKey(b, a)
        val message = letter(a, b, "слово").copy(
            state = PeerMessage.State.HELD,
            heldNonce = "bm9uY2U",
            heldUntilMs = now,
        )
        val sealed = PeerEnvelope.seal(message, keyA)!!
        val opened = PeerEnvelope.open(sealed.frame) { keyB }!!
        assertEquals("", opened.heldNonce)
        assertEquals(0L, opened.heldUntilMs)
    }

    /**
     * Журнал лежить на диску як цей самий JSON. Стан, який не переживає запису й
     * читання, — це стан, що зникає при перезапуску: людина побачила «У СХОВКУ»,
     * а після перезапуску лист знову «ЧЕКАЄ», і квитанції нема до чого привʼязати.
     */
    @Test
    fun `the held state and its nonce survive being written down and read back`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val a = Phone("phone-a")
        val b = Phone("phone-b")
        val outbox = PeerOutbox.markHeld(
            PeerOutbox.enqueue(emptyList(), letter(a, b, "слово")),
            "m-1",
            "bm9uY2U",
            now + PeerRelay.TTL_MS,
        )
        val text = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PeerMessage.serializer()),
            outbox,
        )
        val back = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PeerMessage.serializer()),
            text,
        )
        assertEquals(PeerMessage.State.HELD, back.first().state)
        assertEquals("bm9uY2U", back.first().heldNonce)
        assertEquals(now + PeerRelay.TTL_MS, back.first().heldUntilMs)
    }

    @Test
    fun `a row written before the relay existed still reads`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = """[{"id":"m-0","fromId":"a","toId":"b","body":"слово","sentAtMs":1,"state":"SENT"}]"""
        val back = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PeerMessage.serializer()),
            old,
        )
        assertEquals(PeerMessage.State.SENT, back.first().state)
        assertEquals("", back.first().heldNonce)
    }
}
