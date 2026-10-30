package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.TilePlaces
import local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Point

/** Збирає з уже завантажених плиток місця, які мапа малює, щоб пошук і «Поруч» їх знали. */
internal class TilePlaceHarvest {
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastAtMs = 0L

    fun due(lat: Double, lon: Double, zoom: Double, nowMs: Long): Boolean {
        if (zoom < MIN_ZOOM) return false
        if (lastLat.isNaN()) return true
        return nowMs - lastAtMs >= REPEAT_MS || haversineMeters(lastLat, lastLon, lat, lon) >= MOVE_M
    }

    fun mark(lat: Double, lon: Double, nowMs: Long) {
        lastLat = lat
        lastLon = lon
        lastAtMs = nowMs
    }

    companion object {
        const val MIN_ZOOM = 13.0
        const val MOVE_M = 300.0
        const val REPEAT_MS = 60_000L
        private const val MAX_PLACES = 3_000
        private const val SOURCE_ID = "openmaptiles"
        private const val SOURCE_LAYER = "poi"
        private val KEYS = listOf("name", "name:uk", "name:latin", "name_int", "name:en", "class", "subclass")

        // querySourceFeatures іде на головному потоці: лише на простої камери і не частіше за due().
        fun read(map: MapLibreMap): List<PoiEntity> {
            val source = map.style?.getSourceAs<VectorSource>(SOURCE_ID) ?: return emptyList()
            val features = runCatching { source.querySourceFeatures(arrayOf(SOURCE_LAYER), Expression.has("name")) }
                .getOrNull() ?: return emptyList()
            val out = LinkedHashMap<Long, PoiEntity>()
            for (f in features) {
                if (out.size >= MAX_PLACES) break
                val point = f.geometry() as? Point ?: continue
                val props = HashMap<String, String>(8)
                for (key in KEYS) {
                    if (f.hasNonNullValueForProperty(key)) runCatching { f.getStringProperty(key) }.getOrNull()?.let { props[key] = it }
                }
                val place = TilePlaces.entity(point.latitude(), point.longitude(), props) ?: continue
                out.putIfAbsent(place.id, place)
            }
            return out.values.toList()
        }
    }
}
