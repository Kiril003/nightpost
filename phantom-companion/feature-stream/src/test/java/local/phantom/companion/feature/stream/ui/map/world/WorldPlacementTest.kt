package local.phantom.companion.feature.stream.ui.map.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldPlacementTest {

    private val focus = 49.8397 to 24.0297
    private fun at(id: String, kind: WorldKind, dLatM: Double, dLonM: Double = 0.0, state: WorldState? = null, target: Boolean = false, onRoute: Boolean = false) =
        WorldObject(
            id = id,
            kind = kind,
            lat = focus.first + dLatM / 111_320.0,
            lon = focus.second + dLonM / (111_320.0 * kotlin.math.cos(Math.toRadians(focus.first))),
            state = state,
            target = target,
            onRoute = onRoute,
        )

    @Test
    fun `небезпека виграє місце в аптеки, навіть коли аптека ближче`() {
        val pharmacy = at("ph", WorldKind.PHARMACY, 2.0)
        val danger = at("dg", WorldKind.DANGER, 6.0)
        val placed = placeWorld(listOf(pharmacy, danger), zoom = 17.0, focus = focus, routeActive = false)
        assertEquals(listOf("dg"), placed.map { it.obj.id })
    }

    @Test
    fun `мета маршруту стоїть попереду потреб і решти`() {
        val atm = at("atm", WorldKind.ATM, 0.0, 200.0)
        val goal = at("goal", WorldKind.POST, 0.0, 400.0, target = true)
        val placed = placeWorld(listOf(atm, goal), zoom = 17.0, focus = focus, routeActive = true)
        assertEquals("goal", placed.first().obj.id)
        assertTrue(placed.first().accent)
        assertTrue(placed.first().rank < placed.last().rank)
    }

    @Test
    fun `фігурки не налазять — ближче за поріг лишається старша`() {
        val gapM = MIN_GAP_DP * local.phantom.companion.feature.stream.ui.map.metersPerDp(focus.first, 17.0)
        val a = at("a", WorldKind.FUEL, 0.0)
        val b = at("b", WorldKind.ATM, gapM * 0.5)
        val c = at("c", WorldKind.ATM, gapM * 1.5)
        val placed = placeWorld(listOf(b, a, c), zoom = 17.0, focus = focus, routeActive = false).map { it.obj.id }
        assertTrue("a" in placed)
        assertFalse("налізли одна на одну", "b" in placed)
        assertTrue("c" in placed)
    }

    @Test
    fun `потреби не заслуговують далекого масштабу, безпека заслуговує`() {
        val pharmacy = at("ph", WorldKind.PHARMACY, 50.0)
        val checkpoint = at("cp", WorldKind.CHECKPOINT, 900.0)
        val placed = placeWorld(listOf(pharmacy, checkpoint), zoom = 13.0, focus = focus, routeActive = false)
        assertEquals(listOf("cp"), placed.map { it.obj.id })
    }

    @Test
    fun `ворота на шляху видно й здалеку`() {
        val gate = at("g", WorldKind.LIFT_GATE, 300.0, onRoute = true)
        val yard = at("y", WorldKind.LIFT_GATE, 0.0, 300.0)
        val placed = placeWorld(listOf(gate, yard), zoom = 14.0, focus = focus, routeActive = true)
        assertEquals(listOf("g"), placed.map { it.obj.id })
    }

    @Test
    fun `стеля кадру тримається`() {
        val many = (0 until 60).map { i -> at("s$i", WorldKind.STOP, i * 200.0) }
        assertEquals(capFor(17.0), placeWorld(many, 17.0, focus, false).size)
    }

    @Test
    fun `зачинене й не на шляху — тихіше, безпека не тихне`() {
        val closed = at("c", WorldKind.PHARMACY, 0.0, state = WorldState.CLOSED)
        val shelter = at("s", WorldKind.SHELTER, 400.0)
        val placed = placeWorld(listOf(closed, shelter), 17.0, focus, routeActive = true).associateBy { it.obj.id }
        assertEquals(QUIET * OFF_ROUTE, placed.getValue("c").opacity, 1e-6f)
        assertEquals(1f, placed.getValue("s").opacity, 1e-6f)
    }

    @Test
    fun `укриття дихає лише під тривогою і лише зблизька`() {
        val alert = at("a", WorldKind.SHELTER, 0.0, state = WorldState.ALERT)
        assertTrue(placeWorld(listOf(alert), 16.0, focus, false).single().breathe)
        assertFalse(placeWorld(listOf(alert), 14.2, focus, false).single().breathe)
        val calm = at("b", WorldKind.SHELTER, 0.0, state = WorldState.OPEN)
        assertFalse(placeWorld(listOf(calm), 17.0, focus, false).single().breathe)
        val pharmacy = at("p", WorldKind.PHARMACY, 0.0, state = WorldState.ALERT)
        assertFalse("дихає лише те, що вміє", placeWorld(listOf(pharmacy), 17.0, focus, false).single().breathe)
    }

    @Test
    fun `стан — лише з даних`() {
        val tags = mapOf("opening_hours" to "Mo-Su 08:00-22:00")
        val noon = java.util.Calendar.getInstance().apply { set(2026, 8, 27, 12, 0) }
        val night = java.util.Calendar.getInstance().apply { set(2026, 8, 27, 23, 30) }
        assertEquals(WorldState.OPEN, worldState(WorldKind.PHARMACY, tags, false, noon))
        assertEquals(WorldState.CLOSED, worldState(WorldKind.PHARMACY, tags, false, night))
        assertEquals(null, worldState(WorldKind.PHARMACY, emptyMap(), false, noon))
        assertEquals("у воріт стану в даних немає", null, worldState(WorldKind.LIFT_GATE, tags, true, noon))
        assertEquals(WorldState.ALERT, worldState(WorldKind.SHELTER, emptyMap(), true, noon))
    }

    @Test
    fun `кожен вид має власне тіло і стани лише зі свого списку`() {
        assertEquals(15, WorldKind.entries.size)
        assertEquals(WorldKind.entries.size, WorldKind.entries.map { it.id }.toSet().size)
        assertEquals(listOf(WorldKind.SHELTER), WorldKind.entries.filter { it.animates })
    }
}

class ShelterBreathTest {
    @Test
    fun `дихання скінченне і повертається до спокою`() {
        assertTrue(ShelterBreath.frame(0) != null)
        assertEquals(null, ShelterBreath.frame(ShelterBreath.PERIOD_MS * ShelterBreath.CYCLES))
        assertEquals(null, ShelterBreath.frame(-1))
        val peak = ShelterBreath.frame(ShelterBreath.PERIOD_MS / 2)!!
        val start = ShelterBreath.frame(0)!!
        assertTrue("вдих не розширює коло", peak.radiusDp > start.radiusDp)
        assertEquals(ShelterBreath.rest.radiusDp, start.radiusDp, 1e-4f)
    }
}
