package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

private fun searchManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    surfaces = setOf(FeatureManifest.Surface.MAP, FeatureManifest.Surface.STREAM),
)

data class SpatialSearchItemOutput(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: String,
    val icon: String,
    val lat: Double?,
    val lon: Double?,
    val distanceM: Int?,
    val compassDir: String?,
    val clockDir: String?,
    val isCritical: Boolean,
)

data class SpatialSearchExecutionResult(
    val query: String,
    val items: List<SpatialSearchItemOutput>,
    val summary: String,
)

/**
 * `spatial.search` — Multi-tiered spatial search across OSM universe categories,
 * Vault bookmarks, coordinates, and tactical assets.
 */
class SpatialSearchTool(
    private val executeSearch: suspend (query: String, lat: Double?, lon: Double?, headingDeg: Float?) -> SpatialSearchExecutionResult?,
) : Tool {

    override val id = "spatial.search"

    override val description =
        "Універсальний просторовий пошук: сотні категорій стійкості OSM (генератори, вода, укриття, медицина, АЗС), збережені точки Vault, геокоординати."

    override val argsSchema: JsonObject = buildJsonObject {
        put("query", JsonPrimitive("string — пошуковий запит (назва, категорія або координати)"))
        put("lat", JsonPrimitive("number? — широта (за замовчуванням поточна)"))
        put("lon", JsonPrimitive("number? — довгота (за замовчуванням поточна)"))
        put("heading_deg", JsonPrimitive("number? — азимут руху для розрахунку годинникового напрямку"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = searchManifest("tool.spatial.search", "Просторовий пошук та таксономія")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val query = args["query"]?.jsonPrimitive?.content ?: ""
        val lat = args["lat"]?.let { it as? JsonPrimitive }?.doubleOrNull
        val lon = args["lon"]?.let { it as? JsonPrimitive }?.doubleOrNull
        val heading = args["heading_deg"]?.let { it as? JsonPrimitive }?.floatOrNull

        val res = executeSearch(query, lat, lon, heading)
        if (res == null) {
            return ToolResult.Err(
                code = "search_failed",
                message = "Помилка виконання просторового пошуку.",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        return ToolResult.Ok(
            output = res.summary,
            structured = buildJsonObject {
                put("query", JsonPrimitive(res.query))
                put("total_found", JsonPrimitive(res.items.size))
                put(
                    "items",
                    buildJsonArray {
                        res.items.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(item.id))
                                    put("title", JsonPrimitive(item.title))
                                    put("subtitle", JsonPrimitive(item.subtitle))
                                    put("kind", JsonPrimitive(item.kind))
                                    item.lat?.let { put("lat", JsonPrimitive(it)) }
                                    item.lon?.let { put("lon", JsonPrimitive(it)) }
                                    item.distanceM?.let { put("distance_m", JsonPrimitive(it)) }
                                    item.compassDir?.let { put("compass_direction", JsonPrimitive(it)) }
                                    item.clockDir?.let { put("clock_direction", JsonPrimitive(it)) }
                                    put("is_critical", JsonPrimitive(item.isCritical))
                                }
                            )
                        }
                    }
                )
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}
