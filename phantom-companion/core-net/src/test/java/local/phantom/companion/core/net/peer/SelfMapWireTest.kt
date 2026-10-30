package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelfMapWireTest {

    private val phone = "a1b2c3d4e5f60718"
    private val pc = "0123456789abcdef"
    private fun at(ms: Long, o: String = phone) = SelfMap.Stamp(ms, o)

    @Before fun kinds() = SelfMapWire.register()

    private val ops = listOf(
        SelfMap.Place("home1", at(1_790_000_000_000), false, "Дім", "home", 50.4501234, 30.5234567, "вул. Хрещатик, 1", null),
        SelfMap.Place("old", at(1_790_000_000_001), gone = true),
        SelfMap.Route("r1", at(1_790_000_000_002), false, "На дачу", listOf(50.45 to 30.52, 50.5 to 30.6)),
        SelfMap.Setting("ghost:*", at(1_790_000_000_003), "1790003600000"),
        SelfMap.Setting("battery", at(1_790_000_000_004), null),
        SelfMap.Cell("q:123:456", 1_790_000_000_000, 1_790_000_900_000, 600_000),
    )

    @Test
    fun `кожна операція мапи проходить конверт листа собі й повертається тією самою`() {
        val frame = PeerSelf.encode(PeerSelf.Pack(phone, 1, ops.map(SelfMapWire::toOperation)))
        assertNotNull(frame)
        assertTrue("у d лише цілі", !Regex("\"(lat|lon)_e7\":-?\\d+\\.").containsMatchIn(frame!!))
        val back = PeerSelf.decode(frame)!!.ops.map { SelfMapWire.fromOperation(it) }
        assertEquals(ops, back)
    }

    @Test
    fun `злиття в кодеку — те саме, що в книзі мапи, у будь-якому порядку`() {
        val mine = SelfMap.Place("p", at(2_000, phone), false, "Робота", "work", 50.0, 30.0)
        val pcs = SelfMap.Place("p", at(2_000, pc), false, "Офіс", "work", 50.0, 30.0)
        val older = SelfMap.Place("p", at(1_000, pc), gone = true)
        val c1 = SelfMap.Cell("q:1:1", 100, 200, 50)
        val c2 = SelfMap.Cell("q:1:1", 50, 150, 80)
        val all = listOf(mine, pcs, older, c1, c2).map(SelfMapWire::toOperation)
        val book = SelfMap.applyAll(SelfMap.Book(), listOf(mine, pcs, older, c1, c2))
        for (order in listOf(all, all.reversed(), all + all)) {
            val folded = PeerSelf.fold(order).values.map { SelfMapWire.fromOperation(it)!! }.toSet()
            assertEquals(SelfMap.ops(book).toSet(), folded)
        }
        assertEquals(SelfMap.Cell("q:1:1", 50, 200, 80), book.world["q:1:1"])
    }

    @Test
    fun `зіпсована операція мапи не проходить`() {
        val bad = SelfMapWire.toOperation(ops[0]).let { it.copy(data = kotlinx.serialization.json.JsonObject(it.data + ("lat_e7" to kotlinx.serialization.json.JsonPrimitive(99_0000000L)))) }
        assertNull(SelfMapWire.fromOperation(bad))
    }
}
