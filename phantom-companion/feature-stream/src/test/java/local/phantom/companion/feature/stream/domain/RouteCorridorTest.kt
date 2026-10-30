package local.phantom.companion.feature.stream.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Один коридор замість купи дрібних — і він мусить бути справді вужчим за рамку.
 *
 * Якщо коридор виходить не меншим за прямокутник навколо всього маршруту, то
 * весь задум марний: то була б та сама неможлива до вміщення в телефон земля,
 * тільки під новою назвою.
 */
class RouteCorridorTest {

    /** Київ → Львів по прямій, 25 точок. Класичний довгий маршрут. */
    private val kyivToLviv: List<Pair<Double, Double>> = (0..24).map { i ->
        val t = i / 24.0
        (50.45 + (49.84 - 50.45) * t) to (30.52 + (24.03 - 30.52) * t)
    }

    private fun bboxAreaKm2(pts: List<Pair<Double, Double>>, marginKm: Double): Double {
        val n = pts.maxOf { it.first } + marginKm / 111.32
        val s = pts.minOf { it.first } - marginKm / 111.32
        val e = pts.maxOf { it.second }
        val w = pts.minOf { it.second }
        val mid = Math.toRadians((n + s) / 2)
        return (n - s) * 111.32 * (e - w) * 111.32 * kotlin.math.cos(mid)
    }

    @Test
    fun `a long route corridor is a fraction of its bounding box`() {
        val boxes = RouteCorridor.boxes(kyivToLviv)
        val corridor = RouteCorridor.areaKm2(boxes)
        val bbox = bboxAreaKm2(kyivToLviv, RouteCorridor.DEFAULT_MARGIN_KM)
        assertTrue("ланок немає — коридор порожній", boxes.isNotEmpty())
        assertTrue(
            "коридор ${corridor.toInt()} км² проти рамки ${bbox.toInt()} км² — " +
                "виграшу немає, отже це та сама неможлива земля",
            corridor < bbox / 3.0,
        )
    }

    @Test
    fun `the chain never exceeds its part ceiling`() {
        val many = (0..5_000).map { 50.0 + it * 0.0001 to 30.0 + it * 0.0001 }
        assertTrue(RouteCorridor.boxes(many).size <= RouteCorridor.MAX_PARTS)
    }

    @Test
    fun `a single point is not a corridor`() {
        assertEquals(emptyList<RouteCorridor.Box>(), RouteCorridor.boxes(listOf(50.0 to 30.0)))
        assertEquals(emptyList<RouteCorridor.Box>(), RouteCorridor.boxes(emptyList()))
    }

    @Test
    fun `the margin is real ground on both sides, in kilometres not degrees`() {
        // Пряма зі сходу на захід по 50-й паралелі: ширина коридору тут
        // визначається ЛИШЕ запасом, тож її можна перевірити напряму.
        val flat = listOf(50.0 to 30.0, 50.0 to 30.5)
        val b = RouteCorridor.boxes(flat, marginKm = 5.0).first()
        val heightKm = (b.northLat - b.southLat) * 111.32
        assertEquals("коридор не 10 км завширшки", 10.0, heightKm, 0.3)
    }

    @Test
    fun `every part becomes a closed ring in the geojson`() {
        val json = RouteCorridor.multiPolygonJson(RouteCorridor.boxes(kyivToLviv))
        assertTrue(json.startsWith("""{"type":"MultiPolygon","coordinates":[[["""))
        // Пʼять вершин на кільце: чотири кути плюс замикання.
        assertEquals(RouteCorridor.boxes(kyivToLviv).size * 5, json.split("],[").size - 1 + 1)
    }
}
