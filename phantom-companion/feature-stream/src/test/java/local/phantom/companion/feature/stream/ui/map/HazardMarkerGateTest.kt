package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.geo.cameras.HazardType
import local.phantom.companion.core.geo.cameras.SafetyHazard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Відбір значків-загроз. Сцена — спальний квартал Києва, той самий, на якому
 * власник побачив екран під коричневими «Воротами».
 */
class HazardMarkerGateTest {

    private val lat0 = 50.4700
    private val lon0 = 30.4400

    /** Зсув на північ у метрах — щоб «за 40 м» у тесті означало 40 м. */
    private fun northOf(meters: Double) = lat0 + meters / 111_320.0

    private fun gate(id: String, lat: Double, lon: Double = lon0) =
        SafetyHazard(id, lat, lon, "Ворота", HazardType.GATE)

    private fun camera(id: String, lat: Double) =
        SafetyHazard(id, lat, lon0, "Камера", HazardType.SPEED_CAMERA)

    private fun mark(id: String, lat: Double) =
        SafetyHazard(id, lat, lon0, "Патруль", HazardType.ACCIDENT_HOTSPOT)

    /** 200 м один від одного — жодне скупчення їх не зʼїдає. */
    private fun spread(count: Int) = List(count) { gate("g$it", northOf(200.0 * (it + 1))) }

    @Test
    fun `far out the courtyard gates stay off the glass`() {
        val out = gateHazards(spread(20), zoom = 15.0, focus = lat0 to lon0)
        assertTrue("на z15 ворота не показуємо", out.isEmpty())
    }

    @Test
    fun `close in the gates come back`() {
        val out = gateHazards(spread(5), zoom = 17.0, focus = lat0 to lon0)
        assertEquals(5, out.size)
    }

    @Test
    fun `the zoom threshold is inclusive`() {
        val out = gateHazards(spread(1), zoom = HazardGate.BARRIER_MIN_ZOOM, focus = lat0 to lon0)
        assertEquals(1, out.size)
    }

    @Test
    fun `a gate on the route corridor shows at any zoom`() {
        val onRoute = gate("on", northOf(1000.0))
        val offRoute = gate("off", northOf(5000.0))
        val out = gateHazards(
            listOf(onRoute, offRoute),
            zoom = 12.0,
            corridor = listOf(northOf(1050.0) to lon0),
            focus = lat0 to lon0,
        )
        assertEquals(listOf("on"), out.map { it.id })
    }

    @Test
    fun `corridor width is the stated 150 metres, not more`() {
        val justOutside = gate("far", northOf(1000.0 + HazardGate.ROUTE_CORRIDOR_M + 20.0))
        val out = gateHazards(
            listOf(justOutside),
            zoom = 12.0,
            corridor = listOf(northOf(1000.0) to lon0),
            focus = lat0 to lon0,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `one pin per cluster`() {
        // Пʼятеро воріт одного двору, всі в межах 20 м.
        val yard = List(5) { gate("y$it", northOf(500.0 + it * 5.0)) }
        val out = gateHazards(yard, zoom = 18.0, focus = lat0 to lon0)
        assertEquals(1, out.size)
    }

    @Test
    fun `the kept pin of a cluster is the nearest one`() {
        val yard = listOf(
            gate("far", northOf(520.0)),
            gate("near", northOf(500.0)),
            gate("mid", northOf(510.0)),
        )
        val out = gateHazards(yard, zoom = 18.0, focus = lat0 to lon0)
        assertEquals(listOf("near"), out.map { it.id })
    }

    @Test
    fun `the viewport is capped, nearest first`() {
        val many = spread(40)
        val out = gateHazards(many, zoom = 18.0, focus = lat0 to lon0)
        assertEquals(HazardGate.MAX_BARRIERS, out.size)
        assertEquals(List(HazardGate.MAX_BARRIERS) { "g$it" }, out.map { it.id })
    }

    @Test
    fun `cameras and manual marks are never rationed`() {
        val tactical = List(30) { camera("c$it", northOf(100.0 * (it + 1))) } +
            List(30) { mark("m$it", northOf(50.0 * (it + 1))) }
        val out = gateHazards(tactical + spread(40), zoom = 13.0, focus = lat0 to lon0)
        assertEquals(60, out.size)
        assertTrue("на z13 жодних воріт", out.none { it.type == HazardType.GATE })
    }

    @Test
    fun `a real checkpoint is never thinned as a courtyard gate`() {
        // Поки ворота й блокпост були однією армою, це правило зрізало КПП
        // разом із дворовими шлагбаумами — тобто ховало саме те, заради чого
        // писалось.
        val checkpoints = List(20) {
            SafetyHazard("cp$it", northOf(200.0 * (it + 1)), lon0, "", HazardType.CHECKPOINT)
        }
        val out = gateHazards(checkpoints, zoom = 13.0, focus = lat0 to lon0)
        assertEquals(20, out.size)
    }

    @Test
    fun `no focus means no crash and no ordering promise`() {
        val out = gateHazards(spread(40), zoom = 18.0, focus = null)
        assertEquals(HazardGate.MAX_BARRIERS, out.size)
    }

    @Test
    fun `nothing in, nothing out`() {
        assertTrue(gateHazards(emptyList(), zoom = 18.0).isEmpty())
    }

    @Test
    fun `the spine thins a dense route without shortening it`() {
        // 2 000 вершин через 5 м — типовий міський маршрут із роутера.
        val dense = List(2_000) { northOf(it * 5.0) to lon0 }
        val spine = corridorSpine(dense)
        assertTrue("хребет мусить бути на порядок коротший: ${spine.size}", spine.size < 120)
        assertEquals("початок маршруту лишається", dense.first(), spine.first())
        assertEquals("хвіст маршруту лишається", dense.last(), spine.last())
    }

    @Test
    fun `thinning never opens a hole in the corridor`() {
        val dense = List(2_000) { northOf(it * 5.0) to lon0 }
        val spine = corridorSpine(dense)
        // Кожна вершина щільного маршруту мусить лишитись у межах коридору.
        val orphan = dense.firstOrNull { d ->
            spine.none { haversineMeters(it, d) <= HazardGate.ROUTE_CORRIDOR_M }
        }
        assertEquals(null, orphan)
    }

    @Test
    fun `an empty route has an empty spine`() {
        assertTrue(corridorSpine(emptyList()).isEmpty())
    }

    @Test
    fun `a one-point route survives thinning`() {
        assertEquals(1, corridorSpine(listOf(lat0 to lon0)).size)
    }
}
