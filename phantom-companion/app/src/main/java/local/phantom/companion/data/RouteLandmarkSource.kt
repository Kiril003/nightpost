package local.phantom.companion.data

import local.phantom.companion.core.data.poi.PoiGazetteerDao
import local.phantom.companion.core.geo.sky.SolarAlmanac
import local.phantom.companion.core.nav.Landmark
import local.phantom.companion.core.nav.LandmarkField
import local.phantom.companion.core.nav.LandmarkSource
import local.phantom.companion.core.nav.RouteLine
import local.phantom.companion.core.nav.landmarkOf
import local.phantom.companion.core.nav.routeLineKindOf
import local.phantom.companion.core.sensor.nav.RoadMeshStore
import local.phantom.companion.core.sensor.nav.stopsIn
import kotlin.math.cos

/** Орієнтири біля маневрів — лише з того, що вже лежить на пристрої: місця, дороги, зупинки. */
class RouteLandmarkSource(
    private val gazetteer: () -> PoiGazetteerDao,
    private val mesh: () -> RoadMeshStore,
) : LandmarkSource {

    private val lineCache = object : LinkedHashMap<String, List<Pair<Long, RouteLine>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<Long, RouteLine>>>?) = size > 24
    }

    override suspend fun near(anchors: List<Pair<Double, Double>>, radiusM: Double): LandmarkField {
        val places = LinkedHashMap<String, Landmark>()
        val lines = LinkedHashMap<Long, RouteLine>()
        val stopSeen = HashSet<Long>()
        for ((lat, lon) in anchors) {
            val dLat = radiusM / 111_320.0
            val dLon = radiusM / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
            runCatching {
                gazetteer().inBox(lat - dLat, lat + dLat, lon - dLon, lon + dLon, PLACES_PER_ANCHOR)
            }.getOrDefault(emptyList()).forEach { p ->
                val lm = landmarkOf(p.category, p.subcategory, p.name, p.lat, p.lon, p.tags["opening_hours"]) ?: return@forEach
                places.putIfAbsent("${lm.kind}|${lm.name}|${"%.5f".format(p.lat)}|${"%.5f".format(p.lon)}", lm)
            }
            runCatching { mesh().stopsIn(lat - dLat, lon - dLon, lat + dLat, lon + dLon) }
                .getOrDefault(emptyList())
                .forEach { s ->
                    if (!stopSeen.add(s.id)) return@forEach
                    val lm = landmarkOf("highway", "bus_stop", s.name, s.lat, s.lon, null) ?: return@forEach
                    places.putIfAbsent("stop|${s.id}", lm)
                }
            val key = RoadMeshStore.cellsCovering(lat - dLat, lon - dLon, lat + dLat, lon + dLon).sorted().joinToString(",")
            val cellLines = synchronized(lineCache) { lineCache[key] } ?: runCatching {
                mesh().waysIn(lat - dLat, lon - dLon, lat + dLat, lon + dLon)
                    .mapNotNull { w -> routeLineKindOf(w.tags)?.let { w.id to RouteLine(it, w.points) } }
            }.getOrDefault(emptyList()).also { synchronized(lineCache) { lineCache[key] = it } }
            cellLines.forEach { (id, l) -> lines.putIfAbsent(id, l) }
        }
        return LandmarkField(places.values.toList(), lines.values.toList())
    }

    companion object {
        private const val PLACES_PER_ANCHOR = 400

        /** Сонце нижче за −4°: вітрини без світла вже не видно з дороги. */
        fun darkAt(lat: Double, lon: Double, epochMs: Long): Boolean =
            SolarAlmanac.altitudeDeg(epochMs, lat, lon) < -4.0
    }
}
