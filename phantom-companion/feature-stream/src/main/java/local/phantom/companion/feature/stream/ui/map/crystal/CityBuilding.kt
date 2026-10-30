package local.phantom.companion.feature.stream.ui.map.crystal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.feature.stream.ui.map.GroundRegister

/**
 * One OSM building, as fetched. Nothing renders these any more — the mesher
 * and the Filament city that consumed them are gone; what survives is the
 * HONESTY they carry, read by the ground-register readout and by
 * [RouteRegisterProfile][local.phantom.companion.feature.stream.ui.map.RouteRegisterProfile].
 *
 * Rings are stored open (no duplicated closing point). `rings[0]` is the
 * outer footprint; any further rings are holes (courtyards). Heights are
 * metres above ground; colors are 0xAARRGGBB ints or null when OSM has
 * no colour tag.
 *
 * [register] is how [heightM] came to be known — an explicit `height` tag
 * is MEASURED, a `building:levels` extrusion is DERIVED, the shared
 * [DEFAULT_HEIGHT_M] fallback is GUESSED. Computed once here so the
 * distinction isn't lost the way it is after [heightM] is resolved to a
 * single float (map-oblast-census.md's whole point: 137 buildings in 139
 * nationally have only a footprint, and a renderer that only keeps the
 * final number can no longer say which 137).
 *
 * [register] is presence-only — tag exists, therefore a register — and
 * that is NOT enough on its own. Mariupol carries the country's richest
 * attribution (96.9% storey coverage, 97.1% roof shapes) and would read as
 * confidently DERIVED almost everywhere by presence alone, while its
 * median edit year is 2020 and only 2.9% of it has been touched since the
 * war reached it — a presence-only reading draws an intact city that
 * hasn't existed since spring 2022. [editedYear] (from Overpass `meta`) is
 * what lets a caller close that gap; this parser does not apply an
 * occupied/front-line downgrade itself (it has no administrative boundary
 * data, deliberately — see [local.phantom.companion.feature.stream.ui.map.RouteRegisterProfile]
 * and the viewport-composition aggregation in MapCanvas.kt, which do).
 */
data class CityBuilding(
    val id: Long,
    val rings: List<List<CityPoint>>,
    val heightM: Float,
    val minHeightM: Float,
    val roofShape: RoofShape,
    val roofHeightM: Float,
    val wallColor: Int?,
    val roofColor: Int?,
    val register: GroundRegister,
    /** Year of the element's last OSM edit, or null when Overpass sent no `timestamp`. */
    val editedYear: Int?,
)

/**
 * The roof families the C2 mesher will actually generate. Everything OSM
 * knows but C2 can't build yet (gambrel, mansard, round…) maps to OTHER,
 * which C2 treats as FLAT until a generator exists.
 */
enum class RoofShape { FLAT, GABLED, HIPPED, PYRAMIDAL, SKILLION, DOME, OTHER }

/**
 * Parses an Overpass `out tags geom` JSON response into [CityBuilding]s.
 *
 * Handles both `way` elements (geometry inline) and `multipolygon`
 * relations (outer/inner member rings, stitching split outer ways back
 * into closed rings — common on large courtyard buildings).
 */
object CityBuildingParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<CityBuilding> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val elements = root["elements"]?.jsonArray ?: return emptyList()
        val buildings = mutableListOf<CityBuilding>()
        for (element in elements) {
            val obj = element as? JsonObject ?: continue
            val parsed = when (obj["type"]?.jsonPrimitive?.content) {
                "way" -> parseWay(obj)
                "relation" -> parseRelation(obj)
                else -> null
            }
            if (parsed != null) buildings += parsed
        }
        return buildings
    }

    private fun parseWay(obj: JsonObject): CityBuilding? {
        val tags = obj["tags"]?.jsonObject ?: return null
        if (!isRenderableBuilding(tags)) return null
        val ring = ringOf(obj["geometry"]?.jsonArray ?: return null) ?: return null
        return build(idOf(obj) ?: return null, listOf(ring), tags, timestampYear(obj))
    }

    private fun parseRelation(obj: JsonObject): CityBuilding? {
        val tags = obj["tags"]?.jsonObject ?: return null
        if (!isRenderableBuilding(tags)) return null
        val members = obj["members"]?.jsonArray ?: return null
        val outerSegments = mutableListOf<List<CityPoint>>()
        val innerSegments = mutableListOf<List<CityPoint>>()
        for (member in members) {
            val m = member as? JsonObject ?: continue
            val points = pointsOf(m["geometry"]?.jsonArray ?: continue)
            if (points.size < 2) continue
            when (m["role"]?.jsonPrimitive?.content) {
                "outer" -> outerSegments += points
                "inner" -> innerSegments += points
            }
        }
        val outer = stitchRings(outerSegments)
        if (outer.isEmpty()) return null
        // v1 keeps ONE outer ring per relation (the largest by vertex
        // count); multi-outer relations are rare and can render as their
        // dominant footprint until C2 grows per-ring support.
        val rings = mutableListOf(outer.maxBy { it.size })
        rings += stitchRings(innerSegments)
        return build(idOf(obj) ?: return null, rings, tags, timestampYear(obj))
    }

    /**
     * Reassembles closed rings from way segments. Overpass returns shared
     * nodes with byte-identical coordinates, so exact endpoint equality is
     * a safe join key. Open leftovers (broken data) are dropped.
     */
    fun stitchRings(segments: List<List<CityPoint>>): List<List<CityPoint>> {
        val pool = segments.filter { it.size >= 2 }.toMutableList()
        val rings = mutableListOf<List<CityPoint>>()
        while (pool.isNotEmpty()) {
            val ring = pool.removeAt(0).toMutableList()
            var extended = true
            while (extended && ring.first() != ring.last()) {
                extended = false
                val end = ring.last()
                val iterator = pool.listIterator()
                while (iterator.hasNext()) {
                    val seg = iterator.next()
                    when (end) {
                        seg.first() -> { ring += seg.drop(1); iterator.remove(); extended = true }
                        seg.last() -> { ring += seg.reversed().drop(1); iterator.remove(); extended = true }
                        else -> continue
                    }
                    break
                }
            }
            if (ring.size >= 4 && ring.first() == ring.last()) {
                rings += ring.dropLast(1)
            }
        }
        return rings
    }

    private fun build(id: Long, rings: List<List<CityPoint>>, tags: JsonObject, editedYear: Int?): CityBuilding? {
        if (rings.isEmpty() || rings[0].size < 3) return null
        val roofHeight = meters(tag(tags, "roof:height"))
            ?: tag(tags, "roof:levels")?.toFloatOrNull()?.times(METERS_PER_LEVEL)
            ?: 0f
        val heightTag = meters(tag(tags, "height"))
        val levelsTag = tag(tags, "building:levels")?.toFloatOrNull()
        val height = heightTag
            ?: levelsTag?.times(METERS_PER_LEVEL)?.plus(roofHeight)
            ?: DEFAULT_HEIGHT_M
        val minHeight = meters(tag(tags, "min_height"))
            ?: tag(tags, "building:min_level")?.toFloatOrNull()?.times(METERS_PER_LEVEL)
            ?: 0f
        val register = when {
            heightTag != null -> GroundRegister.MEASURED
            levelsTag != null -> GroundRegister.DERIVED
            else -> GroundRegister.GUESSED
        }
        return CityBuilding(
            id = id,
            rings = rings,
            heightM = height,
            minHeightM = minHeight,
            roofShape = roofShape(tag(tags, "roof:shape")),
            roofHeightM = roofHeight,
            wallColor = parseOsmColor(tag(tags, "building:colour")),
            roofColor = parseOsmColor(tag(tags, "roof:colour")),
            register = register,
            editedYear = editedYear,
        )
    }

    private fun isRenderableBuilding(tags: JsonObject): Boolean {
        val building = tag(tags, "building") ?: return false
        return building != "no"
    }

    internal fun idOf(obj: JsonObject): Long? = obj["id"]?.jsonPrimitive?.content?.toLongOrNull()

    /** `"2020-05-14T09:12:33Z"` → `2020`, or null when `meta` carried no timestamp. */
    internal fun timestampYear(obj: JsonObject): Int? =
        obj["timestamp"]?.jsonPrimitive?.contentOrNull?.take(4)?.toIntOrNull()

    internal fun tag(tags: JsonObject, key: String): String? =
        tags[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

    internal fun ringOf(geometry: kotlinx.serialization.json.JsonArray): List<CityPoint>? {
        val points = pointsOf(geometry)
        if (points.size < 4 || points.first() != points.last()) return null
        return points.dropLast(1)
    }

    internal fun pointsOf(geometry: kotlinx.serialization.json.JsonArray): List<CityPoint> =
        geometry.mapNotNull { node ->
            val o = node as? JsonObject ?: return@mapNotNull null
            val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
            CityPoint(lat, lon)
        }

    /** "12", "12.5", "12 m", "12m" → metres. Feet and other units are dropped (rare in UA). */
    fun meters(raw: String?): Float? {
        if (raw == null) return null
        val cleaned = raw.lowercase().removeSuffix("m").trim().replace(',', '.')
        if (cleaned.any { !it.isDigit() && it != '.' && it != '-' }) return null
        return cleaned.toFloatOrNull()?.takeIf { it > 0f }
    }

    fun roofShape(raw: String?): RoofShape = when (raw?.lowercase()) {
        null, "flat" -> RoofShape.FLAT
        "gabled" -> RoofShape.GABLED
        "hipped", "half-hipped" -> RoofShape.HIPPED
        "pyramidal" -> RoofShape.PYRAMIDAL
        "skillion", "lean_to" -> RoofShape.SKILLION
        "dome", "onion" -> RoofShape.DOME
        else -> RoofShape.OTHER
    }

    /**
     * OSM colour → 0xAARRGGBB, or null when unknown. Pure Kotlin (not
     * android.graphics.Color) so JVM unit tests exercise the real logic.
     */
    fun parseOsmColor(raw: String?): Int? {
        if (raw == null) return null
        val value = raw.lowercase()
        if (value.startsWith("#")) {
            val hex = value.drop(1)
            return when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("").toIntOrNull(16)?.or(ALPHA_OPAQUE)
                6 -> hex.toIntOrNull(16)?.or(ALPHA_OPAQUE)
                else -> null
            }
        }
        return CSS_COLORS[value]
    }

    private const val ALPHA_OPAQUE = 0xFF shl 24
    private const val METERS_PER_LEVEL = 3.2f
    private const val DEFAULT_HEIGHT_M = 8f

    /** The colour names that actually occur on UA buildings, plus CSS basics. */
    private val CSS_COLORS: Map<String, Int> = mapOf(
        "white" to 0xFFFFFF, "black" to 0x000000, "gray" to 0x808080, "grey" to 0x808080,
        "silver" to 0xC0C0C0, "red" to 0xFF0000, "maroon" to 0x800000, "orange" to 0xFFA500,
        "yellow" to 0xFFFF00, "olive" to 0x808000, "green" to 0x008000, "lime" to 0x00FF00,
        "teal" to 0x008080, "cyan" to 0x00FFFF, "aqua" to 0x00FFFF, "blue" to 0x0000FF,
        "navy" to 0x000080, "purple" to 0x800080, "magenta" to 0xFF00FF, "fuchsia" to 0xFF00FF,
        "brown" to 0xA52A2A, "tan" to 0xD2B48C, "beige" to 0xF5F5DC, "cream" to 0xFFFDD0,
        "ivory" to 0xFFFFF0, "salmon" to 0xFA8072, "pink" to 0xFFC0CB, "gold" to 0xFFD700,
        "khaki" to 0xF0E68C, "lightgray" to 0xD3D3D3, "lightgrey" to 0xD3D3D3,
        "darkgray" to 0xA9A9A9, "darkgrey" to 0xA9A9A9, "lightblue" to 0xADD8E6,
        "lightgreen" to 0x90EE90, "lightyellow" to 0xFFFFE0, "brick" to 0xB22222,
        "terracotta" to 0xE2725B, "sand" to 0xC2B280,
    ).mapValues { it.value or ALPHA_OPAQUE }
}
