package local.phantom.companion.core.data.spatial

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * R4.1 — the single Room-backed home for spatial layers, implementing the
 * pure [SpatialLayerSink] port. Geometry rides through a compact
 * [@Serializable][Serializable] snapshot in one JSON column; the primitive
 * columns stay queryable.
 *
 * TTL discipline: every write and every read sweeps expired rows so a live
 * world layer (aircraft, hazard) never lingers past its window. The UI also
 * filters client-side against a live clock as a second guard.
 */
class SpatialLayerRepository(
    private val dao: SpatialLayerDao,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : SpatialLayerSink {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // replay = 1: the asker is usually a screen that then navigates TO the map,
    // so the request is made before anything is collecting. FocusTarget.atMs is
    // what keeps that replay from being a camera jump on some later, unrelated
    // opening of the map.
    private val _focus = MutableSharedFlow<FocusTarget>(replay = 1, extraBufferCapacity = 4)

    /** Camera-move requests from `map.focus` / tool layers; the map collects. */
    val focusRequests: SharedFlow<FocusTarget> = _focus.asSharedFlow()

    /** Live, expiry-filtered layer list for the map UI (newest first). */
    val layers: Flow<List<SpatialLayer>> = dao.observeAll().map { rows ->
        val now = clockMs()
        rows.map { it.toDomain() }.filterNot { it.isExpired(now) }
    }

    override suspend fun push(spec: SpatialLayerSpec): String {
        require(spec.elementCount > 0) { "refusing to push an empty layer (Law 1)" }
        sweep()
        val now = clockMs()
        val id = UUID.randomUUID().toString()
        dao.upsert(
            SpatialLayerEntity(
                id = id,
                title = spec.title,
                origin = spec.origin.name,
                kind = spec.kind,
                ownerThreadId = spec.ownerThreadId,
                geometryJson = json.encodeToString(spec.toGeometry()),
                color = spec.color,
                glyph = spec.glyph,
                createdAt = now,
                expiresAt = spec.ttlMs?.let { now + it },
                visible = true,
            ),
        )
        return id
    }

    override suspend fun setVisible(layerId: String, visible: Boolean) {
        dao.setVisible(layerId, visible)
    }

    override suspend fun clear(layerId: String): Boolean = dao.deleteById(layerId) > 0

    override suspend fun clearThread(threadId: String?, origin: LayerOrigin?): Int = when {
        threadId == null && origin == null -> dao.deleteAll()
        threadId == null -> dao.deleteByOrigin(origin!!.name)
        origin == null -> dao.deleteForThread(threadId)
        else -> dao.deleteForThreadOrigin(threadId, origin.name)
    }

    override suspend fun list(threadId: String?): List<SpatialLayer> {
        sweep()
        val rows = if (threadId == null) dao.getAll() else dao.getForThread(threadId)
        val now = clockMs()
        return rows.map { it.toDomain() }.filterNot { it.isExpired(now) }
    }

    override suspend fun requestFocus(target: FocusTarget) {
        _focus.emit(target)
    }

    private suspend fun sweep() {
        runCatching { dao.deleteExpired(clockMs()) }
    }

    // ── mapping ──────────────────────────────────────────────────────────

    private fun SpatialLayerEntity.toDomain(): SpatialLayer {
        val geo = runCatching { json.decodeFromString<Geometry>(geometryJson) }
            .getOrDefault(Geometry())
        return SpatialLayer(
            id = id,
            spec = SpatialLayerSpec(
                title = title,
                origin = runCatching { LayerOrigin.valueOf(origin) }.getOrDefault(LayerOrigin.PHANTOM),
                kind = kind,
                ownerThreadId = ownerThreadId,
                markers = geo.markers.map { it.toMarker() },
                lines = geo.lines.map { it.toLine() },
                regions = geo.regions.map { it.toRegion() },
                color = color,
                glyph = glyph,
                ttlMs = expiresAt?.let { it - createdAt },
            ),
            createdAt = createdAt,
            visible = visible,
            expiresAt = expiresAt,
        )
    }

    private fun SpatialLayerSpec.toGeometry() = Geometry(
        markers = markers.map {
            GMarker(it.lat, it.lon, it.label, it.glyph, it.color, it.detail, it.bearing, it.source)
        },
        lines = lines.map { GLine(it.points.map { p -> listOf(p.lat, p.lon) }, it.color, it.width, it.label) },
        regions = regions.map { GRegion(it.lat, it.lon, it.radiusM, it.color, it.label) },
    )

    // Compact serialization shapes — lists of primitives keep the JSON small.
    @Serializable
    private data class Geometry(
        val markers: List<GMarker> = emptyList(),
        val lines: List<GLine> = emptyList(),
        val regions: List<GRegion> = emptyList(),
    )

    @Serializable
    private data class GMarker(
        val lat: Double, val lon: Double,
        val label: String? = null, val glyph: String? = null,
        val color: String? = null, val detail: String? = null,
        val bearing: Double? = null, val source: String? = null,
    ) {
        fun toMarker() = SpatialMarker(lat, lon, label, glyph, color, detail, bearing, source)
    }

    @Serializable
    private data class GLine(
        val points: List<List<Double>> = emptyList(),
        val color: String? = null, val width: Float = 4f, val label: String? = null,
    ) {
        fun toLine() = SpatialLine(
            points = points.mapNotNull { if (it.size >= 2) GeoPoint(it[0], it[1]) else null },
            color = color, width = width, label = label,
        )
    }

    @Serializable
    private data class GRegion(
        val lat: Double, val lon: Double, val radiusM: Double,
        val color: String? = null, val label: String? = null,
    ) {
        fun toRegion() = SpatialRegion(lat, lon, radiusM, color, label)
    }
}
