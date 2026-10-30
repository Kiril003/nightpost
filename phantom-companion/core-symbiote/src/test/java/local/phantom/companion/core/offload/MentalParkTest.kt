package local.phantom.companion.core.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentalParkTest {

    @Test
    fun `add then ripest returns the item`() {
        val p = MentalPark(clock = { 1_000L })
        p.add("read paper")
        val r = p.ripestLive(1)
        assertEquals(1, r.size)
        assertEquals("read paper", r.single().text)
    }

    @Test
    fun `ripest ordered descending`() {
        val p = MentalPark()
        val a = p.add("a")
        val b = p.add("b")
        p.setRipeness(a.id, 30)
        p.setRipeness(b.id, 70)
        val r = p.ripestLive(2)
        assertEquals(b.id, r.first().id)
    }

    @Test
    fun `archive removes from live list`() {
        val p = MentalPark()
        val a = p.add("dump")
        p.archive(a.id)
        assertTrue(p.ripestLive(10).isEmpty())
    }

    @Test
    fun `forget keeps tombstone but hides from live`() {
        val p = MentalPark()
        val a = p.add("painful memory")
        p.forget(a.id)
        assertTrue(p.ripestLive(10).isEmpty())
        // Still in items.value as tombstone.
        assertEquals(1, p.items.value.size)
        assertEquals(MentalParkItem.Status.FORGOTTEN, p.items.value.first().status)
    }

    @Test
    fun `ripeness clamps to 0-100`() {
        val p = MentalPark()
        val a = p.add("x")
        p.setRipeness(a.id, 9999)
        assertEquals(100, p.items.value.first().ripeness)
        p.setRipeness(a.id, -10)
        assertEquals(0, p.items.value.first().ripeness)
    }
}
