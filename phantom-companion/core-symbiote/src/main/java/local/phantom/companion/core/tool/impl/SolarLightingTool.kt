package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

private fun solarManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    surfaces = setOf(FeatureManifest.Surface.MAP, FeatureManifest.Surface.STREAM),
)

data class SolarLightingResult(
    val elevationDeg: Double,
    val azimuthDeg: Double,
    val phase: String,
    val intensityLux: Float,
    val colorTempK: Int,
    val description: String,
)

/**
 * Ω-3 TERRAIN — `solar.lighting` — Astronomical solar elevation, azimuth, and 3D lighting telemetry.
 */
class SolarLightingTool(
    private val getSolar: suspend (lat: Double?, lon: Double?, timeMs: Long?) -> SolarLightingResult?,
) : Tool {

    override val id = "solar.lighting"

    override val description =
        "Астрономічний розрахунок положення сонця (висота над горизонтом, азимут), фази доби (золота година, сутінки, ніч) та умов 3D-освітлення мапи."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number? — широта (за замовчуванням поточна)"))
        put("lon", JsonPrimitive("number? — довгота (за замовчуванням поточна)"))
        put("time_ms", JsonPrimitive("number? — час UTC в мілісекундах (за замовчуванням зараз)"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = solarManifest("tool.solar.lighting", "Сонце та астрономічне освітлення")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val lat = args["lat"]?.let { it as? JsonPrimitive }?.doubleOrNull
        val lon = args["lon"]?.let { it as? JsonPrimitive }?.doubleOrNull
        val timeMs = args["time_ms"]?.let { it as? JsonPrimitive }?.longOrNull

        val res = getSolar(lat, lon, timeMs)
        if (res == null) {
            return ToolResult.Err(
                code = "solar_calc_unavailable",
                message = "Не вдалося розрахувати астрономічне положення сонця (відсутні геокоординати).",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        return ToolResult.Ok(
            output = res.description,
            structured = buildJsonObject {
                put("elevation_deg", JsonPrimitive(res.elevationDeg))
                put("azimuth_deg", JsonPrimitive(res.azimuthDeg))
                put("solar_phase", JsonPrimitive(res.phase))
                put("direct_light_lux", JsonPrimitive(res.intensityLux))
                put("color_temperature_k", JsonPrimitive(res.colorTempK))
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}
