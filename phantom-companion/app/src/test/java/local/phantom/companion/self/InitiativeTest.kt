package local.phantom.companion.self

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import local.phantom.companion.self.Initiative.Decision
import local.phantom.companion.self.Initiative.Kind
import local.phantom.companion.self.Initiative.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitiativeTest {

    private val store = MemoryInitiativeStore()
    private val ini = Initiative(store) { ZoneId.of("UTC") }

    private fun at(day: Int, hour: Int, min: Int = 0) =
        LocalDateTime.of(2026, 9, day, hour, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun lesson(key: String, due: Long, id: String = key) =
        Initiative.Visit(id, Kind.LESSON, key, "Повторимо цикли?", "Чому я прийшов: учора цикли хитались.", due)

    private fun decide(ms: Long, road: Boolean = false) = ini.decide(Moment(ms, road))

    @Test
    fun `every visit carries why it came`() {
        ini.offer(lesson("a", at(27, 10)))
        val come = decide(at(27, 10)) as Decision.Come
        assertTrue(come.visit.why.startsWith("Чому я прийшов"))
    }

    @Test
    fun `quiet at night, comes in the morning`() {
        ini.offer(lesson("a", at(27, 23)))
        assertEquals(Decision.Wait(at(28, 8)), decide(at(27, 23)))
        assertEquals(Decision.Wait(at(28, 8)), decide(at(28, 3)))
        assertTrue(decide(at(28, 8)) is Decision.Come)
    }

    @Test
    fun `quiet while the person is on the road`() {
        ini.offer(lesson("a", at(27, 12)))
        assertEquals(Decision.Wait(at(27, 12, 20)), decide(at(27, 12), road = true))
        assertTrue(decide(at(27, 12, 20), road = false) is Decision.Come)
    }

    @Test
    fun `daily budget grows when answered and shrinks when ignored`() {
        assertEquals(2, store.s.budget)
        ini.offer(lesson("a", at(27, 9)))
        ini.offer(lesson("b", at(27, 9), "b"))
        ini.offer(lesson("c", at(27, 9), "c"))
        assertTrue(decide(at(27, 10)) is Decision.Come)
        assertTrue(decide(at(27, 10)) is Decision.Come)
        assertEquals(Decision.Wait(at(28, 8)), decide(at(27, 11)))

        ini.answered()
        assertEquals(3, store.s.budget)
        assertTrue(decide(at(27, 12)) is Decision.Come)

        ini.ignored("c")
        assertEquals(2, store.s.budget)
        repeat(5) { ini.offer(lesson("x$it", 0, "x$it")); decide(at(28, 9 + it)); ini.ignored("x$it") }
        assertEquals(Initiative.MIN_BUDGET, store.s.budget)
        repeat(9) { ini.offer(lesson("y$it", 0, "y$it")); decide(at(29, 9)); ini.answered() }
        assertEquals(Initiative.MAX_BUDGET, store.s.budget)
    }

    @Test
    fun `unanswered for a day counts as ignored`() {
        ini.offer(lesson("a", at(27, 9)))
        decide(at(27, 9))
        decide(at(29, 9))
        assertEquals(1, store.s.budget)
        assertTrue(store.s.waiting.isEmpty())
    }

    @Test
    fun `not now puts the same visit a few hours later`() {
        ini.offer(lesson("a", at(27, 10)))
        val v = (decide(at(27, 10)) as Decision.Come).visit
        ini.notNow(v, at(27, 10))
        assertEquals(Decision.Wait(at(27, 13)), decide(at(27, 11)))
        assertEquals(v.id, (decide(at(27, 13)) as Decision.Come).visit.id)
        assertEquals(2, store.s.budget)
    }

    @Test
    fun `dont do this turns the kind off, and a new one is refused`() {
        ini.offer(lesson("a", at(27, 10)))
        decide(at(27, 10))
        ini.dontDoThis(Kind.LESSON)
        ini.offer(lesson("b", at(27, 11), "b"))
        assertEquals(Decision.Nothing, decide(at(27, 12)))
        ini.unmute(Kind.LESSON)
        ini.offer(lesson("c", at(27, 12), "c"))
        assertTrue(decide(at(27, 12)) is Decision.Come)
    }

    @Test
    fun `a reminder the person set comes on time, night and budget or not`() {
        store.s = store.s.copy(budget = 1, usedToday = 5, day = java.time.LocalDate.of(2026, 9, 27).toEpochDay())
        ini.dontDoThis(Kind.REMINDER)
        ini.offer(Initiative.Visit("r", Kind.REMINDER, "r", "Подзвонити мамі", "Чому я прийшов: ти сам просив нагадати.", 0))
        assertEquals("r", (decide(at(27, 23, 30), road = true) as Decision.Come).visit.id)
        assertEquals(5, store.s.usedToday)
    }

    @Test
    fun `one visit per key - a new lesson replaces the old`() {
        ini.offer(lesson("lesson.csharp", at(27, 10), "old"))
        ini.offer(lesson("lesson.csharp", at(27, 11), "new"))
        assertEquals(listOf("new"), store.s.queue.map { it.id })
        ini.withdraw("lesson.csharp")
        assertEquals(Decision.Nothing, decide(at(27, 12)))
    }
}
