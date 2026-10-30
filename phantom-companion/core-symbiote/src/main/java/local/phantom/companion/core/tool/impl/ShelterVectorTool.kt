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

private fun shelterManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    surfaces = setOf(FeatureManifest.Surface.MAP, FeatureManifest.Surface.STREAM),
)

data class ShelterVectorResult(
    val name: String,
    val distanceM: Int,
    val bearingDeg: Int,
    val compassDir: String,
    val clockDir: String?,
    val walkEtaMin: Int,
    val runEtaMin: Int,
    val instruction: String,
)

/**
 * M-N01 — `shelter.vector` — Tactical vector guidance towards the nearest bomb shelter.
 */
class ShelterVectorTool(
    private val getVector: suspend (lat: Double?, lon: Double?, headingDeg: Float?) -> ShelterVectorResult?,
) : Tool {

    override val id = "shelter.vector"

    override val description =
        "Отримати точний тактичний азимут, дистанцію, час добігання та годинниковий напрямок до найближчого бомбосховища/укриття."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number? — широта (за замовчуванням поточна)"))
        put("lon", JsonPrimitive("number? — довгота (за замовчуванням поточна)"))
        put("heading_deg", JsonPrimitive("number? — поточний азимут руху для розрахунку годинникового напрямку (0..360)"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = shelterManifest("tool.shelter.vector", "Вектор до найближчого укриття")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val lat = args["lat"]?.let { it as? JsonPrimitive }?.doubleOrNull
        val lon = args["lon"]?.let { it as? JsonPrimitive }?.doubleOrNull
        val heading = args["heading_deg"]?.let { it as? JsonPrimitive }?.floatOrNull

        val res = getVector(lat, lon, heading)
        if (res == null) {
            return ToolResult.Err(
                code = "no_shelters_found",
                message = "У радіусі пошуку укриттів не виявлено або відсутні координати.",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        return ToolResult.Ok(
            output = res.instruction,
            structured = buildJsonObject {
                put("shelter_name", JsonPrimitive(res.name))
                put("distance_m", JsonPrimitive(res.distanceM))
                put("bearing_deg", JsonPrimitive(res.bearingDeg))
                put("compass_direction", JsonPrimitive(res.compassDir))
                res.clockDir?.let { put("clock_direction", JsonPrimitive(it)) }
                put("walk_eta_min", JsonPrimitive(res.walkEtaMin))
                put("run_eta_min", JsonPrimitive(res.runEtaMin))
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}
