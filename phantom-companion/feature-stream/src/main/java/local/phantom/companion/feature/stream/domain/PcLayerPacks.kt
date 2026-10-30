package local.phantom.companion.feature.stream.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.geo.layerpack.StoredLayerPack
import local.phantom.companion.core.net.maplayer.PcLayerCatalogClient
import java.util.Locale

/**
 * Map-symbiosis — the four states one PC-offered layer can be in on this
 * phone, kept deliberately impossible to confuse with each other: this
 * codebase's recurring defect is a control that *looks* alive and does
 * nothing (see `phantom-silently-does-nothing`), and a layer row is exactly
 * such a control if "have it" and "PC offers it" ever render the same.
 *
 *  - [Have]         — a pulled extract sits on the phone right now.
 *  - [Offered]       — the PC's catalog names this layer; nothing pulled yet.
 *  - [Transferring]  — a pull is in flight.
 *  - [Failed]        — the last pull did not finish. [had] carries whatever
 *                       was already stored, so a failed REFRESH never makes
 *                       a working layer look gone — the old copy keeps
 *                       serving until a new one is whole (recovered/
 *                       map-symbiosis.md §5, "mid-transfer death is invisible").
 */
sealed interface PcLayerRowState {
    data class Have(val fetchedAtMs: Long, val bytes: Long, val featureCount: Int) : PcLayerRowState
    data object Offered : PcLayerRowState
    // No progress fraction: fetchExtract reads the whole body in one go
    // (ktor bodyAsText, not a streamed download), so nothing on the wire
    // ever produces a real number here — a field nobody can fill honestly
    // is worse than none (see the panel's own dead percent branch, removed
    // alongside this).
    data object Transferring : PcLayerRowState
    data class Failed(val message: String, val had: Have?) : PcLayerRowState
}

/** One row's full identity for the panel — manifest fields plus derived state. */
data class PcLayerRow(
    val layerId: String,
    val labelUa: String,
    val category: String,
    val state: PcLayerRowState,
)

object PcLayerPacks {

    fun have(pack: StoredLayerPack): PcLayerRowState.Have =
        PcLayerRowState.Have(pack.fetchedAtMs, pack.bytes, pack.featureCount)

    fun rowState(
        stored: StoredLayerPack?,
        inFlight: Boolean,
        lastError: String?,
    ): PcLayerRowState = when {
        inFlight -> PcLayerRowState.Transferring
        lastError != null -> PcLayerRowState.Failed(lastError, stored?.let(::have))
        stored != null -> have(stored)
        else -> PcLayerRowState.Offered
    }

    /**
     * Merge the PC's live catalog with what is already on disk. A layer the
     * phone stored before but the PC no longer lists (catalog changed, PC
     * disabled a layer) still shows as [PcLayerRowState.Have] — data the
     * operator already holds is never hidden just because the source went
     * quiet; it is removed by the operator's own delete action, never by a
     * catalog refresh.
     */
    fun mergeRows(
        catalog: List<PcLayerCatalogClient.PcLayerManifest>,
        stored: List<StoredLayerPack>,
        inFlightLayerId: String?,
        errors: Map<String, String>,
    ): List<PcLayerRow> {
        val storedById = stored.associateBy { it.layerId }
        val catalogIds = catalog.map { it.id }.toSet()
        val fromCatalog = catalog.map { m ->
            PcLayerRow(
                layerId = m.id,
                labelUa = m.name_ua,
                category = m.category,
                state = rowState(
                    stored = storedById[m.id],
                    inFlight = inFlightLayerId == m.id,
                    lastError = errors[m.id],
                ),
            )
        }
        val orphaned = stored.filter { it.layerId !in catalogIds }.map { pack ->
            PcLayerRow(
                layerId = pack.layerId,
                labelUa = pack.labelUa,
                category = pack.category,
                state = rowState(
                    stored = pack,
                    inFlight = inFlightLayerId == pack.layerId,
                    lastError = errors[pack.layerId],
                ),
            )
        }
        return fromCatalog + orphaned
    }

    /** "щойно" / "N хв тому" / … — same bucket scheme as `AlertState.sinceText`
     *  (LiveMapFeeds.kt), so a layer's age reads in the voice the rest of the
     *  map already speaks. */
    fun ageLabel(fetchedAtMs: Long, nowMs: Long): String {
        val mins = ((nowMs - fetchedAtMs) / 60_000L).coerceAtLeast(0)
        return when {
            mins < 1 -> "щойно"
            mins < 60 -> "$mins хв тому"
            mins < 24 * 60 -> "${mins / 60} год тому"
            else -> "${mins / (24 * 60)} дн тому"
        }
    }

    /** Ukrainian-locale decimal so a comma renders, not a dot — same reason
     *  `roadPackOfferLabel` fixes `Locale("uk", "UA")` for its own size text. */
    fun sizeLabel(bytes: Long): String {
        val ua = Locale("uk", "UA")
        return when {
            bytes < 1_000 -> "$bytes Б"
            bytes < 1_000_000 -> "${bytes / 1000} КБ"
            else -> String.format(ua, "%.1f МБ", bytes / 1_000_000.0)
        }
    }

    fun totalSizeLabel(bytes: Long): String = sizeLabel(bytes)

    /**
     * Standard GeoJSON (RFC 7946) → the map's own [SpatialLayerSpec]. Point
     * and LineString geometries render as markers/lines; Polygon and every
     * Multi-geometry or GeometryCollection degrade to nothing rather than
     * guess a shape this layer model has no honest slot for (a circle region
     * is not a polygon) — Law 1, an absent shape beats an invented one. Never
     * throws: a malformed body yields an empty spec, same doctrine as every
     * other parser on this map (an honest empty layer beats a crash).
     */
    fun specFromGeoJson(pack: StoredLayerPack): SpatialLayerSpec {
        val markers = mutableListOf<SpatialMarker>()
        val lines = mutableListOf<SpatialLine>()
        runCatching {
            val root = Json.parseToJsonElement(pack.geoJson).jsonObject
            val features = (root["features"] as? JsonArray).orEmpty()
            for (feature in features) {
                val obj = feature as? JsonObject ?: continue
                val geometry = obj["geometry"] as? JsonObject ?: continue
                val props = obj["properties"] as? JsonObject
                val label = props.stringOrNull("name") ?: props.stringOrNull("label")
                val coords = geometry["coordinates"] as? JsonArray ?: continue
                when ((geometry["type"] as? JsonPrimitive)?.content) {
                    "Point" -> lonLat(coords)?.let { (lat, lon) ->
                        markers += SpatialMarker(lat = lat, lon = lon, label = label, glyph = pack.labelUa)
                    }
                    "LineString" -> {
                        val pts = coords.mapNotNull { lonLat(it as? JsonArray) }
                            .map { (lat, lon) -> GeoPoint(lat, lon) }
                        if (pts.size >= 2) lines += SpatialLine(points = pts, label = label)
                    }
                    // Polygon/MultiPoint/MultiLineString/MultiPolygon/
                    // GeometryCollection: no shape here maps to them honestly.
                    else -> Unit
                }
            }
        }
        return SpatialLayerSpec(
            title = pack.labelUa,
            origin = LayerOrigin.WORLD,
            // Doubles as the SpatialLayer identity a re-pull must replace,
            // not duplicate — namespaced so it never collides with a
            // same-named Overpass MapGroundLayers kind.
            kind = "pc-${pack.layerId}",
            markers = markers,
            lines = lines,
        )
    }

    private fun lonLat(coords: JsonArray?): Pair<Double, Double>? {
        val lon = coords?.getOrNull(0)?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
        val lat = coords.getOrNull(1)?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
        return lat to lon
    }

    private fun JsonObject?.stringOrNull(key: String): String? {
        val v = this?.get(key) as? JsonPrimitive ?: return null
        if (v is JsonNull) return null
        return v.content.takeIf { it.isNotBlank() }
    }
}
