package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.external.OpenMeteoClient
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.Locale

/**
 * R1.2 — AI-callable weather tools (kill fake weather).
 *
 * Unlike the W1-5 widget-hydration `core.ai.tool.WeatherTool` (which
 * fills a `weather` widget the model already emitted), these are real
 * function-calling tools: the MODEL sees the numbers and can reason
 * about them («буде дощ о 16 — вийди раніше»).
 *
 * Coordinates default to the operator's current location; the model
 * may override with explicit `lat`/`lon` (e.g. «погода у Львові» after
 * a `geo.search`). Honest degradation (Law 1): no coords ⇒
 * `no_location`, Open-Meteo down ⇒ `weather_unavailable` — never
 * invented numbers.
 */
private fun weatherManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 1,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    abusableAgainstWeak = false,
    sunsetAfterDaysIdle = 90,
    surfaces = setOf(FeatureManifest.Surface.STREAM),
)

internal fun resolveCoords(
    args: JsonObject,
    locationProvider: LocationContextProvider?,
): Pair<Double, Double>? {
    val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
    val lon = args["lon"]?.jsonPrimitive?.doubleOrNull
    if (lat != null && lon != null) return lat to lon
    val fix = runCatching { locationProvider?.current() }.getOrNull() ?: return null
    return fix.lat to fix.lon
}

/** `weather.now` — current conditions + next hours, real Open-Meteo data. */
class WeatherNowTool(
    private val openMeteo: OpenMeteoClient,
    private val locationProvider: LocationContextProvider?,
) : Tool {

    override val id = "weather.now"

    override val description =
        "Реальна погода зараз і на найближчі години (Open-Meteo): температура, вітер, опади. За замовчуванням — поточна локація оператора."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("hours", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = weatherManifest("tool.weather.now", "Погода зараз")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }
        val (lat, lon) = resolveCoords(args, locationProvider)
            ?: return ToolResult.Err(
                code = "no_location",
                message = "немає координат: локація вимкнена і lat/lon не передані. " +
                    "Наступний крок — знайди місто через geo.search і передай його lat/lon сюди.",
                durationMs = elapsed(),
            )
        val hours = (args["hours"]?.jsonPrimitive?.intOrNull ?: 12).coerceIn(1, 48)
        val snap = openMeteo.fetch(lat, lon, hourCount = hours)
            ?: return ToolResult.Err(
                code = "weather_unavailable",
                message = "Open-Meteo не відповів — погоди зараз немає. Скажи це оператору прямо, " +
                    "числа не вигадуй; повтори виклик за кілька хвилин.",
                durationMs = elapsed(),
            )
        val now = snap.hours.firstOrNull()
            ?: return ToolResult.Err(
                code = "weather_empty",
                message = "Open-Meteo віддав відповідь без жодної години — показувати нічого. " +
                    "Повтори виклик; чисел не вигадуй.",
                durationMs = elapsed(),
            )
        // Readings are hourly and the client caches an hour, so «зараз» can be up
        // to two hours old. The model must be able to see that, not guess it.
        val ageMin = ((startMs - now.tsMs) / 60_000L).coerceAtLeast(0L)
        val structured = buildJsonObject {
            put("lat", lat)
            put("lon", lon)
            snap.timezone?.let { put("timezone", it) }
            put("temp_c", now.tempC)
            put("wind_ms", now.windMs)
            put("rain_mm", now.rainMm)
            put("observed_ts", now.tsMs)
            put("age_min", ageMin)
            put("granularity", "погодинна")
            put("source", "Open-Meteo")
            put("hours", buildJsonArray {
                snap.hours.forEach { h ->
                    add(buildJsonObject {
                        put("ts", h.tsMs)
                        put("temp_c", h.tempC)
                        put("wind_ms", h.windMs)
                        put("rain_mm", h.rainMm)
                    })
                }
            })
        }
        val freshness = if (ageMin >= 90L) {
            " Це вимір $ageMin хв тому — скажи вголос, якщо оператор питає «саме зараз»."
        } else {
            ""
        }
        val human = String.format(
            Locale.US,
            "Зараз ≈%.0f°C, вітер %.1f м/с, опади %.1f мм; далі %d год у структурі. Джерело — Open-Meteo, крок година.",
            now.tempC, now.windMs, now.rainMm, snap.hours.size,
        ) + freshness
        return ToolResult.Ok(
            output = human,
            structured = structured,
            durationMs = elapsed(),
        )
    }
}

/** `weather.forecast` — multi-day daily forecast, real Open-Meteo data. */
class WeatherForecastTool(
    private val openMeteo: OpenMeteoClient,
    private val locationProvider: LocationContextProvider?,
) : Tool {

    override val id = "weather.forecast"

    override val description =
        "Прогноз погоди на кілька днів (Open-Meteo): min/max температура, опади, ймовірність дощу, вітер. days: 1–7 (типово 3)."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("days", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)

    override val risk: ToolRisk = ToolRisk.READ

    override val streaming: Boolean = false

    override val manifest = weatherManifest("tool.weather.forecast", "Прогноз погоди")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }
        val (lat, lon) = resolveCoords(args, locationProvider)
            ?: return ToolResult.Err(
                code = "no_location",
                message = "немає координат: локація вимкнена і lat/lon не передані. " +
                    "Наступний крок — знайди місто через geo.search і передай його lat/lon сюди.",
                durationMs = elapsed(),
            )
        val days = (args["days"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 7)
        val snap = openMeteo.fetchDaily(lat, lon, days = days)
            ?: return ToolResult.Err(
                code = "weather_unavailable",
                message = "Open-Meteo не відповів — прогнозу зараз немає. Скажи це оператору прямо, " +
                    "числа не вигадуй; повтори виклик за кілька хвилин.",
                durationMs = elapsed(),
            )
        val first = snap.days.firstOrNull()
            ?: return ToolResult.Err(
                code = "weather_empty",
                message = "Open-Meteo віддав прогноз без жодного дня — показувати нічого. " +
                    "Повтори виклик; чисел не вигадуй.",
                durationMs = elapsed(),
            )
        val structured = buildJsonObject {
            put("lat", lat)
            put("lon", lon)
            snap.timezone?.let { put("timezone", it) }
            put("source", "Open-Meteo")
            put("fetched_at_ms", startMs)
            put("days_requested", days)
            put("days_returned", snap.days.size)
            put("confidence", "прогноз: точність падає з кожним днем")
            put("days", buildJsonArray {
                snap.days.forEach { d ->
                    add(buildJsonObject {
                        put("date", d.dateIso)
                        put("temp_min_c", d.tempMinC)
                        put("temp_max_c", d.tempMaxC)
                        put("rain_mm", d.rainMm)
                        d.rainProbPct?.let { put("rain_prob_pct", it) }
                        put("wind_max_ms", d.windMaxMs)
                    })
                }
            })
        }
        val short = if (snap.days.size < days) " Запитано $days дн., джерело дало ${snap.days.size}." else ""
        val human = String.format(
            Locale.US,
            "%s: %.0f…%.0f°C, опади %.1f мм; всього %d дн. у структурі. Джерело — Open-Meteo, це прогноз.",
            first.dateIso, first.tempMinC, first.tempMaxC, first.rainMm, snap.days.size,
        ) + short
        return ToolResult.Ok(
            output = human,
            structured = structured,
            durationMs = elapsed(),
        )
    }
}
