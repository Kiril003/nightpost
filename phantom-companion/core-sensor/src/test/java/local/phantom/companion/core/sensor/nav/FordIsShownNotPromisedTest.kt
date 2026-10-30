package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * БРІД — сторож межі, а не функції.
 *
 * По країні `ford=*` стоїть на ~26 000 обʼєктів, а в спеченому пакеті їх було
 * нуль: ключа не було в [RoadWayCodec.routingKeys]. Ця хвиля його додала — і
 * рівно тут записано, ЧОГО додавання не дає права робити.
 *
 * Половина тестів нижче перевіряє, що щось відбувається. Друга половина —
 * найважливіша — перевіряє, що НІЧОГО не відбувається: маршрут із бродом
 * коштує стільки ж, скільки той самий маршрут без тегу, і жодна дорога не
 * стала ані забороненою, ані дорожчою. «Тут брід» — не «тут проїде» і не «тут
 * не проїде»: рівня води OSM не несе, а він міняється за годину після дощу.
 * Ту саму межу проєкт уже свідомо провів щодо криги.
 *
 * Якщо колись хтось додасть штраф або заборону за `ford`, розсиплеться саме
 * цей файл — і мусить.
 */
class FordIsShownNotPromisedTest {

    private val a = 50.4500 to 30.5000
    private val b = 50.4500 to 30.5030

    private fun way(id: Long, tags: Map<String, String>) = RoadWay(
        id = id,
        points = listOf(a, b),
        tags = tags,
    )

    private fun route(ways: List<RoadWay>, foot: Boolean = false) = LocalRouter().route(
        graph = RoadGraph(ways),
        fromLat = a.first, fromLon = a.second,
        toLat = b.first, toLon = b.second,
        footProfile = foot,
    )

    // ── що тег ТЕПЕР дає ───────────────────────────────────────────────────

    @Test
    fun `a route over a marked ford reports one crossing`() {
        val r = route(listOf(way(1, mapOf("highway" to "track", "ford" to "yes"))))
        assertNotNull(r)
        assertEquals(1, r!!.fordCrossings)
    }

    @Test
    fun `a ford drawn as many segments is still one ford`() {
        // Справжня переправа лежить кількома відрізками підряд. Рахунок за
        // відрізками назвав би один брід трьома.
        val many = RoadWay(
            id = 7,
            points = listOf(
                a,
                (a.first + 0.0002) to (a.second + 0.0008),
                (a.first + 0.0001) to (a.second + 0.0018),
                b,
            ),
            tags = mapOf("highway" to "track", "ford" to "yes"),
        )
        val r = route(listOf(many))
        assertNotNull(r)
        assertEquals("одна дорога — один брід", 1, r!!.fordCrossings)
    }

    @Test
    fun `ford=no is knowledge that there is none`() {
        val r = route(listOf(way(1, mapOf("highway" to "residential", "ford" to "no"))))
        assertNotNull(r)
        assertEquals(0, r!!.fordCrossings)
    }

    @Test
    fun `every other ford value counts — seasonal and stepping stones are fords`() {
        for (v in listOf("yes", "seasonal", "stepping_stones", "intermittent", "stream")) {
            assertTrue("ford=$v", RoadProfile.marksFord(mapOf("ford" to v)))
        }
        assertFalse(RoadProfile.marksFord(mapOf("ford" to "no")))
        assertFalse(RoadProfile.marksFord(emptyMap()))
    }

    @Test
    fun `a pack that never carried the key reports zero, and zero is not a denial`() {
        // Кожен пакет, спечений до 06.09.2026, саме такий. Нуль тут означає
        // «пакет цього не ніс» так само часто, як «бродів нема», і ніщо
        // збудоване на цьому числі не сміє казати «бродів немає».
        val r = route(listOf(way(1, mapOf("highway" to "track"))))
        assertNotNull(r)
        assertEquals(0, r!!.fordCrossings)
    }

    // ── чого тег НЕ дає: межа ──────────────────────────────────────────────

    @Test
    fun `a ford is never forbidden — not on foot, not by car`() {
        val tags = mapOf("highway" to "track", "ford" to "yes")
        assertFalse("брід не є забороною для пішого", RoadProfile.blocked(tags, foot = true))
        assertFalse("брід не є забороною для машини", RoadProfile.blocked(tags, foot = false))
    }

    @Test
    fun `a ford costs exactly what the same road without the tag costs`() {
        // Штраф — це теж твердження, лише тихе: «ця дорога гірша рівно в N
        // разів». N нема звідки взяти, тож його немає.
        val plain = mapOf("highway" to "track", "surface" to "ground")
        val forded = plain + ("ford" to "yes")
        for (foot in listOf(true, false)) {
            assertEquals(
                "штраф за брід зʼявився (foot=$foot)",
                RoadProfile.costFactor(plain, foot),
                RoadProfile.costFactor(forded, foot),
                1e-12,
            )
            assertEquals(
                "швидкість поїхала за бродом (foot=$foot)",
                RoadProfile.speedMps(plain, foot),
                RoadProfile.speedMps(forded, foot),
                1e-12,
            )
        }
    }

    @Test
    fun `the ford tag does not move the router off a road it used before`() {
        // Ліки не сміють убити хворого: той самий граф, той самий вибір.
        val bridge = RoadWay(
            id = 1,
            points = listOf(a, b),
            tags = mapOf("highway" to "residential"),
        )
        val crossing = RoadWay(
            id = 2,
            points = listOf(a, (a.first + 0.0020) to a.second,
                (a.first + 0.0020) to b.second, b),
            tags = mapOf("highway" to "track"),
        )
        val before = route(listOf(bridge, crossing))
        val after = route(listOf(bridge, crossing.copy(tags = crossing.tags + ("ford" to "yes"))))
        assertNotNull(before)
        assertNotNull(after)
        assertEquals(before!!.distanceMeters, after!!.distanceMeters, 1e-9)
        assertEquals(before.points, after.points)
    }

    // ── ключ мусить доїхати до пакета ──────────────────────────────────────

    @Test
    fun `ford survives the tag codec both ways`() {
        val tags = mapOf("highway" to "track", "ford" to "seasonal")
        val encoded = RoadWayCodec.encodeTags(tags)
        assertTrue("ford не потрапив у рядок пакета: $encoded", encoded.contains("ford=seasonal"))
        assertEquals("seasonal", RoadWayCodec.decodeTags(encoded)["ford"])
    }

    @Test
    fun `ford sits at the tail of the whitelist, so an old pack diffs by one pair`() {
        // Порядок оголошення вирішує байти. Вставка в середину переписала б
        // кожен рядок пакета й зробила б звірку «до/після» нечитабельною.
        assertEquals("ford", RoadWayCodec.routingKeys().last())
    }
}
