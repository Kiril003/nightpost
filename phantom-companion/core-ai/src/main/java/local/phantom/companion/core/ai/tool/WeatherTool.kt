package local.phantom.companion.core.ai.tool

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.external.OpenMeteoClient

/**
 * W1-5 — function-calling style helper backing the `weather` widget.
 *
 * AI is instructed (via the system prompt) that it can emit a
 * placeholder request like `{"type":"weather","payload":{"request":"current"}}`
 * when it doesn't have current data. The phone-side intercepts that
 * placeholder, fetches real values from Open-Meteo for the operator's
 * actual coords, and re-emits a populated `weather` widget payload
 * the [WeatherWidget] composable can render directly.
 *
 * The «re-prompt the AI with the data» variant from full-blown
 * function-calling pipelines is intentionally elided here: the
 * widget renders the hours array directly, so a second LLM round-
 * trip costs latency without adding visible UX. Phase 3's tool-use
 * router can layer on top if/when the AI needs to comment on the
 * fetched data.
 *
 * TODO(Phase 3): full function-calling re-prompt loop — feed the
 * fetched hours[] back to the AI as a tool result so it can comment
 * («буде дощити вдень — візьми парасолю») on top of the chart. Today
 * this just hydrates the widget; the AI never sees the values.
 */
class WeatherTool(
    private val openMeteoClient: OpenMeteoClient,
    private val locationProvider: LocationContextProvider,
    json: Json? = null,
) {
    private val json: Json = json ?: DEFAULT_JSON

    /**
     * @return [WeatherFetchResult.Hydrated] with a JSON-encoded
     *         [WeatherPayload] string when both coords and OpenMeteo
     *         resolved; [WeatherFetchResult.NoLocation] when the
     *         operator has no fix (so AI falls back to «у якому
     *         місті ти?»); [WeatherFetchResult.NetworkFailure] when
     *         coords were available but the cloud lookup failed.
     */
    suspend fun fetchCurrent(locationLabel: String? = null): WeatherFetchResult {
        val coords = runCatching { locationProvider.current() }.getOrNull()
            ?: return WeatherFetchResult.NoLocation
        val (lat, lon) = coords
        val snap = openMeteoClient.fetch(lat, lon)
            ?: return WeatherFetchResult.NetworkFailure
        val payload = WeatherPayload(
            location = locationLabel,
            hours = snap.hours.map { h ->
                WeatherPayload.HourPoint(
                    ts = h.tsMs,
                    tempC = h.tempC,
                    windMs = h.windMs,
                    rainMm = h.rainMm,
                )
            },
        )
        return WeatherFetchResult.Hydrated(json.encodeToString(payload), payload.location)
    }

    /**
     * Inspect a raw widget JSON payload string and decide whether it's
     * a placeholder the tool should hydrate. The AI is told to emit
     * `request:"current"` (top-level or under `payload`) when it lacks
     * data; an empty `hours` array also counts as a placeholder.
     *
     * Pure function — no IO. Intentional so the StreamViewModel
     * intercept path can decide synchronously whether to launch the
     * suspend [fetchCurrent] coroutine.
     */
    fun isPlaceholderRequest(rawJson: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(rawJson).jsonObject
        val flat = obj.requestField()
        val nested = (obj["payload"] as? JsonObject)?.requestField()
        val hasRequestMarker = flat != null || nested != null
        val emptyHours = isHoursEmpty(obj) || isHoursEmpty(obj["payload"] as? JsonObject)
        hasRequestMarker || emptyHours
    }.getOrDefault(false)

    private fun JsonObject.requestField(): String? =
        (this["request"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun isHoursEmpty(obj: JsonObject?): Boolean {
        if (obj == null) return false
        val hours = obj["hours"] as? kotlinx.serialization.json.JsonArray ?: return false
        return hours.isEmpty()
    }

    @Serializable
    data class WeatherPayload(
        val location: String? = null,
        val hours: List<HourPoint> = emptyList(),
    ) {
        @Serializable
        data class HourPoint(
            @SerialName("ts") val ts: Long,
            @SerialName("temp_c") val tempC: Float,
            @SerialName("wind_ms") val windMs: Float,
            @SerialName("rain_mm") val rainMm: Float,
        )
    }

    sealed interface WeatherFetchResult {
        data class Hydrated(val payloadJson: String, val location: String?) : WeatherFetchResult
        data object NoLocation : WeatherFetchResult
        data object NetworkFailure : WeatherFetchResult
    }

    companion object {
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
