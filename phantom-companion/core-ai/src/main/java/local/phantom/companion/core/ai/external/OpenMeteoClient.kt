package local.phantom.companion.core.ai.external

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * W1-5 — pure HTTP client for the public Open-Meteo forecast endpoint
 * (https://api.open-meteo.com/v1/forecast). No API key, anonymous.
 *
 * Used by [local.phantom.companion.core.ai.tool.WeatherTool] to back
 * generative `weather` widgets with real values for the operator's
 * actual coords (or a city's coords) instead of whatever placeholder
 * the AI fabricated from the system-prompt example.
 *
 * Behaviour:
 *  - 5-second per-request timeout including connect.
 *  - Single attempt; the call site (WeatherTool) decides whether to
 *    retry. Retries inside the client would risk rate-limit blowback.
 *  - One-hour in-memory cache keyed by (latRounded, lonRounded). Two
 *    operators ~10km apart on the same hour share a cache hit so a
 *    chatty Stream doesn't spam Open-Meteo.
 *  - Returns null on any failure (timeout, non-2xx, parse error,
 *    fewer-than-12-hours response) so callers can render a graceful
 *    fallback instead of a half-formed widget.
 */
class OpenMeteoClient(
    engine: HttpClientEngine? = null,
    private val timeoutMs: Long = 5_000L,
    private val cacheTtlMs: Long = 60L * 60L * 1000L,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val endpointUrl: String = DEFAULT_ENDPOINT,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client: HttpClient = if (engine == null) {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
            expectSuccess = true
        }
    } else {
        HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
            expectSuccess = true
        }
    }

    /**
     * Cache map keyed by (latRounded, lonRounded) → (timestamp, snapshot).
     * Round to one decimal so micro-jitter (5m of GPS noise) hits the
     * same key. ConcurrentHashMap so multiple Stream prompts running on
     * different dispatchers can hit the cache without external locks.
     */
    private val cache = java.util.concurrent.ConcurrentHashMap<CacheKey, CacheEntry>()

    suspend fun fetch(lat: Double, lon: Double, hourCount: Int = 12): WeatherSnapshot? {
        val key = CacheKey(roundCoord(lat), roundCoord(lon))
        val now = clockMs()
        cache[key]?.let { (atMs, snap) ->
            if (now - atMs < cacheTtlMs) return snap
        }
        val fresh = runCatching {
            val url = "$endpointUrl?latitude=${formatCoord(lat)}&longitude=${formatCoord(lon)}" +
                "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,precipitation" +
                "&wind_speed_unit=ms&timezone=auto"
            val raw: OpenMeteoResponse = client.get(url) {
                timeout { requestTimeoutMillis = timeoutMs }
            }.body()
            parseHourly(raw, hourCount)
        }.getOrNull()
        if (fresh != null) cache[key] = CacheEntry(now, fresh)
        return fresh
    }

    /**
     * R1.2 — multi-day daily forecast for the `weather.forecast` tool.
     * Same contract discipline as [fetch]: single attempt, per-key
     * cache, null on any failure so callers degrade honestly.
     */
    suspend fun fetchDaily(lat: Double, lon: Double, days: Int = 3): DailySnapshot? {
        val d = days.coerceIn(1, 7)
        val key = DailyCacheKey(roundCoord(lat), roundCoord(lon), d)
        val now = clockMs()
        dailyCache[key]?.let { (atMs, snap) ->
            if (now - atMs < cacheTtlMs) return snap
        }
        val fresh = runCatching {
            val url = "$endpointUrl?latitude=${formatCoord(lat)}&longitude=${formatCoord(lon)}" +
                "&daily=temperature_2m_min,temperature_2m_max,precipitation_sum," +
                "precipitation_probability_max,wind_speed_10m_max" +
                "&forecast_days=$d&wind_speed_unit=ms&timezone=auto"
            val raw: OpenMeteoDailyResponse = client.get(url) {
                timeout { requestTimeoutMillis = timeoutMs }
            }.body()
            parseDaily(raw)
        }.getOrNull()
        if (fresh != null) dailyCache[key] = DailyCacheEntry(now, fresh)
        return fresh
    }

    fun close() = client.close()

    /** Test-helper — direct cache priming for «cache hit» pin tests. */
    internal fun primeCacheForTest(lat: Double, lon: Double, snapshot: WeatherSnapshot, atMs: Long) {
        cache[CacheKey(roundCoord(lat), roundCoord(lon))] = CacheEntry(atMs, snapshot)
    }

    private fun parseHourly(raw: OpenMeteoResponse, hourCount: Int): WeatherSnapshot? {
        val hourly = raw.hourly ?: return null
        val times = hourly.time ?: return null
        val temps = hourly.temperature2m ?: return null
        val winds = hourly.windSpeed10m ?: return null
        val rains = hourly.precipitation ?: emptyList()
        // Напрямок вітру НЕ обовʼязковий: відповідь без нього — це погода без
        // напрямку, а не привід відкинути температуру. Порожній перелік тут
        // означає «джерело не сказало», і далі це їде як null, а не як 270°.
        val windDirs = hourly.windDirection10m ?: emptyList()
        if (times.isEmpty() || temps.isEmpty() || winds.isEmpty()) return null

        val nowMs = clockMs()
        val tz = raw.timezone?.let { runCatching { TimeZone.getTimeZone(it) }.getOrNull() }
            ?: TimeZone.getTimeZone("UTC")
        // Open-Meteo returns iso strings in the requested timezone;
        // parse with that tz and convert to epoch-ms.
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = tz }

        val parsed = mutableListOf<HourPoint>()
        val n = minOf(times.size, temps.size, winds.size)
        for (i in 0 until n) {
            val ts = runCatching { df.parse(times[i])?.time }.getOrNull() ?: continue
            // Skip past hours so the AI sees the next 12 going forward,
            // matching the WeatherWidget chart's expected window.
            if (ts < nowMs - 60L * 60_000L) continue
            parsed += HourPoint(
                tsMs = ts,
                tempC = temps[i],
                windMs = winds[i],
                rainMm = rains.getOrNull(i) ?: 0.0f,
                windDirDeg = windDirs.getOrNull(i),
            )
            if (parsed.size >= hourCount) break
        }
        if (parsed.isEmpty()) return null
        return WeatherSnapshot(timezone = raw.timezone, hours = parsed)
    }

    private fun parseDaily(raw: OpenMeteoDailyResponse): DailySnapshot? {
        val daily = raw.daily ?: return null
        val dates = daily.time ?: return null
        val tMin = daily.temperature2mMin ?: return null
        val tMax = daily.temperature2mMax ?: return null
        if (dates.isEmpty() || tMin.isEmpty() || tMax.isEmpty()) return null
        val n = minOf(dates.size, tMin.size, tMax.size)
        val out = (0 until n).map { i ->
            DayPoint(
                dateIso = dates[i],
                tempMinC = tMin[i],
                tempMaxC = tMax[i],
                rainMm = daily.precipitationSum?.getOrNull(i) ?: 0.0f,
                rainProbPct = daily.precipitationProbabilityMax?.getOrNull(i),
                windMaxMs = daily.windSpeed10mMax?.getOrNull(i) ?: 0.0f,
            )
        }
        return DailySnapshot(timezone = raw.timezone, days = out)
    }

    private fun roundCoord(c: Double): Double = (c * 10).toInt() / 10.0

    private fun formatCoord(c: Double): String =
        String.format(Locale.US, "%.4f", c)

    @Serializable
    private data class OpenMeteoResponse(
        val timezone: String? = null,
        val hourly: OpenMeteoHourly? = null,
    )

    @Serializable
    private data class OpenMeteoHourly(
        val time: List<String>? = null,
        @kotlinx.serialization.SerialName("temperature_2m")
        val temperature2m: List<Float>? = null,
        @kotlinx.serialization.SerialName("wind_speed_10m")
        val windSpeed10m: List<Float>? = null,
        @kotlinx.serialization.SerialName("wind_direction_10m")
        val windDirection10m: List<Int>? = null,
        val precipitation: List<Float>? = null,
    )

    private data class CacheKey(val latRounded: Double, val lonRounded: Double)
    private data class CacheEntry(val atMs: Long, val snapshot: WeatherSnapshot)

    private val dailyCache = java.util.concurrent.ConcurrentHashMap<DailyCacheKey, DailyCacheEntry>()

    @Serializable
    private data class OpenMeteoDailyResponse(
        val timezone: String? = null,
        val daily: OpenMeteoDaily? = null,
    )

    @Serializable
    private data class OpenMeteoDaily(
        val time: List<String>? = null,
        @kotlinx.serialization.SerialName("temperature_2m_min")
        val temperature2mMin: List<Float>? = null,
        @kotlinx.serialization.SerialName("temperature_2m_max")
        val temperature2mMax: List<Float>? = null,
        @kotlinx.serialization.SerialName("precipitation_sum")
        val precipitationSum: List<Float>? = null,
        @kotlinx.serialization.SerialName("precipitation_probability_max")
        val precipitationProbabilityMax: List<Int>? = null,
        @kotlinx.serialization.SerialName("wind_speed_10m_max")
        val windSpeed10mMax: List<Float>? = null,
    )

    private data class DailyCacheKey(val latRounded: Double, val lonRounded: Double, val days: Int)
    private data class DailyCacheEntry(val atMs: Long, val snapshot: DailySnapshot)

    companion object {
        const val DEFAULT_ENDPOINT: String = "https://api.open-meteo.com/v1/forecast"
    }
}

/** Hourly point — matches the [HourPoint] field shape the WeatherWidget renders. */
data class HourPoint(
    val tsMs: Long,
    val tempC: Float,
    val windMs: Float,
    val rainMm: Float,
    /**
     * Звідки дме, у градусах — або null, коли джерело не назвало.
     *
     * Nullable свідомо. До 13.09 напрямку тут не було зовсім, і споживач
     * (`weather.radar`) підставляв на його місце сталу 270°, тобто «Західний»
     * у КОЖНОМУ брифі, у будь-яку погоду й на будь-якій широті. Правдоподібне
     * число замість мовчання — рівно та вигадка, яку решта дому ловить.
     */
    val windDirDeg: Int? = null,
)

/** Open-Meteo response trimmed to the fields the WeatherWidget needs. */
data class WeatherSnapshot(
    val timezone: String?,
    val hours: List<HourPoint>,
)

/** R1.2 — one day of the daily forecast (`weather.forecast` tool). */
data class DayPoint(
    val dateIso: String,
    val tempMinC: Float,
    val tempMaxC: Float,
    val rainMm: Float,
    val rainProbPct: Int?,
    val windMaxMs: Float,
)

/** R1.2 — daily forecast trimmed to what the AI needs to answer honestly. */
data class DailySnapshot(
    val timezone: String?,
    val days: List<DayPoint>,
)
