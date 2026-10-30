package local.phantom.companion.feature.stream.ui.map.nav

import local.phantom.companion.core.nav.LandmarkField
import local.phantom.companion.core.nav.OfflineManeuverSynthesizer
import local.phantom.companion.core.nav.RouteLine
import local.phantom.companion.core.nav.RoutePath
import local.phantom.companion.core.nav.TurnLandmarks
import local.phantom.companion.core.nav.guidancePhraseUk
import local.phantom.companion.core.nav.landmarkOf
import local.phantom.companion.core.nav.routeLineKindOf
import local.phantom.companion.core.net.map.RouteOrigin
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import local.phantom.companion.core.sensor.nav.LocalRouter
import local.phantom.companion.core.sensor.nav.RoadGraph
import local.phantom.companion.core.sensor.nav.RoadProfile
import local.phantom.companion.core.sensor.nav.RoadWay
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos

/**
 * ПРИЛАД, не ворота: без `build/landmark-routes` пропускається.
 *
 *   python3 scripts/extract_landmark_corridors.py <lvivska.db> <kyiv-v5.db> \
 *       core-data/src/main/assets/country/ua feature-stream/build/landmark-routes
 *   bash scripts/gate.sh :feature-stream:testDebugUnitTest --tests '*LandmarkShareMeasure*'
 *
 * Той самий шлях, що в телефоні без мережі: А* по дорожньому пакету → синтез
 * кроків → вибір орієнтира з того, що дає газетир і пакет у радіусі маневру.
 */
class LandmarkShareMeasureTest {

    private val dir = File("build/landmark-routes")
    private val noon = 1_790_000_000_000L

    private class Corridor(
        val name: String,
        val mode: String,
        val from: Pair<Double, Double>,
        val to: Pair<Double, Double>,
        val ways: List<RoadWay>,
        val places: List<Array<String>>,
    )

    private fun read(f: File): Corridor {
        val lines = f.readLines()
        val h = lines.first().removePrefix("#").split('\t')
        val ways = ArrayList<RoadWay>()
        val places = ArrayList<Array<String>>()
        for (l in lines.drop(1)) {
            val c = l.split('\t')
            if (c[0] == "W" && c.size >= 6) {
                val pts = c[4].split(';').mapNotNull {
                    val p = it.split(','); if (p.size == 2) p[0].toDouble() to p[1].toDouble() else null
                }
                if (pts.size < 2) continue
                val tags = c[5].split('|').mapNotNull { val kv = it.split('=', limit = 2); if (kv.size == 2) kv[0] to kv[1] else null }.toMap()
                ways += RoadWay(c[1].toLong(), pts, c[2].ifBlank { null }, c[3] == "1", tags)
            } else if (c[0] == "P" && c.size >= 7) {
                places += c.drop(1).toTypedArray()
            }
        }
        return Corridor(h[0], h[1], h[2].toDouble() to h[3].toDouble(), h[4].toDouble() to h[5].toDouble(), ways, places)
    }

    private fun field(c: Corridor, anchors: List<Pair<Double, Double>>): LandmarkField {
        val r = TurnLandmarks.QUERY_RADIUS_M
        fun near(lat: Double, lon: Double) = anchors.any { (a, b) ->
            Math.abs(lat - a) <= r / 111_320.0 && Math.abs(lon - b) <= r / (111_320.0 * cos(Math.toRadians(a)))
        }
        val places = c.places.mapNotNull { p ->
            val lat = p[0].toDouble()
            val lon = p[1].toDouble()
            if (!near(lat, lon)) null else landmarkOf(p[2], p[3].ifEmpty { null }, p[4], lat, lon, p[5].ifEmpty { null })
        }
        val lines = c.ways.mapNotNull { w ->
            val k = routeLineKindOf(w.tags) ?: return@mapNotNull null
            if (w.points.none { (la, lo) -> near(la, lo) }) null else RouteLine(k, w.points)
        }
        return LandmarkField(places, lines)
    }

    @Test
    fun `частка поворотів з орієнтиром на десяти маршрутах`() {
        val files = dir.listFiles { f: File -> f.name.endsWith(".tsv") }?.sortedBy { it.name }.orEmpty()
        assumeTrue("немає ${dir.path}", files.isNotEmpty())
        var turnsAll = 0
        var dayAll = 0
        var nightAll = 0
        println("МАРШРУТ\tпрофіль\tкм\tповоротів\tдо\tпісля_день\tпісля_ніч")
        val samples = ArrayList<String>()
        for (f in files) {
            val c = read(f)
            val profile = if (c.mode == "foot") RouteProfile.FOOT else RouteProfile.CAR
            val mode = if (profile == RouteProfile.FOOT) RoadProfile.TravelMode.FOOT else RoadProfile.TravelMode.CAR
            val leg = LocalRouter().route(RoadGraph(c.ways), c.from.first, c.from.second, c.to.first, c.to.second, mode)
            if (leg == null) {
                println("${c.name}\tНЕ ПРОКЛАДЕНО")
                continue
            }
            val steps = OfflineManeuverSynthesizer.synthesize(leg.points, leg.roadNames, profile)
            val v = RouteVariant(leg.points, leg.distanceMeters, leg.durationSeconds, steps, emptyList(), profile, origin = RouteOrigin.OFFLINE_MESH)
            val path = RoutePath.of(v)!!
            val hints = TurnLandmarks.plan(v, path, field(c, TurnLandmarks.anchors(v, path)))
            val turns = steps.indices.filter { TurnLandmarks.isTurn(steps[it]) }
            val day = turns.count { TurnLandmarks.choose(hints[it], noon) { false } != null }
            val night = turns.count { TurnLandmarks.choose(hints[it], noon) { true } != null }
            // До цієї зміни вказівка не мала звідки взяти орієнтир: «до» — нуль за побудовою.
            val before = 0
            turnsAll += turns.size
            dayAll += day
            nightAll += night
            println("${c.name}\t${c.mode}\t${"%.1f".format(leg.distanceMeters / 1000)}\t${turns.size}\t$before\t$day (${pct(day, turns.size)})\t$night (${pct(night, turns.size)})")
            turns.mapNotNull { i -> TurnLandmarks.choose(hints[i], noon) { false }?.let { i to it } }.take(3).forEach { (i, h) ->
                samples += "${c.name}: ${guidancePhraseUk(steps[i], h, null)}"
            }
            hints[0]?.firstOrNull()?.let { samples += "${c.name}: ${guidancePhraseUk(steps[0], it, null)}" }
        }
        println("УСЬОГО\t\t\t$turnsAll\t0\t$dayAll (${pct(dayAll, turnsAll)})\t$nightAll (${pct(nightAll, turnsAll)})")
        samples.forEach { println("ФРАЗА\t$it") }
    }

    private fun pct(a: Int, b: Int) = if (b == 0) "—" else "${a * 100 / b}%"
}
