package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сервер бачить кожен забір і кожну підписку. Якщо вигадані адреси живуть
 * інакше, ніж справжні, він відсіє їх, просто порівнявши запити в часі.
 */
class PeerRelayDecoyTest {

    private val me = Phone("phone-me")
    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val day0 = 20_000L * PeerRelay.EPOCH_MS

    private class Phone(val id: String) {
        val dh = X25519.generateKeypair()
        val dhPubB64: String = java.util.Base64.getEncoder().withoutPadding().encodeToString(dh.publicKey)
    }

    private inner class World(var them: List<Phone>) : PeerRelayPump.World {
        override fun selfId(): String = me.id
        override fun contacts(): List<String> = them.map { it.id }
        override fun pairKey(peerId: String): ByteArray? =
            them.firstOrNull { it.id == peerId }?.let { PeerChannel.derive(me.dh.privateKey, it.dhPubB64, me.id, it.id) }
        override fun signingKey(peerId: String): ByteArray? = null
        override fun outbox(): List<PeerMessage> = emptyList()
        override fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) = Unit
        override fun persist(message: PeerMessage): Boolean = true
        override fun decoySecret(): ByteArray = secret
    }

    private fun asked(world: World, at: Long, round: Long = 0L): PeerRelay.Plan =
        PeerRelayPump(world, PeerRelayCourier(NoRelay) { at }) { at }.prepare(round)!!.plan

    @Test
    fun `two polls of one day ask the very same names, whatever the order`() {
        val world = World(listOf(Phone("b"), Phone("c")))
        val first = asked(world, day0 + 60_000L, round = 0L)
        val later = asked(world, day0 + 13 * 3_600_000L, round = 7L)
        assertEquals(PeerRelay.FETCH_TAGS, first.tags.size)
        assertEquals(PeerRelay.FETCH_TAGS, first.tags.toSet().size)
        assertEquals(first.tags.toSet(), later.tags.toSet())
    }

    @Test
    fun `every name lives three days, the real ones and the made-up ones alike`() {
        val world = World(listOf(Phone("b"), Phone("c"), Phone("d")))
        val days = (0L..5L).map { d -> asked(world, day0 + d * PeerRelay.EPOCH_MS + 1_000L) }
        val seen = HashMap<String, MutableList<Int>>()
        days.forEachIndexed { d, plan -> plan.tags.forEach { seen.getOrPut(it) { mutableListOf() } += d } }
        val real = days.flatMap { it.slots.keys }.toSet()
        // Лише ті, чиє життя цілком у вікні спостереження: перший день — їхній третій.
        val whole = seen.filterValues { it.first() > 0 && it.last() < days.lastIndex }
        val spans = whole.mapValues { (_, d) -> d.size }
        assertTrue(whole.keys.any { it in real })
        assertTrue(whole.keys.any { it !in real })
        assertEquals(setOf(3), spans.values.toSet())
        assertTrue(whole.values.all { d -> d.zipWithNext().all { (x, y) -> y == x + 1 } })
    }

    @Test
    fun `midnight carries two thirds of the names over, not just the real ones`() {
        val world = World(listOf(Phone("b")))
        val today = asked(world, day0 + 1_000L).tags.toSet()
        val tomorrow = asked(world, day0 + PeerRelay.EPOCH_MS + 1_000L).tags.toSet()
        val kept = (today intersect tomorrow).size
        assertTrue("$kept", kept in 42..43)
    }

    @Test
    fun `the set stays sixty-four for any roster that fits`() {
        for (n in 1..10) {
            val world = World(List(n) { Phone("p$it") })
            val plan = asked(world, day0 + 5_000L)
            assertEquals(PeerRelay.FETCH_TAGS, plan.tags.size)
            assertEquals(PeerRelay.FETCH_TAGS, plan.tags.toSet().size)
            assertEquals(n * 6, plan.slots.size)
        }
    }

    @Test
    fun `a friend added mid-day costs the made-up names only their tail`() {
        val b = Phone("b")
        val world = World(listOf(b))
        val before = asked(world, day0 + 1_000L)
        world.them = listOf(b, Phone("c"))
        val after = asked(world, day0 + 2_000L)
        assertTrue(after.tags.containsAll(before.slots.keys))
        assertEquals(6, (before.tags.toSet() - after.tags.toSet()).size)
    }

    private object NoRelay : PeerRelayTransport {
        override suspend fun drop(tag: String, blob: ByteArray) =
            PeerRelayTransport.Dropped(PeerRelayTransport.Drop.NO_CONTACT)
        override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? = null
        override suspend fun burn(ids: List<String>): Boolean = false
    }
}
