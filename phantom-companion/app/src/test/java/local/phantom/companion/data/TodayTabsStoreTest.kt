package local.phantom.companion.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Порядок і сховані сторінки «Сьогодні» — свої для кожної особи й переживають перезапуск. */
class TodayTabsStoreTest {

    private class FakeSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    @Test
    fun `порядок і сховане переживають перезапуск, і в кожної особи своє`() {
        val slot = FakeSlot()
        TodayTabsStore(slot).put("me", TodayTabsStore.Tabs(listOf("c:work", "c:close", "posts"), listOf("c:social")))

        val again = TodayTabsStore(slot)
        assertEquals(TodayTabsStore.Tabs(listOf("c:work", "c:close", "posts"), listOf("c:social")), again.get("me"))
        assertEquals(TodayTabsStore.Tabs(emptyList(), emptyList()), again.get("someone-else"))
    }

    @Test
    fun `чуже й «Усі» відкидаються, повтори зникають`() {
        val store = TodayTabsStore(FakeSlot())
        val kept = store.put("me", TodayTabsStore.Tabs(listOf("all", "c:work", "c:work", "f:x", "c:enemies", "posts"), listOf("all", "c:personal", "zzz")))
        assertEquals(TodayTabsStore.Tabs(listOf("c:work", "posts"), listOf("c:personal")), kept)
        assertEquals(kept, store.get("me"))
    }

    @Test
    fun `старі ключі мігрують при читанні`() {
        val slot = FakeSlot()
        slot.write("tabs:me", """{"order":["c:family","c:study","c:travel","c:work"],"hidden":["c:study"]}""")
        val got = TodayTabsStore(slot).get("me")
        assertEquals(TodayTabsStore.Tabs(listOf("c:personal", "c:social", "c:work"), listOf("c:social")), got)
    }
}
