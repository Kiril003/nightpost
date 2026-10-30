package local.phantom.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CirclePolicyTest {

    private class FakeSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private class FakeWhere : CircleWhere {
        val active = LinkedHashMap<String, Long>()
        var grantCalls = 0
        var revokeCalls = 0

        override fun granted(id: String): Boolean = id in active

        override fun grant(id: String, untilMs: Long): Boolean {
            grantCalls++
            if (id == "refuse") return false
            active[id] = untilMs
            return true
        }

        override fun revoke(id: String) {
            revokeCalls++
            active.remove(id)
        }
    }

    @Test
    fun `adding to close grants once, work does nothing, moving to work revokes`() {
        val slot = FakeSlot()
        val where = FakeWhere()
        val store = PeerRosterStore(slot)
        store.bindWhere(where)

        store.addToCircle("a", "work")
        assertFalse(where.granted("a"))
        assertEquals(0, where.grantCalls)

        store.addToCircle("a", "close")
        assertTrue(where.granted("a"))
        assertEquals(0L, where.active["a"])
        assertEquals(1, where.grantCalls)

        store.addToCircle("a", "personal")
        assertEquals(1, where.grantCalls)

        store.removeFromCircle("a", "close")
        assertTrue(where.granted("a"))
        assertEquals(0, where.revokeCalls)

        store.setCircle("a", "work")
        assertFalse(where.granted("a"))
        assertEquals(1, where.revokeCalls)
    }

    @Test
    fun `hand grant survives leaving close`() {
        val slot = FakeSlot()
        val where = FakeWhere()
        where.active["hand"] = 0L

        val store = PeerRosterStore(slot)
        store.bindWhere(where)

        store.addToCircle("hand", "close")
        assertEquals(0, where.grantCalls)

        store.removeFromCircle("hand", "close")
        assertTrue(where.granted("hand"))
        assertEquals(0, where.revokeCalls)
    }

    @Test
    fun `policy remembers circle grants across restart and revokes on removal`() {
        val slot = FakeSlot()
        val where1 = FakeWhere()
        val store1 = PeerRosterStore(slot)
        store1.bindWhere(where1)
        store1.addToCircle("a", "close")
        assertTrue(where1.granted("a"))

        val where2 = FakeWhere().apply { active["a"] = 0L }
        val store2 = PeerRosterStore(slot)
        store2.bindWhere(where2)
        store2.remove("a")
        assertFalse(where2.granted("a"))
        assertEquals(1, where2.revokeCalls)
    }

    @Test
    fun `shareCircle grants members for future untilMs and rejects past`() {
        val slot = FakeSlot()
        val where = FakeWhere()
        val store = PeerRosterStore(slot)
        store.addToCircle("a", "work")
        store.addToCircle("b", "work")
        store.addToCircle("refuse", "work")

        assertEquals(0, store.shareCircle("work", System.currentTimeMillis() + 60_000L))

        store.bindWhere(where)
        val future = System.currentTimeMillis() + 60_000L
        assertEquals(2, store.shareCircle("work", future))
        assertEquals(future, where.active["a"])
        assertEquals(future, where.active["b"])

        assertEquals(0, store.shareCircle("work", System.currentTimeMillis() - 1_000L))
    }
}
