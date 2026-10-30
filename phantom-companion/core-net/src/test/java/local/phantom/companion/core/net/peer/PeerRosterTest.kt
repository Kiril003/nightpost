package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity rules. A roster that can show the same person twice, or rename one
 * person into another, is a roster the operator stops believing — and belief
 * is the only thing it is for.
 */
class PeerRosterTest {

    private fun peer(
        id: String,
        name: String = "Марта",
        seen: Long = 0L,
        transport: String? = null,
    ) = PeerIdentity(
        id = id, name = name, kind = PeerIdentity.Kind.PERSON,
        pubKey = "pk-$id", transport = transport, lastSeenMs = seen,
    )

    @Test
    fun `pairing again from a new address updates, never twins`() {
        val one = PeerRoster.upsert(emptyList(), peer("a", transport = "192.168.0.5"))
        val two = PeerRoster.upsert(one, peer("a", transport = "10.0.0.9", seen = 500L))
        assertEquals(1, two.size)
        assertEquals("10.0.0.9", two[0].transport)
        assertEquals(500L, two[0].lastSeenMs)
    }

    @Test
    fun `the name the operator chose survives the wire`() {
        val mine = PeerRoster.rename(
            PeerRoster.upsert(emptyList(), peer("a", name = "Марта")),
            "a",
            "Марта · робота",
        )
        val after = PeerRoster.upsert(mine, peer("a", name = "android-9f2b"))
        assertEquals("Марта · робота", after[0].name)
    }

    @Test
    fun `a peer that never spoke is not live`() {
        assertFalse(peer("a", seen = 0L).isLive(1_000_000L))
    }

    @Test
    fun `presence expires rather than lingering as a green dot`() {
        val now = 1_000_000L
        assertTrue(peer("a", seen = now - 10_000L).isLive(now))
        assertFalse(peer("a", seen = now - PeerRoster.LIVE_WINDOW_MS - 1).isLive(now))
    }

    @Test
    fun `live peers come first, then the most recently heard`() {
        val now = 1_000_000L
        val roster = listOf(
            peer("old", name = "Бо", seen = now - 500_000L),
            peer("live", name = "Яна", seen = now - 5_000L),
            peer("recent", name = "Ада", seen = now - 200_000L),
        )
        assertEquals(
            listOf("live", "recent", "old"),
            PeerRoster.ordered(roster, now).map { it.id },
        )
    }

    @Test
    fun `silent peers fall back to alphabetical, not to insertion luck`() {
        val roster = listOf(peer("b", name = "Яна"), peer("a", name = "Ада"))
        assertEquals(listOf("Ада", "Яна"), PeerRoster.ordered(roster, 1L).map { it.name })
    }

    @Test
    fun `removing takes exactly one`() {
        val roster = listOf(peer("a"), peer("b"))
        assertEquals(listOf("b"), PeerRoster.remove(roster, "a").map { it.id })
        assertEquals(2, PeerRoster.remove(roster, "ghost").size)
    }

    @Test
    fun `a blank name becomes the key, so the row is still readable`() {
        val out = PeerRoster.upsert(emptyList(), peer("a1b2c3d4e5", name = "   "))
        assertEquals("a1b2·c3d4", out[0].name)
    }

    @Test
    fun `a name cannot grow unbounded`() {
        val out = PeerRoster.upsert(emptyList(), peer("a", name = "я".repeat(300)))
        assertEquals(PeerName.MAX, PeerName.length(out[0].name))
        // І обріз не мовчить: «…» на кінці й позначка поруч з іменем. Раніше
        // тут стояла тільки довжина, і тест був зеленим на мовчазному обрізі.
        assertTrue(out[0].name.endsWith(PeerName.ELLIPSIS))
        assertTrue(out[0].nameShortened)
    }

    @Test
    fun `search matches the name in any case`() {
        val roster = listOf(peer("a", name = "Марта"), peer("b", name = "Олег"))
        assertEquals(1, PeerRoster.search(roster, "март").size)
        assertEquals(2, PeerRoster.search(roster, "  ").size)
    }

    @Test
    fun `a short key still yields something to show`() {
        assertEquals("abc", PeerRoster.shortId("abc"))
        assertEquals("?", PeerRoster.shortId("---"))
    }

    @Test
    fun `the paired device is not a person you can message`() {
        val roster = listOf(
            peer("laptop", name = "Ноутбук").copy(kind = PeerIdentity.Kind.DEVICE),
            peer("marta", name = "Марта"),
            peer("pc", name = "ПК").copy(kind = PeerIdentity.Kind.DEVICE),
        )
        assertEquals(listOf("marta"), PeerRoster.people(roster).map { it.id })
    }

    @Test
    fun `seeing a peer marks only that one`() {
        val roster = listOf(peer("a"), peer("b"))
        val out = PeerRoster.sawIt(roster, "a", 777L)
        assertEquals(777L, out.first { it.id == "a" }.lastSeenMs)
        assertEquals(0L, out.first { it.id == "b" }.lastSeenMs)
    }
}
