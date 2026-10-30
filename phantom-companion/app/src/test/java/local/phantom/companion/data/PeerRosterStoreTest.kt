package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRosterStoreTest {

    private class FakeSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private fun person(id: String) = PeerIdentity(id = id, name = id, kind = PeerIdentity.Kind.PERSON, pubKey = "pk_$id")

    @Test
    fun `migration of old circles slot maps legacy keys, survives restart, and clears old key`() {
        val slot = FakeSlot()
        slot.write("circles", """{"a":"family","b":"travel","c":"study","d":"work","e":"obsolete"}""")

        val store = PeerRosterStore(slot)
        assertNull(slot.read("circles"))
        assertNotNull(slot.read("circle_sets"))
        assertEquals(setOf("personal"), store.circlesOf("a"))
        assertEquals(setOf("social"), store.circlesOf("b"))
        assertEquals(setOf("social"), store.circlesOf("c"))
        assertEquals(setOf("work"), store.circlesOf("d"))
        assertEquals(emptySet<String>(), store.circlesOf("e"))

        val restarted = PeerRosterStore(slot)
        assertEquals(setOf("personal"), restarted.circlesOf("a"))
        assertEquals(setOf("social"), restarted.circlesOf("b"))
        assertEquals("personal", restarted.circleOf("a"))
    }

    @Test
    fun `addToCircle keeps multiple circles, removeFromCircle keeps the other, setCircle replaces`() {
        val slot = FakeSlot()
        val store = PeerRosterStore(slot)
        store.upsert(person("a"))

        assertTrue(store.addToCircle("a", "work"))
        assertTrue(store.addToCircle("a", "close"))
        assertFalse(store.addToCircle("a", "unknown"))
        assertEquals(setOf("close", "work"), store.circlesOf("a"))
        assertEquals(setOf("a"), store.membersOf("close"))
        assertEquals(setOf("a"), store.membersOf("work"))
        assertEquals("close", store.circleOf("a"))

        val restarted = PeerRosterStore(slot)
        assertEquals(setOf("close", "work"), restarted.circlesOf("a"))

        assertTrue(restarted.removeFromCircle("a", "close"))
        assertEquals(setOf("work"), restarted.circlesOf("a"))
        assertEquals("work", restarted.circleOf("a"))

        assertTrue(restarted.setCircle("a", "personal"))
        assertEquals(setOf("personal"), restarted.circlesOf("a"))

        assertTrue(restarted.setCircle("a", PeerCircle.NONE))
        assertEquals(emptySet<String>(), restarted.circlesOf("a"))
        assertFalse(restarted.memberships.value.containsKey("a"))
    }

    @Test
    fun `createCircle caps at 8, validates length, and deleteCircle drops memberships`() {
        val slot = FakeSlot()
        val store = PeerRosterStore(slot)

        assertNull(store.createCircle("   "))
        assertNull(store.createCircle("x".repeat(33)))

        val created = (1..PeerCircle.MAX_CUSTOM).map { idx ->
            val c = store.createCircle("  Circle $idx  ")
            assertNotNull(c)
            assertTrue(c!!.id.matches(Regex("^u:[0-9a-f]{8}$")))
            assertEquals("Circle $idx", c.name)
            c
        }
        assertNull(store.createCircle("Ninth"))

        val first = created[0]
        val second = created[1]
        assertTrue(store.isCircle(first.id))
        assertTrue(store.addToCircle("p1", second.id))
        assertTrue(store.addToCircle("p1", first.id))
        assertEquals(first.id, store.circleOf("p1"))

        assertFalse(store.deleteCircle("work"))
        assertFalse(store.deleteCircle("u:deadbeef"))
        assertTrue(store.deleteCircle(first.id))
        assertFalse(store.isCircle(first.id))
        assertEquals(setOf(second.id), store.circlesOf("p1"))
        assertEquals(second.id, store.circleOf("p1"))

        assertTrue(store.deleteCircle(second.id))
        assertEquals(emptySet<String>(), store.circlesOf("p1"))
        assertEquals(PeerCircle.NONE, store.circleOf("p1"))
        assertFalse(store.memberships.value.containsKey("p1"))
    }
}
