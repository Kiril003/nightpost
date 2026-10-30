package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Коло людини чи групи живе в тому ж реєстрі й переживає перезапуск. */
class PeerCirclesTest {

    private class FakeSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private fun person(id: String) = PeerIdentity(id = id, name = id, kind = PeerIdentity.Kind.PERSON, pubKey = "pk_$id")

    @Test
    fun `коло переживає перезапуск`() {
        val slot = FakeSlot()
        val store = PeerRosterStore(slot)
        store.upsert(person("a"))
        assertTrue(store.setCircle("a", "friends"))
        assertTrue(store.setCircle("grp_1", "work"))

        val again = PeerRosterStore(slot)
        assertEquals("friends", again.circleOf("a"))
        assertEquals("work", again.circleOf("grp_1"))
        assertEquals(PeerCircle.NONE, again.circleOf("b"))
    }

    @Test
    fun `чуже слово не стає колом, а «без кола» знімає його`() {
        val store = PeerRosterStore(FakeSlot())
        assertFalse(store.setCircle("a", "enemies"))
        assertEquals(PeerCircle.NONE, store.circleOf("a"))

        store.setCircle("a", "close")
        assertTrue(store.setCircle("a", PeerCircle.NONE))
        assertEquals(PeerCircle.NONE, store.circleOf("a"))
        assertFalse(store.circles.value.containsKey("a"))
    }

    @Test
    fun `прибрана людина забирає й своє коло`() {
        val slot = FakeSlot()
        val store = PeerRosterStore(slot)
        store.upsert(person("a"))
        store.setCircle("a", "personal")
        store.remove("a")
        assertEquals(PeerCircle.NONE, PeerRosterStore(slot).circleOf("a"))
    }

    @Test
    fun `порядок кіл — як у смузі Сьогодні`() {
        assertEquals(listOf("personal", "close", "friends", "social", "work"), PeerCircle.ALL)
    }
}
