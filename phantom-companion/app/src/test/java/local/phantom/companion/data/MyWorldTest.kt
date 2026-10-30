package local.phantom.companion.data

import local.phantom.companion.core.net.peer.SelfMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyWorldTest {

    private val t0 = 1_790_000_000_000L

    @Test
    fun `перебування росте лише поки людина в тому самому кварталі без довгих перерв`() {
        var b = MyWorld.Book()
        for (i in 0..30) b = MyWorld.onFix(b, 50.4501, 30.5234, t0 + i * 60_000L)
        val home = b.cells.getValue(MyWorld.keyOf(50.4501, 30.5234))
        assertEquals(30 * 60_000L, home.dwellMs)
        b = MyWorld.onFix(b, 50.4501, 30.5234, t0 + 31 * 60_000L + MyWorld.GAP_MS + 1)
        assertEquals("перерва понад межу — не перебування", 30 * 60_000L, b.cells.getValue(home.key).dwellMs)
    }

    @Test
    fun `підсумок рахує квартали й часті місця, а не виміри`() {
        var b = MyWorld.Book()
        for (i in 0..40) b = MyWorld.onFix(b, 50.4501, 30.5234, t0 + i * 60_000L)
        for (i in 0..5) b = MyWorld.onFix(b, 50.4501 + i * 0.006, 30.5234, t0 + 50 * 60_000L + i * 60_000L)
        b = b.copy(cities = mapOf("d:1:1" to "Київ", "d:1:2" to " київ", "d:2:2" to "Бровари"))
        val s = MyWorld.summary(b, labelOf = { _, _ -> "Дім" })
        assertEquals(b.cells.size, s.quarters)
        assertTrue(s.quarters >= 5)
        assertEquals(2, s.cities)
        assertEquals(1, s.frequent.size)
        assertEquals("Дім", s.frequent.single().label)
        assertEquals(s.quarters, s.boxes.size)
    }

    @Test
    fun `клітинка з іншого мого пристрою нічого не зменшує і повтор нічого не додає`() {
        var b = MyWorld.onFix(MyWorld.Book(), 50.45, 30.52, t0)
        val key = MyWorld.keyOf(50.45, 30.52)
        val other = SelfMap.Cell(key, t0 - 1_000, t0 + 5_000, 90_000)
        b = MyWorld.absorb(MyWorld.absorb(b, other), other)
        assertEquals(SelfMap.Cell(key, t0 - 1_000, t0 + 5_000, 90_000), b.cells.getValue(key))
    }
}
