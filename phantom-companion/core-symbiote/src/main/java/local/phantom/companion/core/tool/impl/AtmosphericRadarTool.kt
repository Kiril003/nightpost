package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

private fun radarManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    surfaces = setOf(FeatureManifest.Surface.MAP, FeatureManifest.Surface.STREAM),
)

/**
 * [observedAtMs] — коли показник виміряно. null означає, що джерело години
 * не назвало: інструмент тоді відмовляє замість друкувати число. Постачальник,
 * який забув поле, дістає відмову, а не тихо публікує вигадку.
 */
data class RadarResult(
    val conditionType: String,
    val reflectivityDbz: Int,
    val temperatureC: Double,
    val windKmh: Double,
    /**
     * Румб, яким дме — або null, коли джерело напрямку не назвало.
     *
     * Nullable з тієї ж причини, що й [observedAtMs]: постачальник без поля
     * мусить про нього мовчати, а не підставляти правдоподібне. До 13.09сюди
     * завжди приходив «Західний», бо на дорозі до інструмента стояла стала 270°.
     */
    val windDir: String?,
    val activeLayer: String?,
    val brief: String,
    val observedAtMs: Long? = null,
)

/**
 * M-F01 / M-F02 — `weather.radar` — Atmospheric radar reflectivity & precipitation overlay query.
 */
class AtmosphericRadarTool(
    private val getRadar: suspend (lat: Double?, lon: Double?) -> RadarResult?,
) : Tool {

    override val id = "weather.radar"

    override val description =
        "Метеорологічний радар: відбивна здатність опадів (dBZ), інтенсивність дощу/грози, вітрові вектори та шар погодного радару на мапі."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number? — широта (за замовчуванням поточна)"))
        put("lon", JsonPrimitive("number? — довгота (за замовчуванням поточна)"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = radarManifest("tool.weather.radar", "Метеорологічний радар мапи")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val lat = args["lat"]?.let { it as? JsonPrimitive }?.doubleOrNull
        val lon = args["lon"]?.let { it as? JsonPrimitive }?.doubleOrNull

        val res = getRadar(lat, lon)
        if (res == null) {
            return ToolResult.Err(
                code = "radar_unavailable",
                message = "Радар мовчить: для цих координат не прийшло жодного виміру, " +
                    "тож підтвердити погоду зараз нічим. Скажи це прямо і не називай чисел; " +
                    "повтори виклик за кілька хвилин.",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        val observedAt = res.observedAtMs
            ?: return ToolResult.Err(
                code = "radar_unsourced",
                message = "Показники прийшли без часу виміру — це не вимір, а припущення, " +
                    "і як погоду його подавати не можна. Скажи, що радар зараз без підтвердження; " +
                    "чисел не переказуй.",
                durationMs = System.currentTimeMillis() - start,
            )

        val ageMin = ((start - observedAt) / 60_000L).coerceAtLeast(0L)
        val freshness = if (ageMin >= STALE_AFTER_MIN) {
            " Це вимір $ageMin хв тому — скажи вголос, якщо оператор питає «саме зараз»."
        } else {
            ""
        }

        return ToolResult.Ok(
            output = res.brief + freshness,
            structured = buildJsonObject {
                put("condition_type", JsonPrimitive(res.conditionType))
                put("reflectivity_dbz", JsonPrimitive(res.reflectivityDbz))
                put("temperature_c", JsonPrimitive(res.temperatureC))
                put("wind_speed_kmh", JsonPrimitive(res.windKmh))
                // Поля немає, якщо напрямку не міряли: порожнє поле модель
                // пропустить, а «Західний» зі стелі вона перекаже як вимір.
                res.windDir?.let { put("wind_direction", JsonPrimitive(it)) }
                put("observed_ts", JsonPrimitive(observedAt))
                put("age_min", JsonPrimitive(ageMin))
                res.activeLayer?.let { put("map_layer", JsonPrimitive(it)) }
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }

    private companion object {
        // Той самий поріг, що й у weather.now — одна межа несвіжості на весь дім.
        const val STALE_AFTER_MIN = 90L
    }
}
