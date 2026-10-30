package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

private fun placeContextManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    surfaces = setOf(FeatureManifest.Surface.MAP, FeatureManifest.Surface.STREAM),
)

data class PlaceContextResult(
    val mode: String,
    val activeAnchor: String?,
    val speedKmh: Double,
    val homeSentryActive: Boolean,
    val suggestedMapMode: String,
    val summary: String,
)

/**
 * M-L01 / M-L03 — `place.context` — Semantic operational place detector and home sentry state.
 */
class PlaceContextTool(
    private val getContext: suspend (lat: Double?, lon: Double?, speedMs: Float?) -> PlaceContextResult?,
) : Tool {

    override val id = "place.context"

    override val description =
        "Визначити семантичний контекст поточної локації (Дім/База, Офіс, Транзит, Незнайома зона, Загроза) та стан дистанційної варти дому."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number? — широта (за замовчуванням поточна)"))
        put("lon", JsonPrimitive("number? — довгота (за замовчуванням поточна)"))
        put("speed_ms", JsonPrimitive("number? — поточна швидкість у м/с"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = placeContextManifest("tool.place.context", "Детектор режиму місця та варта")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val lat = args["lat"]?.let { it as? JsonPrimitive }?.doubleOrNull
        val lon = args["lon"]?.let { it as? JsonPrimitive }?.doubleOrNull
        val speed = args["speed_ms"]?.let { it as? JsonPrimitive }?.floatOrNull

        val res = getContext(lat, lon, speed)
        if (res == null) {
            return ToolResult.Err(
                code = "context_unavailable",
                message = "Не вдалося визначити контекст місця (відсутні геодані).",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        return ToolResult.Ok(
            output = res.summary,
            structured = buildJsonObject {
                put("semantic_mode", JsonPrimitive(res.mode))
                res.activeAnchor?.let { put("active_anchor", JsonPrimitive(it)) }
                put("speed_kmh", JsonPrimitive(res.speedKmh))
                put("home_sentry_active", JsonPrimitive(res.homeSentryActive))
                put("suggested_map_mode", JsonPrimitive(res.suggestedMapMode))
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}
