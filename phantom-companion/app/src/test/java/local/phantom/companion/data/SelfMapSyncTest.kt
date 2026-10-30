package local.phantom.companion.data

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.peer.SelfMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfMapSyncTest {

    private class Memory : SelfMapLedger {
        var book = SelfMap.Book()
        override fun load() = book
        override fun save(book: SelfMap.Book) { this.book = book }
    }

    private class Stores : SelfMapTarget {
        val places = LinkedHashMap<String, SelfMap.Place>()
        val routes = LinkedHashMap<String, SelfMap.Route>()
        val settings = LinkedHashMap<String, String>()
        val cells = ArrayList<SelfMap.Cell>()
        override suspend fun places() = places.values.toList()
        override suspend fun routes() = routes.values.toList()
        override fun settings() = settings.toMap()
        override suspend fun put(op: SelfMap.Place) { if (op.gone) places.remove(op.id) else places[op.id] = op }
        override suspend fun put(op: SelfMap.Route) { if (op.gone) routes.remove(op.id) else routes[op.id] = op }
        override fun put(op: SelfMap.Setting) { if (op.value == null) settings.remove(op.key) else settings[op.key] = op.value!! }
        override fun put(op: SelfMap.Cell) { cells += op }
    }

    private var now = 10_000L
    private val ledger = Memory()
    private val stores = Stores()
    private val phone = SelfMapSync(ledger, stores, { "phone" }, { now })
    private val zero = SelfMap.Stamp(0, "-")

    private fun home(label: String) = SelfMap.Place("p-home", zero, false, label, "home", 50.45, 30.52)

    @Test
    fun a_local_save_becomes_one_operation_and_only_once() = runTest {
        stores.places["p-home"] = home("Дім")
        val ops = phone.collect()
        assertEquals(1, ops.size)
        assertEquals(SelfMap.Stamp(10_000, "phone"), (ops[0] as SelfMap.Place).stamp)
        assertTrue(phone.collect().isEmpty())
    }

    @Test
    fun a_deletion_on_this_device_travels_as_a_tombstone() = runTest {
        stores.places["p-home"] = home("Дім")
        phone.collect()
        stores.places.clear()
        now = 20_000
        val ops = phone.collect()
        assertEquals(listOf(SelfMap.Place("p-home", SelfMap.Stamp(20_000, "phone"), gone = true)), ops)
    }

    @Test
    fun an_older_edit_from_the_pc_does_not_overwrite_my_newer_one() = runTest {
        stores.places["p-home"] = home("Дім")
        now = 30_000
        val mine = phone.take(listOf(home("Хата").copy(stamp = SelfMap.Stamp(20_000, "pc"))))
        assertEquals(1, mine.size)
        assertEquals("Дім", stores.places["p-home"]!!.label)
    }

    @Test
    fun a_newer_edit_from_the_pc_lands_and_does_not_echo_back() = runTest {
        stores.places["p-home"] = home("Дім")
        phone.collect()
        phone.take(
            listOf(
                home("Хата").copy(stamp = SelfMap.Stamp(40_000, "pc")),
                SelfMap.Setting("battery", SelfMap.Stamp(40_000, "pc"), "on"),
                SelfMap.Cell("q:1:2", 1, 2, 3),
            ),
        )
        assertEquals("Хата", stores.places["p-home"]!!.label)
        assertEquals("on", stores.settings["battery"])
        assertEquals(1, stores.cells.size)
        assertTrue(phone.collect().isEmpty())
    }
}
