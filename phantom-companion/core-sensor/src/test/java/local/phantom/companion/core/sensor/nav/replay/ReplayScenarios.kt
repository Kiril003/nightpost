package local.phantom.companion.core.sensor.nav.replay

import local.phantom.companion.core.sensor.nav.RoadGraph
import kotlin.math.PI

internal enum class EventKind { OUTAGE, SPOOF, BENIGN }

internal data class Scenario(
    val name: String,
    val body: Body,
    val route: List<En>,
    val legs: List<Leg>,
    val sky: List<SkyWindow>,
    val durationS: Double,
    val event: EventKind,
    val eventFromS: Double,
    val eventToS: Double,
    val jamming: Boolean = false,
    val mountYawDeg: Double = 0.0,
    val roads: RoadNet? = null,
    val seed: Long = 7L,
) {
    val path: Polyline by lazy { Polyline(route) }
    val graph: RoadGraph? by lazy { roads?.let { RoadGraph(it.ways) } }
}

internal object Scenarios {
    private val highway = listOf(En(0.0, 0.0), En(3_000.0, 0.0), En(6_000.0, 900.0), En(9_000.0, 900.0), En(12_000.0, 300.0))

    private fun highwayRoads(): RoadNet {
        val main = RoadNet.splitAt(Polyline(highway).pts, 400.0)
        val cross = (1..20).map { k ->
            val (p, h) = Polyline(highway).at(k * 450.0)
            listOf(p + unit(h + PI / 2) * -250.0, p, p + unit(h + PI / 2) * 250.0)
        }
        val parallel = listOf(listOf(En(200.0, 45.0), En(2_900.0, 45.0)))
        return RoadNet.of(*(main + cross + parallel).toTypedArray())
    }

    private val city = listOf(
        En(0.0, 0.0), En(600.0, 0.0), En(600.0, 450.0), En(1_200.0, 450.0), En(1_200.0, 900.0), En(1_800.0, 900.0),
    )

    private fun cityGrid(): RoadNet {
        val lines = ArrayList<List<En>>()
        for (i in -1..13) {
            val x = i * 150.0
            lines += RoadNet.splitAt(listOf(En(x, -300.0), En(x, 1_200.0)), 150.0)
        }
        for (j in -2..8) {
            val y = j * 150.0
            lines += RoadNet.splitAt(listOf(En(-150.0, y), En(1_950.0, y)), 150.0)
        }
        return RoadNet.of(*lines.toTypedArray())
    }

    private val walk = listOf(
        En(0.0, 0.0), En(90.0, 0.0), En(90.0, 60.0), En(250.0, 60.0), En(250.0, -40.0), En(420.0, -40.0), En(420.0, 200.0),
    )

    private fun clearAround(fromS: Double, toS: Double, event: Sky, total: Double) = listOf(
        SkyWindow(0.0, fromS, Sky.Clear()),
        SkyWindow(fromS, toS, event),
        SkyWindow(toS, total, Sky.Clear()),
    )

    fun tunnel(name: String, outageS: Double, legs: List<Leg> = listOf(Leg(1e9, 17.0)), mountYawDeg: Double = 0.0, sky: Sky = Sky.Silent, jamming: Boolean = false): Scenario {
        val total = 60 + outageS + 60
        return Scenario(
            name, Body.CAR, highway, legs, clearAround(60.0, 60 + outageS, sky, total), total,
            EventKind.OUTAGE, 60.0, 60 + outageS, jamming = jamming, mountYawDeg = mountYawDeg, roads = highwayRoads(),
        )
    }

    val all: List<Scenario> by lazy {
        listOf(
            tunnel("авто, тунель 60 с", 60.0),
            tunnel("авто, тунель 180 с, затор", 180.0, listOf(Leg(90.0, 17.0), Leg(40.0, 0.0), Leg(1e9, 12.0))),
            tunnel("авто, тунель 300 с", 300.0),
            tunnel("авто, тунель 180 с, лише стільник", 180.0, sky = Sky.CellOnly()),
            tunnel("авто, тримач 30°, тунель 90 с", 90.0, mountYawDeg = 30.0),
            tunnel("авто, глушіння 120 с", 120.0, jamming = true),
            Scenario(
                "авто, місто, підземний 60 с з поворотами", Body.CAR, city, listOf(Leg(1e9, 11.0)),
                clearAround(40.0, 100.0, Sky.Silent, 180.0), 180.0, EventKind.OUTAGE, 40.0, 100.0, roads = cityGrid(),
            ),
            Scenario(
                "пішки, перехід/метро 120 с", Body.WALK, walk, listOf(Leg(1e9, 1.4)),
                clearAround(60.0, 180.0, Sky.Silent, 260.0), 260.0, EventKind.OUTAGE, 60.0, 180.0,
            ),
            Scenario(
                "пішки, підземка 300 с", Body.WALK, walk + listOf(En(420.0, 400.0), En(200.0, 400.0)), listOf(Leg(1e9, 1.4)),
                clearAround(40.0, 340.0, Sky.Silent, 400.0), 400.0, EventKind.OUTAGE, 40.0, 340.0,
            ),
            tunnel("авто, підміна дрейфом 3 м/с", 150.0, sky = Sky.Drift(3.0)).copy(event = EventKind.SPOOF),
            tunnel("авто, підміна стрибком 1,5 км", 120.0, sky = Sky.Jump(1_500.0)).copy(event = EventKind.SPOOF),
            Scenario(
                "на столі, підміна стрибком 2 км", Body.STILL, listOf(En(0.0, 0.0), En(1.0, 0.0)), listOf(Leg(1e9, 0.0)),
                clearAround(120.0, 240.0, Sky.Jump(2_000.0), 300.0), 300.0, EventKind.SPOOF, 120.0, 240.0,
            ),
            Scenario(
                "авто, міський каньйон 300 с", Body.CAR, city, listOf(Leg(1e9, 9.0)),
                clearAround(30.0, 330.0, Sky.Canyon(), 360.0),
                360.0, EventKind.BENIGN, 30.0, 330.0, roads = cityGrid(),
            ),
        )
    }

    fun table(hours: Double, seed: Long = 11L) = Scenario(
        "на столі в приміщенні, ${hours.toInt()} год", Body.STILL, listOf(En(0.0, 0.0), En(1.0, 0.0)), listOf(Leg(1e9, 0.0)),
        listOf(SkyWindow(0.0, 60.0, Sky.Clear(12.0)), SkyWindow(60.0, hours * 3600, Sky.Indoor)),
        hours * 3600, EventKind.BENIGN, 60.0, hours * 3600, seed = seed,
    )

    fun networkAccFor(sky: Sky): Double? = when (sky) {
        is Sky.Clear, is Sky.Drift, is Sky.Jump -> 60.0
        is Sky.Canyon -> 50.0
        Sky.Silent, is Sky.CellOnly -> 1_500.0
        Sky.Indoor -> 40.0
    }
}
