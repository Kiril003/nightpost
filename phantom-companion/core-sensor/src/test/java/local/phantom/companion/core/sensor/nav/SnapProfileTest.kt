package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Машина не починає маршрут із тротуару.
 *
 * ЧОМУ ЦЕ ІСНУЄ. `LocalRouter.route` чіплявся до найближчої лінії БУДЬ-ЯКОГО
 * роду (`near(..., limit = 1)`). На пакетах без тегів це не шкодило: там
 * непроїзних доріг не існувало взагалі, бо не існувало тегів. На пакеті з
 * тегами воно вимикає навігацію рівно там, де люди сідають у машину.
 *
 * Заміряно 30 серпня 2026 на щойно спеченому київському пакеті (v2), маршрут
 * від дому власника до центру:
 *
 *   без тегів: 11,36 км, основа ASSUMED — і 4 497 м із них (39,6%) пролягали
 *              там, де машина проїхати не може;
 *   з тегами, старе чіплення: МАРШРУТУ НЕМАЄ ВЗАГАЛІ. Обидва кінці чіплялись
 *              до `highway=footway` за 1 і 4 метри; перше ж ребро коштувало
 *              BLOCKED_PENALTY = 500×, фронт A* розпухав і впирався у стелю
 *              MAX_ASTAR_NODES;
 *   з тегами, після ліків: 13,02 км, основа CLASS, 97% метрів зі знаком,
 *              0 м непроїзних, і пошук ШВИДШИЙ (285 мс проти 467 мс).
 *
 * Тобто перепікання пакетів, накладене на цю ваду, зробило б навігацію
 * гіршою за хворобу. Сторож стоїть саме тут.
 */
class SnapProfileTest {

    private val lat0 = 50.4501
    private val lon0 = 30.5234

    /** Північніше на [m] метрів. */
    private fun north(m: Double) = lat0 + Math.toDegrees(m / 6_371_000.0)

    private fun east(m: Double) =
        lon0 + Math.toDegrees(m / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))

    /** Вулиця й тротуар поруч, зшиті західним кінцем — як у справжньому дворі. */
    private fun ways(): List<RoadWay> {
        val street = RoadWay(
            id = 1,
            points = listOf(lat0 to lon0, lat0 to east(2_000.0)),
            tags = mapOf("highway" to "residential"),
        )
        val footway = RoadWay(
            id = 2,
            points = listOf(lat0 to lon0, north(20.0) to east(300.0)),
            tags = mapOf("highway" to "footway"),
        )
        return listOf(street, footway)
    }

    @Test
    fun `машина чіпляється до вулиці, хоч тротуар ближчий`() {
        val g = RoadGraph(ways())
        // Людина стоїть НА тротуарі: до нього нуль метрів, до вулиці — двадцять.
        val startLat = north(20.0)
        val startLon = east(300.0)

        val nearest = g.near(startLat, startLon, LocalRouter.SNAP_RADIUS_M, limit = 1).first()
        assertEquals("умова тесту: найближчий саме тротуар", 2L, nearest.way.id)

        val r = LocalRouter().route(
            g, startLat, startLon, lat0, east(1_900.0), footProfile = false,
        )
        assertNotNull("машина мусить знайти дорогу, а не впертись у тротуар", r)
        checkNotNull(r)
        assertEquals(
            "час мусить стояти на класі дороги, а не на пласкій вгадці",
            RoadProfile.SpeedBasis.CLASS,
            r.speedBasis,
        )
    }

    @Test
    fun `пішоходу тротуар лишається`() {
        val g = RoadGraph(ways())
        val startLat = north(20.0)
        val startLon = east(300.0)
        val r = LocalRouter().route(
            g, startLat, startLon, lat0, east(200.0), footProfile = true,
        )
        assertNotNull("ліки для машини не сміють забрати тротуар у людини", r)
    }

    @Test
    fun `коли проїзної нема взагалі — ведемо, а не відмовляємо`() {
        // Людина в глибині парку. 500× штрафу за перші метри чесніше за
        // «маршруту немає»: відмова нікуди не веде.
        val onlyFoot = listOf(
            RoadWay(
                id = 2,
                points = listOf(lat0 to lon0, lat0 to east(500.0)),
                tags = mapOf("highway" to "footway"),
            ),
        )
        val g = RoadGraph(onlyFoot)
        val r = LocalRouter().route(g, lat0, lon0, lat0, east(400.0), footProfile = false)
        assertNotNull("без проїзної дороги маршрут усе одно мусить бути", r)
    }

    @Test
    fun `без тегів вибір не змінюється — ліки нікого не зрушили`() {
        // Гарантія «не погіршити»: на старих пакетах blocked завжди false,
        // тож чіплення лишається тим самим, що й до ліків.
        val bare = ways().map { it.copy(tags = emptyMap()) }
        val g = RoadGraph(bare)
        val startLat = north(20.0)
        val startLon = east(300.0)
        val nearest = g.near(startLat, startLon, LocalRouter.SNAP_RADIUS_M, limit = 1).first()
        val r = LocalRouter().route(g, startLat, startLon, lat0, east(1_900.0), footProfile = false)
        assertNotNull(r)
        assertTrue("без тегів найближча лінія лишається тротуаром за геометрією", nearest.way.id == 2L)
    }
}
