package local.phantom.companion.core.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Помилка тут — це лист, надісланий не тій людині. Тому «схоже на» ніколи не
 * стає «точно вона»: двох однакових повертаємо оператору, а не вгадуємо.
 */
class PeopleMatchTest {

    private fun p(id: String, name: String) =
        ReachablePerson(id = id, name = name, live = false, lastSeenMs = 0L, verified = false)

    private val roster = listOf(
        p("1", "Марта"),
        p("2", "Тарас"),
        p("3", "Олег Ткаченко"),
        p("4", "олег "),
    )

    @Test
    fun `точне імʼя знаходить людину`() {
        val r = PeopleMatch.one(roster, "Марта")
        assertTrue(r is PeopleMatch.One && r.person.id == "1")
    }

    @Test
    fun `регістр і зайві пробіли не заважають`() {
        val r = PeopleMatch.one(roster, "  тАрАс ")
        assertTrue(r is PeopleMatch.One && r.person.id == "2")
    }

    @Test
    fun `частина імені годиться, поки вона одна`() {
        val r = PeopleMatch.one(roster, "Ткаченко")
        assertTrue(r is PeopleMatch.One && r.person.id == "3")
    }

    @Test
    fun `двох Олегів не вгадуємо, а віддаємо оператору`() {
        val two = listOf(p("1", "Олег Ткаченко"), p("2", "Олег Пилипенко"))
        val r = PeopleMatch.one(two, "олег")
        assertTrue("мало бути Many, а було $r", r is PeopleMatch.Many)
        assertEquals(2, (r as PeopleMatch.Many).names.size)
    }

    /**
     * Точний збіг виграє свідомо: інструмент лише ГОТУЄ лист, надсилає оператор,
     * тож хибний вибір видно на екрані до того, як щось пішло.
     */
    @Test
    fun `точний збіг сильніший за частковий`() {
        val two = listOf(p("1", "Оля"), p("2", "Оля Петрівна"))
        val r = PeopleMatch.one(two, "Оля")
        assertTrue("точне «Оля» мало виграти, а було $r", r is PeopleMatch.One)
        assertEquals("1", (r as PeopleMatch.One).person.id)
    }

    @Test
    fun `невідоме імʼя — це None, а не перша ліпша людина`() {
        assertTrue(PeopleMatch.one(roster, "Богдан") is PeopleMatch.None)
        assertTrue(PeopleMatch.one(roster, "   ") is PeopleMatch.None)
        assertTrue(PeopleMatch.one(emptyList(), "Марта") is PeopleMatch.None)
    }
}
