package local.phantom.companion.core.data.poi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Не дуже подобаються ці іконки під все» — the cap, and which pins earn the
 * room when there is not enough of it.
 */
class PoiDensityTest {

    private fun poi(id: Long, name: String, category: String, sub: String?, lat: Double = 50.45, lon: Double = 30.52) =
        PoiEntity(id = id, lat = lat, lon = lon, name = name, category = category, subcategory = sub, tags = emptyMap())

    @Test
    fun `a district sweep carries fewer pins than a street`() {
        assertTrue(PoiDensity.capForZoom(13.5) < PoiDensity.capForZoom(15.5))
        assertTrue(PoiDensity.capForZoom(15.5) < PoiDensity.capForZoom(17.0))
    }

    @Test
    fun `under the cap nothing is thrown away`() {
        val all = (1..10).map { poi(it.toLong(), "місце $it", "shop", "clothes") }
        assertEquals(10, PoiDensity.rank(all, 50.45, 30.52, cap = 25).size)
    }

    @Test
    fun `over the cap the fuel station survives and the boutique does not`() {
        val shops = (1..40).map { poi(it.toLong(), "крамниця $it", "shop", "clothes") }
        val fuel = poi(100, "WOG", "amenity", "fuel")
        val pharmacy = poi(101, "Аптека", "amenity", "pharmacy")
        val station = poi(102, "Вокзал", "railway", "station")
        val kept = PoiDensity.rank(shops + fuel + pharmacy + station, 50.45, 30.52, cap = 10)
        val ids = kept.map { it.id }
        assertEquals(10, kept.size)
        assertTrue(ids.containsAll(listOf(100L, 101L, 102L)))
        // Аптека веде: ярус життя і здоровʼя старший за пальне й вокзал. Раніше
        // тут стояло `setOf(100, 102)` — АЗС і вокзал ПОПЕРЕДУ аптеки, — і це
        // був той самий перевернутий порядок, яким `salience` викидала воду.
        assertEquals(101L, ids.first())
        assertEquals(setOf(100L, 102L), ids.drop(1).take(2).toSet())
    }

    @Test
    fun `within one tier the nearer place wins`() {
        val near = poi(1, "Аптека поруч", "amenity", "pharmacy", lat = 50.4501, lon = 30.5201)
        val far = poi(2, "Аптека далеко", "amenity", "pharmacy", lat = 50.4800, lon = 30.5800)
        val kept = PoiDensity.rank(listOf(far, near), 50.45, 30.52, cap = 1)
        assertEquals(listOf(1L), kept.map { it.id })
    }

    /**
     * Був `a bus shelter never outranks a bank` — зупинка мусила поступатись
     * банку. Обидва тепер ОПОРНІ, тобто рівні, і різницю між ними вирішує
     * близькість. Твердження перевернуто в те, що справді важить: і зупинка, і
     * банк стоять попереду кавʼярні.
     */
    @Test
    fun `a stop and a bank both outrank a cafe`() {
        val cafe = PoiDensity.salience(poi(3, "Кава", "amenity", "cafe"))
        assertTrue(PoiDensity.salience(poi(1, "Ощадбанк", "amenity", "bank")) < cafe)
        assertTrue(PoiDensity.salience(poi(2, "Зупинка", "highway", "bus_stop")) < cafe)
    }

    /**
     * ЦЕ Й Є ХВИЛЯ. Заміряно на національному корпусі: 13 395 критичних рядків
     * (32% усіх критичних) сиділи в останньому відрі старої `salience`, з них
     * 12 193 — вода. Джерело не має назви за побудовою, і саме воно потрібне
     * першим.
     */
    @Test
    fun `a nameless spring outranks a named cafe`() {
        assertTrue(
            PoiDensity.salience(poi(1, "Джерело", "natural", "spring")) <
                PoiDensity.salience(poi(2, "Кава з собою", "amenity", "cafe")),
        )
        assertTrue(
            PoiDensity.salience(poi(3, "Криниця", "man_made", "water_well")) <
                PoiDensity.salience(poi(4, "Готель", "tourism", "hotel")),
        )
    }

    /**
     * Стеля набирається КОЛОМ по родах: сто аптек не мають права зʼїсти кадр,
     * у якому є єдине джерело. До цієї зміни саме це й ставалось — «відсортуй
     * і візьми перші N» над важким хвостом завжди повертає найчастіший рід.
     */
    @Test
    fun `one spring survives a hundred pharmacies`() {
        val pharmacies = (1..100).map {
            poi(it.toLong(), "Аптека $it", "amenity", "pharmacy", lat = 50.4500 + it * 0.00001)
        }
        val spring = poi(500, "Джерело", "natural", "spring", lat = 50.4520, lon = 30.5220)
        val kept = PoiDensity.rank(pharmacies + spring, 50.45, 30.52, cap = 25)
        assertEquals(25, kept.size)
        assertTrue("джерело зникло під аптеками", kept.any { it.id == 500L })
    }

    @Test
    fun `the frame answers what is here, not twenty of one thing`() {
        val rows = buildList {
            repeat(60) { add(poi(it.toLong(), "Аптека $it", "amenity", "pharmacy", lat = 50.4500 + it * 0.00001)) }
            repeat(60) { add(poi(100L + it, "АЗС $it", "amenity", "fuel", lat = 50.4510 + it * 0.00001)) }
            add(poi(900, "Лікарня", "amenity", "hospital", lat = 50.4530))
            add(poi(901, "Криниця", "man_made", "water_well", lat = 50.4531))
            add(poi(902, "Відділення поліції", "amenity", "police", lat = 50.4532))
        }
        val kept = PoiDensity.rank(rows, 50.45, 30.52, cap = 10)
        val kinds = kept.map { PlaceTiers.kindOf(it) }.toSet()
        assertEquals(10, kept.size)
        assertTrue("у кадрі менше пʼяти родів: $kinds", kinds.size >= 5)
        assertTrue(kept.any { it.id == 900L })
        assertTrue(kept.any { it.id == 901L })
        assertTrue(kept.any { it.id == 902L })
    }

    /**
     * Брід має ВЛАСНИЙ шар, тож у суцільному його немає — і слот під стелею
     * йому теж не належить. Доти оверлей викидав брід уже ПІСЛЯ стелі: слот
     * витрачено, піна не намальовано.
     */
    @Test
    fun `a ford never spends a slot it will not use`() {
        val fords = (1..40).map { poi(it.toLong(), "Брід", "ford", "yes", lat = 50.4500 + it * 0.00001) }
        val well = poi(500, "Криниця", "man_made", "water_well")
        val kept = PoiDensity.rank(fords + well, 50.45, 30.52, cap = 10)
        assertEquals(listOf(500L), kept.map { it.id })
    }

    @Test
    fun `nameless rows and Unknown never reach the map`() {
        val rows = listOf(
            poi(1, "", "amenity", "cafe"),
            poi(2, "Unknown", "amenity", "cafe"),
            poi(3, "Кава", "amenity", "cafe"),
        )
        assertEquals(listOf(3L), PoiDensity.rank(rows, 50.45, 30.52, cap = 25).map { it.id })
    }

    @Test
    fun `the same OSM id from two sources is one pin`() {
        val fromPack = poi(7, "Сільпо", "shop", "supermarket")
        val fromLive = poi(7, "Сільпо", "shop", "supermarket")
        assertEquals(1, PoiDensity.rank(listOf(fromPack, fromLive), 50.45, 30.52, cap = 25).size)
    }

    /**
     * Порядок ВИДАЧІ лишається «доречніше спершу»: на нього спирається
     * `POIOverlay.labelCarriers`, обираючи носія підпису серед однойменних
     * близнюків. Коло набирає СКЛАД кадру, а не його порядок.
     */
    @Test
    fun `the list still comes out most relevant first`() {
        val rows = buildList {
            repeat(30) { add(poi(it.toLong(), "Кава $it", "amenity", "cafe", lat = 50.4500 + it * 0.00001)) }
            add(poi(900, "Аптека", "amenity", "pharmacy", lat = 50.4540))
            add(poi(901, "АЗС", "amenity", "fuel", lat = 50.4541))
        }
        val kept = PoiDensity.rank(rows, 50.45, 30.52, cap = 12)
        assertEquals(900L, kept.first().id)
        assertEquals(901L, kept[1].id)
    }

    @Test
    fun `a canopy calling itself a shelter is not critical`() {
        val canopy = PoiEntity(
            id = 1, lat = 50.45, lon = 30.52, name = "Альтанка",
            category = "amenity", subcategory = "shelter",
            tags = mapOf("amenity" to "shelter", "shelter_type" to "gazebo"),
        )
        val real = PoiEntity(
            id = 2, lat = 50.45, lon = 30.52, name = "Сховище",
            category = "amenity", subcategory = "shelter",
            tags = mapOf("amenity" to "shelter", "civil_shelter" to "yes"),
        )
        assertTrue(PoiDensity.salience(real) < PoiDensity.salience(canopy))
    }
}
