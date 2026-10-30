package local.phantom.companion.core.geo.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Збережена земля мусить зватися землею.
 *
 * На пристрої власника 16.08 лежав 61 пакет, і кожен звався «регіон · дд.мм
 * гг:хх» — годиною натискання кнопки. Серед них «регіон · 12.07 16:16» тричі
 * поспіль: три написи, однакові до символу, за якими три різні клапті землі.
 * Імʼя, яке не розрізняє, не є імʼям.
 */
class PlaceNamingTest {

    /** Приблизно 12 × 9 км навколо Кукільні на Житомирщині. */
    private val north = 50.0250
    private val south = 49.9440
    private val east = 28.8730
    private val west = 28.7050

    @Test
    fun `a saved box is named by the smallest place that contains it`() {
        val name = PlaceNaming.forBox(north, east, south, west)
        assertFalse("імʼя знову про годинник, а не про землю", name.contains("регіон"))
        assertTrue("немає розміру: «Київ» без нього не відрізнити від «Київ і сто кілометрів»",
            name.contains("км"))
        // Найменше з тих, що містять центр — не країна: «Україна · 12 × 9 км»
        // не каже нічого про те, куди саме дивишся.
        assertFalse("назвало країною замість місцевості", name.startsWith("Україна"))
    }

    @Test
    fun `two different patches of land never get the same name`() {
        val kyiv = PlaceNaming.forBox(50.52, 30.62, 50.38, 30.42)
        val lviv = PlaceNaming.forBox(49.90, 24.10, 49.78, 23.92)
        assertTrue("Київ і Львів отримали однакове імʼя: $kyiv", kyiv != lviv)
    }

    @Test
    fun `outside the gazetteer it says coordinates, never an invented place`() {
        // Середина Атлантики: таблиця місць українська, і вигадувати нема з чого.
        val name = PlaceNaming.forBox(30.10, -40.10, 29.90, -40.30)
        assertTrue("вигадало місце там, де його не знає: $name", name.startsWith("30."))
        assertTrue(name.contains("км"))
    }

    @Test
    fun `the span is stated in sides, not in area`() {
        // Сторони відповідають на «чи влізе це в телефон» одразу; площу треба
        // спершу уявити.
        assertEquals("12 × 9 км", PlaceNaming.spanText(50.0250, 28.8730, 49.9440, 28.7050))
    }

    @Test
    fun `мета маршруту не стає областю`() {
        // Довідник знає не кожне село. Для точки під Кукільнею найменшим
        // ВІДОМИМ місцем є Житомирська область, і підписати нею двадцятиметрову
        // прогулянку означало б збрехати упевненим тоном.
        val lat = 49.97368
        val lon = 28.79453
        val goal = PlaceNaming.settlementAt(lat, lon)
        // Головний інваріант один: що б не віддав довідник, метою маршруту
        // ніколи не стає область чи район. Порожньо — теж правильна відповідь:
        // тоді смуга покаже координати, і це чесно.
        if (goal != null) {
            assertFalse("мета «$goal» — це область, а не населений пункт", goal.contains("област", ignoreCase = true))
            assertFalse("мета «$goal» — це район, а не населений пункт", goal.contains("район", ignoreCase = true))
        }
    }

    @Test
    fun `у відомому місті мета називається містом`() {
        val kyiv = PlaceNaming.settlementAt(50.4501, 30.5234)
        assertEquals("Київ у довіднику є — мета мусить називатись ним", "Київ", kyiv)
    }
}
