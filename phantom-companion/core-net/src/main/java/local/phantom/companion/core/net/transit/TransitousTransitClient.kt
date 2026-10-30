package local.phantom.companion.core.net.transit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Worldwide public transit routing client using the open-source Transitous MOTIS engine
 * (transitous.org).
 *
 * Provides borderless, free, open-data public transit routing across Europe, North America,
 * and globally for trains, subways, trams, buses, and ferries.
 */
open class TransitousTransitClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val timeoutMs: Int = 10_000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val planCache = object : java.util.LinkedHashMap<String, Pair<Long, Plan>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Plan>>?): Boolean {
            return size > 50
        }
    }
    private val CACHE_TTL_MS = 5 * 60 * 1000L // 5 хвилин для розкладу transit

    data class Plan(
        val itineraries: List<Itinerary>,
    )

    data class Itinerary(
        val durationSeconds: Int,
        val startTimeIso: String?,
        val endTimeIso: String?,
        val transfers: Int,
        val legs: List<Leg>,
    )

    data class Leg(
        val mode: String, // WALK, TRAM, BUS, SUBWAY, RAIL, etc.
        val routeShortName: String?,
        val routeLongName: String?,
        val routeColor: String?,
        val fromName: String,
        val fromLat: Double,
        val fromLon: Double,
        val toName: String,
        val toLat: Double,
        val toLon: Double,
        val durationSeconds: Int,
        val distanceMeters: Double,
        val points: List<Pair<Double, Double>>,
        /**
         * Коли ця нога справді відходить, за дротом. MOTIS віддає `startTime`
         * (з урахуванням реального часу) і `scheduledStartTime` (за розкладом);
         * пауза на пересадці живе САМЕ тут, а не в `durationSeconds` — сума
         * тривалостей ніг коротша за поїздку рівно на час чекання.
         */
        val startTimeIso: String?,
        val endTimeIso: String?,
        /**
         * Проміжні зупинки з дроту. Null — дріт про них не сказав (пішохідні
         * ноги їх не мають); 0 — сказав, що їх нема. Різниця видима людині:
         * при null рядок «· N зупинок» не малюється взагалі.
         */
        val intermediateStopCount: Int?,
        val headsign: String? = null,
        val tripId: String? = null,
        val fromTrack: String? = null,
        val toTrack: String? = null,
        val stops: List<StopCall> = emptyList(),
        val agencyName: String? = null,
        val realTime: Boolean = false,
        val scheduledStartIso: String? = null,
        val fromCall: StopCall? = null,
        val toCall: StopCall? = null,
    ) {
        /** Усі зупинки рейсу по порядку, обидва кінці включно. */
        val allCalls: List<StopCall> get() = listOfNotNull(fromCall) + stops + listOfNotNull(toCall)
    }

    data class StopCall(
        val name: String,
        val lat: Double,
        val lon: Double,
        val arrivalIso: String?,
        val departureIso: String?,
        val scheduledDepartureIso: String?,
        val track: String?,
        val cancelled: Boolean,
    )

    open suspend fun plan(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        departTime: String? = null,
        departDate: String? = null,
        numItineraries: Int = 3,
    ): Plan? = withContext(Dispatchers.IO) {
        val queryParams = buildList {
            add("fromPlace=${String.format(Locale.US, "%.6f,%.6f", fromLat, fromLon)}")
            add("toPlace=${String.format(Locale.US, "%.6f,%.6f", toLat, toLon)}")
            add("numItineraries=$numItineraries")
            if (!departTime.isNullOrBlank()) {
                add("time=" + URLEncoder.encode(departTime, "UTF-8"))
            }
            if (!departDate.isNullOrBlank()) {
                add("date=" + URLEncoder.encode(departDate, "UTF-8"))
            }
        }.joinToString("&")

        val cacheKey = "$fromLat,$fromLon->$toLat,$toLon@$departTime@$departDate@$numItineraries"
        val now = System.currentTimeMillis()
        synchronized(planCache) {
            val cached = planCache[cacheKey]
            if (cached != null && (now - cached.first) < CACHE_TTL_MS) {
                return@withContext cached.second
            }
        }

        val urlString = "$baseUrl/api/v1/plan?$queryParams"
        val body = httpGet(urlString) ?: return@withContext null

        val parsed = parsePlanJson(body)
        if (parsed != null && parsed.itineraries.isNotEmpty()) {
            synchronized(planCache) {
                planCache[cacheKey] = now to parsed
            }
        }
        parsed
    }

    internal fun parsePlanJson(jsonText: String): Plan? {
        return runCatching {
            val root = json.parseToJsonElement(jsonText).jsonObject
            val itinsJson = root["itineraries"]?.jsonArray ?: return null
            val itineraries = itinsJson.mapNotNull { itElement ->
                val itObj = itElement.jsonObject
                val durationSec = itObj["duration"]?.jsonPrimitive?.intOrNull ?: 0
                val startTime = itObj["startTime"]?.jsonPrimitive?.contentOrNull
                val endTime = itObj["endTime"]?.jsonPrimitive?.contentOrNull
                val transfers = itObj["transfers"]?.jsonPrimitive?.intOrNull ?: 0
                val legsJson = itObj["legs"]?.jsonArray ?: return@mapNotNull null

                val legs = legsJson.mapNotNull { legElement ->
                    val legObj = legElement.jsonObject
                    val mode = legObj["mode"]?.jsonPrimitive?.contentOrNull ?: "WALK"
                    val longName = legObj["routeLongName"]?.jsonPrimitive?.contentOrNull
                    val shortName = LineLabel.of(
                        legObj["routeShortName"]?.jsonPrimitive?.contentOrNull,
                        longName,
                        legObj["routeId"]?.jsonPrimitive?.contentOrNull,
                    ).ifEmpty { null }
                    val color = legObj["routeColor"]?.jsonPrimitive?.contentOrNull

                    val fromObj = legObj["from"]?.jsonObject
                    val fromName = fromObj?.get("name")?.jsonPrimitive?.contentOrNull ?: "Початок"
                    val fromLat = fromObj?.get("lat")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val fromLon = fromObj?.get("lon")?.jsonPrimitive?.doubleOrNull ?: 0.0

                    val toObj = legObj["to"]?.jsonObject
                    val toName = toObj?.get("name")?.jsonPrimitive?.contentOrNull ?: "Кінець"
                    val toLat = toObj?.get("lat")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val toLon = toObj?.get("lon")?.jsonPrimitive?.doubleOrNull ?: 0.0

                    val legDuration = legObj["duration"]?.jsonPrimitive?.intOrNull ?: 0
                    val legDistance = legObj["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0

                    // Реальний час має перевагу над розкладним: людину цікавить,
                    // коли трамвай піде насправді, а не коли обіцяли торік.
                    val legStart = legObj["startTime"]?.jsonPrimitive?.contentOrNull
                        ?: legObj["scheduledStartTime"]?.jsonPrimitive?.contentOrNull
                    val legEnd = legObj["endTime"]?.jsonPrimitive?.contentOrNull
                        ?: legObj["scheduledEndTime"]?.jsonPrimitive?.contentOrNull
                    val stopsJson = legObj["intermediateStops"] as? JsonArray
                    val stopCount = stopsJson?.size
                    val calls = stopsJson.orEmpty().mapNotNull { runCatching { stopCall(it.jsonObject) }.getOrNull() }

                    val geomObj = legObj["legGeometry"]?.jsonObject
                    val encodedPoints = geomObj?.get("points")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val precision = geomObj?.get("precision")?.jsonPrimitive?.intOrNull ?: 7

                    val points = if (encodedPoints.isNotEmpty()) {
                        decodePolyline(encodedPoints, precision)
                    } else if (fromLat != 0.0 && toLat != 0.0) {
                        listOf(fromLat to fromLon, toLat to toLon)
                    } else {
                        emptyList()
                    }

                    Leg(
                        mode = mode,
                        routeShortName = shortName,
                        routeLongName = longName,
                        routeColor = color,
                        fromName = fromName,
                        fromLat = fromLat,
                        fromLon = fromLon,
                        toName = toName,
                        toLat = toLat,
                        toLon = toLon,
                        durationSeconds = legDuration,
                        distanceMeters = legDistance,
                        points = points,
                        startTimeIso = legStart,
                        endTimeIso = legEnd,
                        intermediateStopCount = stopCount,
                        headsign = legObj.str("headsign"),
                        tripId = legObj.str("tripId"),
                        fromTrack = fromObj?.str("track"),
                        toTrack = toObj?.str("track"),
                        stops = calls,
                        agencyName = legObj.str("agencyName"),
                        realTime = legObj.str("realTime") == "true",
                        scheduledStartIso = legObj.str("scheduledStartTime"),
                        fromCall = fromObj?.let { runCatching { stopCall(it) }.getOrNull() },
                        toCall = toObj?.let { runCatching { stopCall(it) }.getOrNull() },
                    )
                }

                if (legs.isEmpty()) null
                else Itinerary(
                    durationSeconds = durationSec,
                    startTimeIso = startTime,
                    endTimeIso = endTime,
                    transfers = transfers,
                    legs = legs,
                )
            }

            // Порожній `Plan` ≠ `null`. Null означає «це не відповідь планувальника»
            // (мережа мовчить, сервер віддав помилку), порожній список — «планувальник
            // відповів: рейсів нема». Далі по ланцюгу з цього виходять два різні
            // речення до людини, і злити їх назад в одне — знову збрехати про світ.
            Plan(itineraries)
        }.getOrNull()
    }

    /** Увесь рейс від першої до останньої зупинки — для розгортання лінії. */
    open suspend fun trip(tripId: String): Leg? = withContext(Dispatchers.IO) {
        val body = httpGet("$baseUrl/api/v5/trip?tripId=" + URLEncoder.encode(tripId, "UTF-8")) ?: return@withContext null
        parsePlanJson("{\"itineraries\":[" + body + "]}")?.itineraries?.firstOrNull()?.legs?.firstOrNull { it.mode != "WALK" }
    }

    private fun stopCall(o: kotlinx.serialization.json.JsonObject) = StopCall(
        name = o.str("name").orEmpty(),
        lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        arrivalIso = o.str("arrival"),
        departureIso = o.str("departure"),
        scheduledDepartureIso = o.str("scheduledDeparture"),
        track = o.str("track"),
        cancelled = o.str("cancelled") == "true",
    )

    private fun kotlinx.serialization.json.JsonObject.str(name: String): String? =
        this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotBlank() }

    private fun httpGet(urlString: String): String? {
        return runCatching {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.transitous.org"
        const val DEFAULT_USER_AGENT: String = "PhantomCompanion/0.1 (operator; https://try.phantom-os.dev)"

        /**
         * Decodes standard Google Encoded Polyline with arbitrary precision (5, 6, or 7).
         * MOTIS uses precision 7 (1e-7 deg ~1.1 cm).
         */
        fun decodePolyline(encoded: String, precision: Int = 7): List<Pair<Double, Double>> {
            if (encoded.isEmpty()) return emptyList()
            val factor = Math.pow(10.0, precision.toDouble())
            val out = ArrayList<Pair<Double, Double>>(encoded.length / 4)
            var i = 0
            var lat = 0L
            var lon = 0L
            val len = encoded.length
            while (i < len) {
                var result = 0L
                var shift = 0
                var b: Int
                do {
                    if (i >= len) return out
                    b = encoded[i++].code - 63
                    if (b < 0) return out
                    result = result or ((b and 0x1F).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

                result = 0L
                shift = 0
                do {
                    if (i >= len) return out
                    b = encoded[i++].code - 63
                    if (b < 0) return out
                    result = result or ((b and 0x1F).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1

                out.add(lat / factor to lon / factor)
            }
            return out
        }
    }
}
