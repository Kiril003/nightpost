package local.phantom.companion.core.data.spatial

/**
 * R4.1 — the spatial spine. A **layer is a first-class object**, not a
 * transient draw call: the phantom pushes NAMED layers onto the map, they
 * persist across process death, they are listed / toggled / cleared, and
 * they belong to the thread that produced them.
 *
 * This file is the pure-Kotlin contract shared by three sides so no module
 * has to depend on Room or MapLibre to speak "layer":
 *
 *   - the Room store ([SpatialLayerRepository]) *implements* [SpatialLayerSink];
 *   - the `map.*` tools (core-symbiote) *consume* [SpatialLayerSink];
 *   - the map UI (feature-stream) *observes* the repository's [SpatialLayer] flow.
 *
 * Later phases become the SECOND writer of this same store (R7 Atlas
 * dwell-clustering, R8 M-cards) — never a parallel one. Get the shape right
 * here (the plan's explicit warning: wrong shapes cost two rewrites later).
 */

/** Who authored a layer. Drives glyph family + grouping ("від Фантома"). */
enum class LayerOrigin {
    /** The model pushed it via `map.show` / a world tool. */
    PHANTOM,

    /** The operator saved it by hand (pins, saved POIs). */
    OPERATOR,

    /** A background system (Atlas, WatchTower) produced it. */
    SYSTEM,

    /** Live world data (transit, cameras, aircraft, hazards). */
    WORLD,
}

/** One point on a layer. `detail` is the one-line tap-info (Law 1: real tags only). */
data class SpatialMarker(
    val lat: Double,
    val lon: Double,
    val label: String? = null,
    /** Short kind hint for the operator, e.g. "камера", "трамвай", "літак". */
    val glyph: String? = null,
    /** Optional per-marker hex colour; null → layer default. */
    val color: String? = null,
    /** One-line factual detail surfaced on tap (hours, line no., altitude…). */
    val detail: String? = null,
    /** Optional heading in degrees (aircraft/vehicle) for a rotated glyph. */
    val bearing: Double? = null,
    /**
     * Where this point came from, when that is not «us». null = produced on
     * this device; «спільнота» = synced from another operator (ATLAS-4).
     * Provenance lives in the data so a synced mark can never be shown as a
     * verified fact (D8.1).
     */
    val source: String? = null,
)

/** A polyline (route, transit line). */
data class SpatialLine(
    val points: List<GeoPoint>,
    val color: String? = null,
    val width: Float = 4f,
    val label: String? = null,
)

/** A circle region (radius watch, coverage, alert zone). */
data class SpatialRegion(
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val color: String? = null,
    val label: String? = null,
)

/** Minimal (lat, lon) pair — avoids leaking Pair through serialization. */
data class GeoPoint(val lat: Double, val lon: Double)

/**
 * The input shape callers hand to [SpatialLayerSink.push]. Immutable
 * description of everything a layer draws plus its lifecycle.
 */
data class SpatialLayerSpec(
    val title: String,
    val origin: LayerOrigin,
    /** Free-form family: "poi","route","transit","cameras","aircraft","hazard"… */
    val kind: String,
    val ownerThreadId: String? = null,
    val markers: List<SpatialMarker> = emptyList(),
    val lines: List<SpatialLine> = emptyList(),
    val regions: List<SpatialRegion> = emptyList(),
    /** Layer accent colour (hex) — the chip + default marker tint. */
    val color: String? = null,
    /** Layer glyph hint for the chip. */
    val glyph: String? = null,
    /** Time-to-live in millis; null → persistent. Live world layers set this. */
    val ttlMs: Long? = null,
) {
    /** Total drawable element count — used for the chip subtitle + empty guard. */
    val elementCount: Int get() = markers.size + lines.size + regions.size
}

/** A persisted layer as read back from the store. */
data class SpatialLayer(
    val id: String,
    val spec: SpatialLayerSpec,
    val createdAt: Long,
    val visible: Boolean,
    /** Absolute expiry epoch-ms; null → never expires. */
    val expiresAt: Long?,
) {
    fun isExpired(nowMs: Long): Boolean = expiresAt != null && expiresAt <= nowMs
}

/**
 * A camera-move request the map UI honours.
 *
 * [atMs] exists because the request usually happens BEFORE the map does: a
 * screen that asks for a place and then navigates to the map is the normal
 * shape, and a bufferless event in that gap is simply lost — the map opens
 * wherever it was. The flow replays the last request so the gap is survivable,
 * and the stamp is what stops that replay from re-flying the camera every time
 * the map is opened for an unrelated reason later.
 */
data class FocusTarget(
    val lat: Double,
    val lon: Double,
    val zoom: Double? = null,
    val layerId: String? = null,
    val atMs: Long = System.currentTimeMillis(),
) {
    fun isFresh(nowMs: Long): Boolean = nowMs - atMs <= FOCUS_FRESH_MS

    companion object {
        /**
         * Long enough for a cold map to compose on a weak phone, short enough
         * that yesterday's request is not honoured today.
         */
        const val FOCUS_FRESH_MS = 20_000L
    }
}

/**
 * The write/observe port the `map.*` tools depend on. Kept free of Room and
 * Android so tool unit tests use a trivial in-memory fake.
 */
interface SpatialLayerSink {
    /** Persist a layer, returning its generated id. Empty specs are rejected. */
    suspend fun push(spec: SpatialLayerSpec): String

    /** Toggle a layer's visibility. No-op if the id is unknown. */
    suspend fun setVisible(layerId: String, visible: Boolean)

    /** Remove one layer. Returns true if it existed. */
    suspend fun clear(layerId: String): Boolean

    /**
     * Remove layers for a thread (or all, if [threadId] is null), optionally
     * restricted to one [origin]. Returns the number removed.
     */
    suspend fun clearThread(threadId: String?, origin: LayerOrigin? = null): Int

    /** Current layers for a thread (null → every thread), newest first. */
    suspend fun list(threadId: String?): List<SpatialLayer>

    /** Ask the open map to fly its camera to [target]. */
    suspend fun requestFocus(target: FocusTarget)
}
