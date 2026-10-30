package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож проти вади, яка прожила в продукті непоміченою: обмеження з тегу
 * множилось на реалізм, а таблиця класів — ні, тож дорога БЕЗ даних виходила
 * швидшою за ту саму дорогу З даними на 43%.
 *
 * Заміряно 05.09 на свіжому пакеті України, географічне відкладання:
 * стара таблиця завищувала на 100,0% метрів trunk поза навчанням.
 */
class SpeedTableIsNotACeilingTest {

    /** Український закон: стеля для класу, вже помножена на реалізм. */
    private val legalCeilingKmh = mapOf(
        "motorway" to 130, "trunk" to 90, "trunk_link" to 90,
        "primary" to 90, "primary_link" to 90,
        "secondary" to 90, "secondary_link" to 90,
        "tertiary" to 90, "tertiary_link" to 90,
        "unclassified" to 90, "residential" to 50,
        "living_street" to 20, "service" to 50,
        "motorway_link" to 130,
    )

    @Test
    fun `жоден клас не їде швидше за закон, помножений на реалізм`() {
        val table = RoadProfile.classPaceForTest()
        assertTrue("таблиця порожня — перевіряти нічого", table.isNotEmpty())
        val broken = table.mapNotNull { (cls, pace) ->
            val ceiling = legalCeilingKmh[cls] ?: return@mapNotNull null
            val allowed = ceiling * RoadProfile.KMH_TO_MPS * RoadProfile.URBAN_REALISM
            if (pace > allowed + 1e-9) {
                "$cls: ${"%.1f".format(pace)} м/с проти стелі ${"%.1f".format(allowed)}"
            } else {
                null
            }
        }
        assertTrue(
            "клас їде швидше, ніж дозволяє закон із тим самим реалізмом — саме так " +
                "trunk рахувався як 90 км/год без тегу й 63 з тегом:\n" + broken.joinToString("\n"),
            broken.isEmpty(),
        )
    }

    @Test
    fun `ґрунтівки лишаються поза таблицею й падають у ASSUMED`() {
        // 45% усієї прогалини і 500 розмічених кілометрів на 496 014.
        assertNull(
            "track додали в таблицю — це підняло б упевненість там, де даних найменше",
            RoadProfile.classPaceForTest()["track"],
        )
    }

    @Test
    fun `пресет чужої країни не дає швидше за український закон`() {
        assertEquals(50, RoadProfile.postedKmh("UA:urban"))
        assertEquals(90, RoadProfile.postedKmh("UA:rural"))
        // RU:urban = 60 у себе, але в Україні 50.
        assertEquals(50, RoadProfile.postedKmh("RU:urban"))
        // RO:trunk = 100 у себе, в Україні 90.
        assertEquals(90, RoadProfile.postedKmh("RO:trunk"))
        assertEquals(90, RoadProfile.postedKmh("RU:rural"))
    }

    @Test
    fun `звичайні числа читаються як раніше, а сміття не читається`() {
        assertEquals(50, RoadProfile.postedKmh("50"))
        assertEquals(5, RoadProfile.postedKmh("5 mph"))
        assertNull(RoadProfile.postedKmh(null))
        assertNull(RoadProfile.postedKmh("signals"))
    }
}
