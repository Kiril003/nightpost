package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.poi.PoiEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Нова Пошта Нова Пошта» — гонтлет-1 §5.
 *
 * Це не склеювання рядків: це два законні обʼєкти OSM (відділення і його ж
 * поштомат) за чотири метри один від одного, обидва з однаковим `name`. Пін
 * лишається в обох — вони справді різні речі, і обидва мають бути під пальцем;
 * мовчить лише другий підпис.
 *
 * Міряється [POIOverlay.labelPlan], а не сам GeoJSON: серіалізація йде через
 * `org.json`, а в юніт-тесті це заглушка з android.jar. Рішення про підпис
 * ухвалює саме план, і саме він стоїть на шляху [POIOverlay.featureCollection].
 */
class PoiLabelTwinTest {

    private fun poi(
        id: Long,
        name: String,
        lat: Double,
        lon: Double,
        tags: Map<String, String> = emptyMap(),
    ) = PoiEntity(
        id = id,
        lat = lat,
        lon = lon,
        name = name,
        category = "amenity",
        subcategory = "post_office",
        tags = tags,
    )

    private fun labels(pois: List<PoiEntity>, latin: Boolean = false) =
        POIOverlay.labelPlan(pois, latin).map { it.second }

    /** Справжня пара з витягу пака UA: 50.383538/30.479127 і 50.383558/30.479050. */
    @Test
    fun `однойменні сусіди за чотири метри дають один підпис`() {
        val pois = listOf(
            poi(11405086891, "Нова Пошта", 50.383538, 30.479127),
            poi(12995936963, "Нова Пошта", 50.383558, 30.479050),
        )
        val plan = POIOverlay.labelPlan(pois, latin = false)

        assertEquals("обидва піни лишаються на мапі", 2, plan.size)
        assertEquals(
            "перший у ранжованому списку несе підпис, другий мовчить",
            listOf("Нова Пошта", ""),
            plan.map { it.second },
        )
    }

    /** Імʼя нікуди не дівається: ним живуть досʼє і пошук. */
    @Test
    fun `мовчазний близнюк лишається справжнім місцем`() {
        val pois = listOf(
            poi(1, "Нова Пошта", 50.383538, 30.479127),
            poi(2, "Нова Пошта", 50.383558, 30.479050),
        )
        POIOverlay.labelPlan(pois, latin = false).forEach { (place, _) ->
            assertEquals("Нова Пошта", place.name)
        }
    }

    @Test
    fun `різні імена поруч лишаються обидва`() {
        val pois = listOf(
            poi(1, "Нова Пошта №64", 50.383522, 30.479087),
            poi(2, "Нова Пошта", 50.383538, 30.479127),
        )
        assertEquals(listOf("Нова Пошта №64", "Нова Пошта"), labels(pois))
    }

    @Test
    fun `однойменні за квартал одне одного не глушать`() {
        val pois = listOf(
            poi(1, "ПриватБанк", 50.4500, 30.5230),
            // ~110 м на північ — це вже два різні орієнтири для ока.
            poi(2, "ПриватБанк", 50.4510, 30.5230),
        )
        assertEquals(listOf("ПриватБанк", "ПриватБанк"), labels(pois))
    }

    /** Сто сім ПриватБанків у пакеті — рідкий ланцюжок має лишитись цілим. */
    @Test
    fun `ланцюжок однойменних через тридцять метрів не гасне`() {
        val pois = (0 until 12).map { i ->
            poi(i.toLong(), "Фора", 50.4500 + i * 0.00027, 30.5230)
        }
        assertTrue(labels(pois).all { it == "Фора" })
    }

    /**
     * Кадр стенда 03.09: «Independence Square» пʼять разів поспіль на одному екрані.
     * Це один майдан, який OSM тримає кількома обʼєктами РІЗНОГО роду — площа,
     * станція метро, зупинки, — і кожен приносив свій підпис.
     */
    @Test
    fun `одне місце, розбите на роди, підписується один раз`() {
        val pois = listOf(
            poi(1, "Майдан Незалежності", 50.4500, 30.5230).copy(category = "place"),
            poi(2, "Майдан Незалежності", 50.4508, 30.5230).copy(category = "railway"),
            poi(3, "Майдан Незалежності", 50.4512, 30.5236).copy(category = "highway"),
            poi(4, "Майдан Незалежності", 50.4497, 30.5241).copy(category = "amenity"),
        )
        assertEquals(1, labels(pois).count { it.isNotEmpty() })
    }

    @Test
    fun `мережа одного роду лишається підписаною вся`() {
        // Дзеркало до тесту вище: та сама відстань, ті самі імена — але один рід,
        // тобто чотири різні крамниці, а не один обʼєкт у чотирьох записах.
        val pois = listOf(
            poi(1, "Фора", 50.4500, 30.5230).copy(category = "shop"),
            poi(2, "Фора", 50.4508, 30.5230).copy(category = "shop"),
            poi(3, "Фора", 50.4512, 30.5236).copy(category = "shop"),
            poi(4, "Фора", 50.4497, 30.5241).copy(category = "shop"),
        )
        assertEquals(4, labels(pois).count { it.isNotEmpty() })
    }

    @Test
    fun `різні роди далеко одне від одного лишаються обидва`() {
        // 300 м — межа «одного місця»; за нею однойменні різного роду знову двоє.
        val pois = listOf(
            poi(1, "Либідська", 50.4300, 30.5150).copy(category = "railway"),
            poi(2, "Либідська", 50.4360, 30.5150).copy(category = "amenity"),
        )
        assertEquals(2, labels(pois).count { it.isNotEmpty() })
    }

    /** Безіменні й службові рядки до шару взагалі не доходять. */
    @Test
    fun `заглушки й дворові барʼєри в план не потрапляють`() {
        val pois = listOf(
            poi(1, "Ворота", 50.45, 30.52),
            poi(2, "Unknown", 50.45, 30.52),
            poi(3, "   ", 50.45, 30.52),
            poi(4, "Аптека", 50.45, 30.52),
        )
        assertEquals(listOf("Аптека"), labels(pois))
    }

    @Test
    fun `латинський читач бачить назву піна латинкою`() {
        val pois = listOf(
            poi(
                id = 1,
                name = "Ощадбанк",
                lat = 50.45,
                lon = 30.52,
                tags = mapOf("name" to "Ощадбанк", "name:en" to "Oschadbank"),
            ),
        )
        val plan = POIOverlay.labelPlan(pois, latin = true)
        assertEquals("Oschadbank", plan[0].second)
        assertEquals("сутність лишається українською", "Ощадбанк", plan[0].first.name)
    }

    /** Жодної транслітерації від себе: немає латинки — лишається кирилиця. */
    @Test
    fun `без латинки в тегах підпис лишається кирилицею`() {
        assertEquals(
            listOf("Пекарня на розі"),
            labels(listOf(poi(1, "Пекарня на розі", 50.45, 30.52)), latin = true),
        )
    }

    /**
     * Близнюків шукають за ПОКАЗАНИМ іменем: два обʼєкти, чиї українські назви
     * різні, а англійські однакові, для чужинця — теж два однакові написи.
     */
    @Test
    fun `близнюків рахують за тим іменем, яке побачить око`() {
        val pois = listOf(
            poi(1, "Нова Пошта", 50.383538, 30.479127, mapOf("name:en" to "Nova Poshta")),
            poi(2, "Нова Пошта №64", 50.383558, 30.479050, mapOf("name:en" to "Nova Poshta")),
        )
        assertEquals(listOf("Nova Poshta", ""), labels(pois, latin = true))
    }
}
