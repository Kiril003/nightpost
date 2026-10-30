package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfMapTest {

    private val vectors: JsonObject =
        SelfMap.parse(javaClass.classLoader!!.getResource("self_map_vectors.json")!!.readText()).jsonObject

    private fun ops(arr: JsonArray): List<SelfMap.Op> = arr.map { assertNotNull(it.toString(), SelfMap.decode(it.jsonObject)); SelfMap.decode(it.jsonObject)!! }

    private fun expected(o: JsonObject): SelfMap.Book =
        SelfMap.applyAll(SelfMap.Book(), listOf("places", "routes", "settings", "world").flatMap { ops(o[it]!!.jsonArray) })

    @Test
    fun every_order_of_both_devices_lands_on_the_vector() {
        vectors["cases"]!!.jsonArray.forEach { c ->
            val case = c.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val a = ops(case["a"]!!.jsonArray)
            val b = ops(case["b"]!!.jsonArray)
            val want = expected(case["expect"]!!.jsonObject)
            val bookA = SelfMap.applyAll(SelfMap.Book(), a)
            val bookB = SelfMap.applyAll(SelfMap.Book(), b)
            assertEquals(name, want, SelfMap.merge(bookA, bookB))
            assertEquals(name, want, SelfMap.merge(bookB, bookA))
            assertEquals(name, want, SelfMap.applyAll(SelfMap.Book(), (a + b).reversed()))
            assertEquals("$name — повтор", want, SelfMap.merge(SelfMap.merge(bookA, bookB), bookB))
        }
    }

    @Test
    fun snapshot_round_trips_through_the_wire_form() {
        vectors["cases"]!!.jsonArray.forEach { c ->
            val want = expected(c.jsonObject["expect"]!!.jsonObject)
            val again = SelfMap.parse(SelfMap.snapshot(want).toString()).jsonObject
            assertEquals(want, expected(again))
        }
    }

    @Test
    fun the_boundary_refuses_what_the_vector_refuses() {
        vectors["refused"]!!.jsonArray.forEach { assertNull(it.toString(), SelfMap.decode(it.jsonObject)) }
    }

    @Test
    fun an_older_edit_never_wins() {
        val new = SelfMap.Place("p", SelfMap.Stamp(2, "pc"), false, "Дім", "home", 50.0, 30.0)
        val old = new.copy(stamp = SelfMap.Stamp(1, "phone"), label = "Хата")
        val book = SelfMap.apply(SelfMap.Book(), new)
        assertFalse(SelfMap.wins(book, old))
        assertTrue(SelfMap.wins(book, new.copy(stamp = SelfMap.Stamp(2, "phone2"))))
        assertEquals("Дім", SelfMap.apply(book, old).places["p"]!!.label)
    }
}
