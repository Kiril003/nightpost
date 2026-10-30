package local.phantom.companion.core.net.transit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The stop-board half of Transitous/MOTIS.
 *
 * Route planning already uses Transitous, but a tapped stop used to rely on a
 * locally installed country pack or an Overpass relation.  Those are useful
 * sources, not a reason to show an empty stop in a city covered by MOTIS.
 * This client deliberately reads the v6 stop and stop-times endpoints: they
 * return only provider data and a failed request remains an empty result.
 */
class TransitousStopClient(
    private val baseUrl: String = TransitousTransitClient.DEFAULT_BASE_URL,
    private val userAgent: String = TransitousTransitClient.DEFAULT_USER_AGENT,
    private val timeoutMs: Int = 10_000,
) {
    data class Line(
        val ref: String,
        val mode: String,
        val direction: String?,
        val colour: String?,
    )

    data class Departure(
        val ref: String,
        val mode: String,
        val direction: String?,
        val colour: String?,
        val departureIso: String,
        val timeZone: String?,
        val realTime: Boolean,
    )

    data class StopBoard(val lines: List<Line>, val departures: List<Departure>)

    /**
     * Одна зупинка з видимої рамки. [modes] — рід транспорту так, як його
     * назвав сервер: словник MOTIS ширший за наш, і звужувати його тут
     * означало б вирішувати за шар.
     */
    data class MapStop(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val modes: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Зупинки у видимій рамці — те, чого шар мапи не мав узагалі.
     *
     * Заміряно 20.09: рамка Львова (49.78,23.92 → 49.88,24.10) повертає 769
     * зупинок за 0,28 с, Києва — 527. Дорожній пакет транспорту при цьому
     * існує у світі рівно один (Люксембург), тож без цього виклику шар
     * зупинок порожній за побудовою в обох містах.
     *
     * `null` — запит не вдався. Це НЕ те саме, що порожній список: викликач
     * мусить лишити шар порожнім, а не домалювати попередню рамку.
     */
    suspend fun stopsIn(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        limit: Int = MAX_STOPS,
    ): List<MapStop>? = withContext(Dispatchers.IO) {
        val min = "${num(minLat)},${num(minLon)}"
        val max = "${num(maxLat)},${num(maxLon)}"
        val body = get("/api/v1/map/stops?min=${enc(min)}&max=${enc(max)}") ?: return@withContext null
        parseStopsJson(body, limit)
    }

    suspend fun boardAt(lat: Double, lon: Double, radiusM: Int): StopBoard? = withContext(Dispatchers.IO) {
        val center = "${"%.6f".format(java.util.Locale.US, lat)},${"%.6f".format(java.util.Locale.US, lon)}"
        val radius = radiusM.coerceIn(30, 500)
        val routes = get("/api/v6/stop?center=${enc(center)}&radius=$radius&exactRadius=false")
        val times = get("/api/v6/stoptimes?center=${enc(center)}&radius=$radius&n=12&exactRadius=false")
        if (routes == null && times == null) return@withContext null

        val lines = routes?.let(::parseLines).orEmpty()
        val departures = times?.let(::parseDepartures).orEmpty()
        // Some feeds expose a route only on its next stop-time.  Preserve that
        // truthful fact rather than hiding the whole line list.
        val fromTimes = departures.map { Line(it.ref, it.mode, it.direction, it.colour) }
        StopBoard(
            lines = (lines + fromTimes).filter { it.ref.isNotBlank() }
                .distinctBy { "${it.ref.lowercase()}|${it.mode}|${it.direction.orEmpty()}" },
            departures = departures,
        )
    }

    data class BoardDeparture(
        val label: String,
        val mode: String,
        val headsign: String?,
        val colour: String?,
        val atIso: String,
        val scheduledIso: String?,
        val timeZone: String?,
        val realTime: Boolean,
        val cancelled: Boolean,
        val platform: String?,
        val stopName: String?,
        val tripId: String?,
        /** Для групування рейсів однієї лінії; людині не показується. */
        val routeKey: String,
        val operator: String?,
    )

    data class DepartureBoard(val upcoming: List<BoardDeparture>, val recent: List<BoardDeparture>)

    suspend fun departureBoard(
        lat: Double,
        lon: Double,
        radiusM: Int,
        upcoming: Int = 24,
        recent: Int = 6,
    ): DepartureBoard? = withContext(Dispatchers.IO) {
        val center = "${"%.6f".format(java.util.Locale.US, lat)},${"%.6f".format(java.util.Locale.US, lon)}"
        val radius = radiusM.coerceIn(30, 1_500)
        val base = "/api/v6/stoptimes?center=${enc(center)}&radius=$radius&exactRadius=false"
        val ahead = get("$base&n=$upcoming")
        val behind = get("$base&n=$recent&direction=EARLIER")
        if (ahead == null && behind == null) return@withContext null
        DepartureBoard(
            upcoming = ahead?.let(::parseBoard).orEmpty(),
            recent = behind?.let(::parseBoard).orEmpty(),
        )
    }

    fun parseBoard(body: String): List<BoardDeparture> = runCatching {
        json.parseToJsonElement(body).jsonObject["stopTimes"]?.jsonArray.orEmpty().mapNotNull { raw ->
            val o = raw.jsonObject
            val place = o.objectAt("place")
            val at = place?.string("departure") ?: place?.string("arrival") ?: o.string("departure") ?: return@mapNotNull null
            val routeId = o.string("routeId")
            BoardDeparture(
                label = LineLabel.of(o.string("routeShortName") ?: o.string("displayName"), o.string("routeLongName"), routeId),
                mode = o.string("mode") ?: "TRANSIT",
                headsign = o.string("headsign")?.takeUnless { LineLabel.looksLikeId(it) },
                colour = o.string("routeColor"),
                atIso = at,
                scheduledIso = place?.string("scheduledDeparture") ?: place?.string("scheduledArrival"),
                timeZone = place?.string("tz"),
                realTime = o["realTime"]?.jsonPrimitive?.contentOrNull == "true",
                cancelled = o["cancelled"]?.jsonPrimitive?.contentOrNull == "true" ||
                    place?.get("cancelled")?.jsonPrimitive?.contentOrNull == "true",
                platform = place?.string("track"),
                stopName = place?.string("name"),
                tripId = o.string("tripId"),
                routeKey = routeId ?: o.string("routeShortName").orEmpty(),
                operator = o.string("agencyName"),
            )
        }
    }.getOrDefault(emptyList())

    internal fun parseStopsJson(body: String, limit: Int = MAX_STOPS): List<MapStop>? = runCatching {
        json.parseToJsonElement(body).jsonArray.mapNotNull { raw ->
            val o = raw.jsonObject
            val id = o.string("stopId") ?: return@mapNotNull null
            // Сервер віддає координати в науковому записі (4.984035E1) —
            // читається лише через рядок, `.float`/`.int` на ньому падають.
            val lat = o.double("lat") ?: return@mapNotNull null
            val lon = o.double("lon") ?: return@mapNotNull null
            MapStop(
                id = id,
                name = o.string("name").orEmpty(),
                lat = lat,
                lon = lon,
                modes = o["modes"]?.let { m ->
                    runCatching { m.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
                }.orEmpty(),
            )
        }.take(limit)
    }.getOrNull()

    internal fun parseLines(body: String): List<Line> = runCatching {
        json.parseToJsonElement(body).jsonObject["routes"]?.jsonArray.orEmpty().mapNotNull { raw ->
            val o = raw.jsonObject
            val long = o.string("routeLongName") ?: o.string("longName")
            val ref = LineLabel.of(o.string("routeShortName") ?: o.string("shortName") ?: o.string("displayName"), long, o.string("routeId") ?: o.string("id"))
            Line(ref, o.string("mode") ?: "TRANSIT", o.string("routeLongName") ?: o.string("longName"), o.string("routeColor") ?: o.string("color"))
        }
    }.getOrDefault(emptyList())

    internal fun parseDepartures(body: String): List<Departure> = runCatching {
        json.parseToJsonElement(body).jsonObject["stopTimes"]?.jsonArray.orEmpty().mapNotNull { raw ->
            val o = raw.jsonObject
            val ref = LineLabel.of(o.string("routeShortName") ?: o.string("displayName"), o.string("routeLongName"), o.string("routeId"))
            val at = o.string("departure") ?: o.objectAt("place")?.string("departure") ?: return@mapNotNull null
            Departure(
                ref = ref,
                mode = o.string("mode") ?: "TRANSIT",
                direction = o.string("headsign") ?: o.string("routeLongName"),
                colour = o.string("routeColor"),
                departureIso = at,
                timeZone = o.objectAt("place")?.string("tz"),
                realTime = o["realTime"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }
    }.getOrDefault(emptyList())

    private fun get(path: String): String? = runCatching {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        try {
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun num(v: Double): String = "%.5f".format(java.util.Locale.US, v)
    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.double(name: String): Double? =
        this[name]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    private fun JsonObject.objectAt(name: String): JsonObject? = this[name]?.let { runCatching { it.jsonObject }.getOrNull() }

    companion object {
        /**
         * Стеля на рамку. Не про мережу — про скло: 769 крапок Львова вже на
         * межі читності, і рамка, що зачепила пів області, малювала б кашу,
         * під якою не видно дороги. Сам запит дешевий (220 КБ).
         */
        const val MAX_STOPS: Int = 1_500
    }
}
