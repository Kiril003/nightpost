package local.phantom.companion.feature.stream.ui.map.crystal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Crystal City C2 — the green half of a tile: areas OSM says are planted,
 * and the individual trees OSM has actually surveyed.
 *
 * Kept apart from [CityBuilding] because the two answer different questions:
 * a building is a shape we extrude, a green area is a shape we *populate*.
 * [GreenKind] carries only what changes the population — how densely trees
 * stand and how tall they grow.
 */
enum class GreenKind { PARK, GRASS, WOOD }

data class CityGreen(
    val id: Long,
    val rings: List<List<CityPoint>>,
    val kind: GreenKind,
)

data class CityGreenery(
    val areas: List<CityGreen>,
    val trees: List<CityPoint>,
) {
    val isEmpty: Boolean get() = areas.isEmpty() && trees.isEmpty()

    companion object {
        val EMPTY = CityGreenery(emptyList(), emptyList())
    }
}

/**
 * Parses the greenery elements out of the same Overpass response
 * [CityBuildingParser] reads buildings from — one request, two harvests.
 *
 * A tile cached before greenery was asked for simply yields
 * [CityGreenery.EMPTY] until its week-long TTL expires, so no cache
 * migration is needed.
 */
object CityGreenParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): CityGreenery {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return CityGreenery.EMPTY
        val elements = root["elements"]?.jsonArray ?: return CityGreenery.EMPTY
        val areas = mutableListOf<CityGreen>()
        val trees = mutableListOf<CityPoint>()
        for (element in elements) {
            val obj = element as? JsonObject ?: continue
            when (obj["type"]?.jsonPrimitive?.content) {
                "node" -> parseTree(obj)?.let { trees += it }
                "way" -> parseWay(obj)?.let { areas += it }
                "relation" -> parseRelation(obj)?.let { areas += it }
            }
        }
        return CityGreenery(areas, trees)
    }

    private fun parseTree(obj: JsonObject): CityPoint? {
        val tags = obj["tags"]?.jsonObject ?: return null
        if (CityBuildingParser.tag(tags, "natural") != "tree") return null
        val lat = obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val lon = obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        return CityPoint(lat, lon)
    }

    private fun parseWay(obj: JsonObject): CityGreen? {
        val tags = obj["tags"]?.jsonObject ?: return null
        val kind = kindOf(tags) ?: return null
        val id = CityBuildingParser.idOf(obj) ?: return null
        val ring = CityBuildingParser.ringOf(obj["geometry"]?.jsonArray ?: return null) ?: return null
        return CityGreen(id, listOf(ring), kind)
    }

    private fun parseRelation(obj: JsonObject): CityGreen? {
        val tags = obj["tags"]?.jsonObject ?: return null
        val kind = kindOf(tags) ?: return null
        val id = CityBuildingParser.idOf(obj) ?: return null
        val members = obj["members"]?.jsonArray ?: return null
        val outerSegments = mutableListOf<List<CityPoint>>()
        val innerSegments = mutableListOf<List<CityPoint>>()
        for (member in members) {
            val m = member as? JsonObject ?: continue
            val points = CityBuildingParser.pointsOf(m["geometry"]?.jsonArray ?: continue)
            if (points.size < 2) continue
            when (m["role"]?.jsonPrimitive?.content) {
                "outer" -> outerSegments += points
                "inner" -> innerSegments += points
            }
        }
        val outer = CityBuildingParser.stitchRings(outerSegments)
        if (outer.isEmpty()) return null
        val rings = mutableListOf(outer.maxBy { it.size })
        rings += CityBuildingParser.stitchRings(innerSegments)
        return CityGreen(id, rings, kind)
    }

    fun kindOf(tags: JsonObject): GreenKind? {
        if (CityBuildingParser.tag(tags, "building") != null) return null
        CityBuildingParser.tag(tags, "leisure")?.let { leisure ->
            when (leisure) {
                "park" -> return GreenKind.PARK
                "garden" -> return GreenKind.PARK
                else -> Unit
            }
        }
        CityBuildingParser.tag(tags, "landuse")?.let { landuse ->
            when (landuse) {
                "forest" -> return GreenKind.WOOD
                "grass", "meadow", "village_green", "recreation_ground" -> return GreenKind.GRASS
                else -> Unit
            }
        }
        CityBuildingParser.tag(tags, "natural")?.let { natural ->
            when (natural) {
                "wood" -> return GreenKind.WOOD
                "scrub", "grassland" -> return GreenKind.GRASS
                else -> Unit
            }
        }
        return null
    }
}
