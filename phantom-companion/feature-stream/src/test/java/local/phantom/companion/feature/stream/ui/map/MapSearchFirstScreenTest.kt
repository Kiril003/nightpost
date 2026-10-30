package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.poi.PoiEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Перший екран пошуку — те, що оператор бачить ДО першої літери.
 *
 * Ці випадки стережуть два обіцяння, які легко втратити мовчки:
 *  1. якір знаходиться за КАТЕГОРІЄЮ, бо саме її читає `transit.home`;
 *     імʼя локалізоване і ознакою бути не може;
 *  2. полиця категорій лишається вісьмома потрібними, а не сорока вісьмома
 *     в порядку оголошення.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class MapSearchFirstScreenTest {

    @Before
    fun bindText() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    private fun place(
        name: String,
        category: String,
        lat: Double = 50.45,
        lon: Double = 30.52,
    ) = PoiEntity(
        id = name.hashCode().toLong(),
        lat = lat,
        lon = lon,
        name = name,
        category = category,
        subcategory = null,
        tags = emptyMap(),
    )

    private fun anchors(saved: List<PoiEntity>) = searchAnchors(saved, "Дім", "Робота")

    @Test
    fun `обидва якорі стоять і без жодної збереженої мітки`() {
        val a = anchors(emptyList())

        assertEquals(2, a.size)
        assertEquals(listOf(SavedPlaceCategory.HOME, SavedPlaceCategory.WORK), a.map { it.category })
        // Порожній якір — не відсутній: саме через його відсутність «Дім» і
        // був недосяжним із дотику.
        assertTrue("порожній якір зник з першого екрана", a.all { it.place == null })
    }

    @Test
    fun `якір бере збережене місце за категорією`() {
        val a = anchors(
            listOf(
                place("Хата на Оболоні", SavedPlaceCategory.HOME),
                place("Штаб", SavedPlaceCategory.WORK),
            ),
        )

        assertEquals("Хата на Оболоні", a.first { it.category == SavedPlaceCategory.HOME }.place?.name)
        assertEquals("Штаб", a.first { it.category == SavedPlaceCategory.WORK }.place?.name)
    }

    @Test
    fun `мітка з іменем «Дім», але чужою категорією, якорем не стає`() {
        // Той самий контракт, що й у ToolRegistryFactory.resolveHome: спершу
        // категорія. Якби якір ловився по імені, англомовний оператор із
        // міткою «Home» лишився б без дому, а українець із «улюбленим»
        // під назвою «Дім» дістав би чужий якір.
        val a = anchors(listOf(place("Дім", SavedPlaceCategory.FAVOURITE)))

        assertNull(a.first { it.category == SavedPlaceCategory.HOME }.place)
    }

    @Test
    fun `англомовне імʼя не заважає якорю знайтись`() {
        val a = anchors(listOf(place("Home", SavedPlaceCategory.HOME)))

        assertNotNull(a.first { it.category == SavedPlaceCategory.HOME }.place)
    }

    @Test
    fun `полиця категорій — вісім, і ранги без дірок`() {
        val shortlist = MapDiscovery.shortlist

        assertEquals("полиця першого екрана перестала бути восьмиркою", 8, shortlist.size)
        // Ранг унікальний і суцільний: два однакові дали б порядок за
        // оголошенням (тобто випадковий), а дірка — мовчазну втрату рядка.
        assertEquals(
            "ранги полиці розійшлись із 1..8",
            (1..8).toList(),
            shortlist.mapNotNull { it.shelf },
        )
    }

    @Test
    fun `полиця стоїть на рангу, а не на іменах`() {
        // Полиця раніше збиралась пошуком за ярликом — і ярлик мусив
        // збігатись дослівно. Саме так у MapToolsCapsule тихо всохли сім
        // із дванадцяти карток «поруч».
        assertTrue(
            "категорія з полиці не належить каталогу",
            MapDiscovery.shortlist.all { it in MapDiscovery.all },
        )
        assertTrue(
            "полиця перестала бути підмножиною швидкого доступу",
            MapDiscovery.shortlist.all { it.quick },
        )
    }

    @Test
    fun `перші чотири — воєнний побут, а не порядок оголошення`() {
        // MapDiscovery.quick віддає сімнадцять у порядку тексту, першими
        // «Генератори». Полиця існує саме тому, що це не той порядок.
        assertEquals(
            listOf("Кавʼярня", "Супермаркет", "Аптека", "АЗС"),
            MapDiscovery.shortlist.take(4).map { it.label },
        )
    }

    @Test
    fun `у вузол транспорту ведуть рівно дві категорії`() {
        val hubs = MapDiscovery.all.filter { it.transitHub }.map { it.label }

        assertEquals(listOf("Зупинки транспорту", "Метро та вокзали"), hubs)
    }

    @Test
    fun `«Метро (Укриття)» лишається укриттям, а не розкладом`() {
        // Аркуш питав `label.contains("Метро")`, тож дотик по бомбосховищу
        // відкривав розклад тролейбусів. Прапорець замість підрядка.
        val shelter = MapDiscovery.all.first { it.label == "Метро (Укриття)" }

        assertTrue("укриття метро знову веде у вузол транспорту", !shelter.transitHub)
    }
}
