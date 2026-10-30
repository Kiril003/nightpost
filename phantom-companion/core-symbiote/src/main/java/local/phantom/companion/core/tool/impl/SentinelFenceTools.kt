package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

private fun fenceManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    surfaces = setOf(FeatureManifest.Surface.MAP, FeatureManifest.Surface.STREAM),
)

data class FenceItem(
    val id: String,
    val label: String,
    val radiusM: Double,
    val kind: String,
    val lat: Double,
    val lon: Double,
)

/**
 * NAV-Ω2 — `fence.set` — Establish a sovereign geofence perimeter.
 */
class FenceSetTool(
    private val locationProvider: LocationContextProvider?,
    private val setFence: (label: String, radiusM: Double, kind: String, lat: Double, lon: Double) -> String,
) : Tool {

    override val id = "fence.set"

    override val description =
        "Встановити охоронний гео-периметр / геозону (безпечна зона 'safe', зона небезпеки 'hazard', або якірний периметр 'anchor') навколо вказаних або поточних координат."

    override val argsSchema: JsonObject = buildJsonObject {
        put("label", JsonPrimitive("string — назва периметра або обʼєкта"))
        put("radius_m", JsonPrimitive("number? — радіус у метрах (за замовчуванням 500м)"))
        put("kind", JsonPrimitive("string? — тип: 'safe' (вихід карається тривогою), 'hazard' (вхід карається тривогою), 'anchor'"))
        put("lat", JsonPrimitive("number? — широта (за замовчуванням поточна)"))
        put("lon", JsonPrimitive("number? — довгота (за замовчуванням поточна)"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = fenceManifest("tool.fence.set", "Встановити гео-периметр")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val label = args["label"]?.jsonPrimitive?.content ?: "Периметр"
        val radius = args["radius_m"]?.jsonPrimitive?.doubleOrNull ?: 500.0
        val kindStr = args["kind"]?.jsonPrimitive?.content?.lowercase() ?: "safe"

        val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
            ?: locationProvider?.current()?.lat
        val lon = args["lon"]?.jsonPrimitive?.doubleOrNull
            ?: locationProvider?.current()?.lon

        if (lat == null || lon == null) {
            return ToolResult.Err(
                code = "location_unavailable",
                message = "Не вдалося отримати координати для встановлення геозони.",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        val fenceId = setFence(label, radius, kindStr, lat, lon)

        val kindUk = when (kindStr) {
            "hazard" -> "зона небезпеки"
            "anchor" -> "якірний периметр"
            else -> "безпечна зона"
        }

        return ToolResult.Ok(
            output = "Встановлено $kindUk «$label» радіусом ${radius.toInt()} м довкола ($lat, $lon).",
            structured = buildJsonObject {
                put("id", JsonPrimitive(fenceId))
                put("label", JsonPrimitive(label))
                put("radius_m", JsonPrimitive(radius))
                put("kind", JsonPrimitive(kindStr))
                put("lat", JsonPrimitive(lat))
                put("lon", JsonPrimitive(lon))
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}

/**
 * NAV-Ω2 — `fence.list` — List active sovereign geofences.
 */
class FenceListTool(
    private val listFences: () -> List<FenceItem>,
) : Tool {

    override val id = "fence.list"

    override val description = "Отримати перелік усіх активних охоронних геозон та периметрів."

    override val argsSchema: JsonObject = buildJsonObject {
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = fenceManifest("tool.fence.list", "Список гео-периметрів")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val list = listFences()

        return ToolResult.Ok(
            output = if (list.isEmpty()) {
                "Активних геозон немає."
            } else {
                "Активних геозон: ${list.size}.\n" + list.joinToString("\n") {
                    "• «${it.label}» (${it.kind}, радіус ${it.radiusM.toInt()} м)"
                }
            },
            structured = buildJsonObject {
                put("count", JsonPrimitive(list.size))
                put(
                    "fences",
                    JsonArray(
                        list.map { f ->
                            buildJsonObject {
                                put("id", JsonPrimitive(f.id))
                                put("label", JsonPrimitive(f.label))
                                put("radius_m", JsonPrimitive(f.radiusM))
                                put("kind", JsonPrimitive(f.kind))
                                put("lat", JsonPrimitive(f.lat))
                                put("lon", JsonPrimitive(f.lon))
                            }
                        },
                    ),
                )
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}
