package local.phantom.companion.core.net.map

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * M-B01 — point-to-point routing over an OSRM-compatible server, і лише над
 * тим, який оператор сам налаштував ([GeoEndpoints]). Three profiles:
 * car / foot / bike, each on its own host. We ask for the full GeoJSON
 * geometry so the route draws as a real polyline, plus the total distance (m)
 * and duration (s) for the ETA badge.
 *
 * ФОРМА АДРЕСИ: OSRM несе координати в ШЛЯХУ, не в параметрах
 * (`/route/v1/driving/$lon,$lat;$lon,$lat`) — тобто чужий сервер, поставлений
 * сюди «щоб не падало», отримує звідки І куди їде оператор. Сторож координат
 * ловить і цю форму; основа приходить лише з конфігурації.
 *
 * Honesty (Law 3): a failed or empty response returns `null` — the caller
 * shows "маршрут не вдалось прокласти", never a fake straight line.
 */
enum class RouteProfile(val host: String, val slug: String, val label: String) {
    FOOT("routed-foot", "foot", "пішки"),
    CAR("routed-car", "driving", "авто"),
    BIKE("routed-bike", "bike", "вело"),
    TRANSIT("", "", "транзит"),
}

data class RouteResult(
    val points: List<Pair<Double, Double>>, // (lat, lon)
    val distanceMeters: Double,
    val durationSeconds: Double,
    val profile: RouteProfile,
)

/**
 * PRESENCE — one turn lane on the approach to a maneuver, as OSRM read it off
 * the way's `turn:lanes` tagging.
 *
 * [indications] are the arrows painted on that lane ("left", "straight",
 * "slight right", …; empty or "none" where the lane carries no marking) and
 * [validForManeuver] is the router's own verdict that the lane can be used to
 * complete the maneuver ahead. Both come from the server or not at all — a
 * road whose lanes are untagged produces no [LaneInfo], and the band is then
 * not drawn (D8a: нема lane-даних → смуги не вигадуємо).
 */
data class LaneInfo(
    val indications: List<String> = emptyList(),
    val validForManeuver: Boolean = false,
)

/** NAV-1 — one OSRM maneuver: what to do, where, on which road. */
data class RouteStep(
    val type: String,       // OSRM maneuver type: depart / turn / arrive / …
    val modifier: String?,  // left / right / slight left / …
    val roadName: String,   // may be blank on unnamed ways
    val distanceMeters: Double,
    val durationSeconds: Double,
    val lat: Double,
    val lon: Double,
    /** Roundabout exit to take, 1-based. Null when this is not a roundabout. */
    val exitNumber: Int? = null,
    /**
     * How far the road actually turns, degrees, negative left. The modifier is
     * a five-bucket approximation of this; an arrow drawn from the bucket
     * points at 90° for a turn that is really 40°.
     */
    val turnAngleDeg: Float? = null,
    /**
     * Compass bearing of the road AFTER the maneuver, map-north frame — the
     * datum for a MAP-aligned map symbol. turnAngleDeg is the signed CHANGE —
     * the datum for a screen-frame banner glyph. Two datums, two jobs.
     */
    val bearingAfterDeg: Float? = null,
    /**
     * Lanes on the approach INTO this step's maneuver, leftmost first. OSRM
     * hangs them on the step's FIRST intersection, which is the maneuver node
     * itself — so they describe the asphalt the operator is on while reading
     * the instruction, not the road after the turn. Empty = untagged.
     */
    val lanes: List<LaneInfo> = emptyList(),
)

/** Signed heading change, −180..180. */
internal fun turnAngleOf(before: Double?, after: Double?): Float? {
    if (before == null || after == null) return null
    var d = after - before
    while (d > 180.0) d -= 360.0
    while (d <= -180.0) d += 360.0
    return d.toFloat()
}

/** NAV-M1 — Park & Walk last-mile connection. */
data class ParkAndWalkLeg(
    val parkingLat: Double,
    val parkingLon: Double,
    val parkingName: String? = null,
    val driveDistanceMeters: Double,
    val driveDurationSeconds: Double,
    val walkDistanceMeters: Double,
    val walkDurationSeconds: Double,
    val walkPoints: List<Pair<Double, Double>> = emptyList(),
    val walkSteps: List<RouteStep> = emptyList(),
)

/**
 * Which router drew this line — and therefore how much of the road law it
 * could see. ATLAS-2.5, owner's field verdict §11: the on-device A* walks a
 * cached way mesh that carries no turn-restriction relations, no time-based
 * access, no signed prohibitions. It can produce a legal-looking instruction
 * into a turn that is forbidden. A public OSRM profile does honour those OSM
 * relations, so the two claims are not the same claim and must not be drawn,
 * spoken or trusted as if they were.
 */
enum class RouteOrigin {
    /** OSRM (or any server profile) — turn restrictions modelled. */
    ONLINE_ROUTER,

    /** On-device mesh A* — geometry only; the turn law is unknown. */
    OFFLINE_MESH,
}

/** NAV-1 — one full route variant (the chosen corridor or an alternative). */
data class RouteVariant(
    val points: List<Pair<Double, Double>>, // (lat, lon)
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    /** Per-segment speeds (m/s) from `annotations=speed`; empty if absent. */
    val speedsMps: List<Double>,
    val profile: RouteProfile,
    /**
     * Where the minutes come from. The offline mesh router knows whether it
     * read a posted sign, inferred from road class, or fell back to one flat
     * speed — and those are different claims. OSRM answers carry the default:
     * it does not tell us, so we must not imply it did.
     */
    val speedBasis: String = "",
    /** Fraction of route metres that carried a real posted limit, 0..1. */
    val postedShare: Double = 0.0,
    /** Metres of a walking route that run along a national-highway shoulder. */
    val trunkShoulderMeters: Double = 0.0,
    /**
     * How many ways on this route OSM marks `ford`. Zero is the OSRM default
     * and stays zero for online answers: the server does not report fords, so
     * we must not imply it looked. Offline it is a count of distinct ways —
     * and it says a crossing is MARKED, never that it is passable.
     */
    val fordCrossings: Int = 0,
    /** NAV-M1 — multimodal last-mile connection (car -> parking -> walk to door). */
    val parkAndWalk: ParkAndWalkLeg? = null,
    /** Defaults to the server answer: only a router that KNOWS it read no
     *  restriction model may claim [RouteOrigin.OFFLINE_MESH]. */
    val origin: RouteOrigin = RouteOrigin.ONLINE_ROUTER,
) {
    /** Real maneuvers: depart/arrive and plain road renames don't count. */
    val turnCount: Int
        get() = steps.count { it.type != "depart" && it.type != "arrive" && it.type != "new name" }
}

open class OsrmClient {
    // Kept private so callers (feature-stream) don't need ktor on their
    // compile classpath — the ktor HttpClient type never appears in the
    // public API surface of this class.
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // P0 (owner field report 2026-07-20, «все тупить») — this client had
        // NO timeout at all: a throttled or hung routing server stalled
        // `plan()` indefinitely, and GuidanceController's `rerouting` latch
        // stays set for the whole hang — a driver off-route gets no replan
        // until the socket dies on its own. Every other geo client in the
        // codebase already carries explicit timeouts; a routing answer that
        // arrives after the caps below describes a road position long past.
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private val detailedRouteCache = object : java.util.LinkedHashMap<String, Pair<Long, List<RouteVariant>>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<RouteVariant>>>?): Boolean {
            return size > 64
        }
    }
    private val ROUTE_CACHE_TTL_MS = 10 * 60 * 1000L // 10 хвилин для кешу одного маршруту

    /**
     * Обходить налаштовані основи. Їх нуль або одна: чужого сервера позаду
     * своєї більше немає (`routingBasesFor`), тож порожній список означає
     * «жодного запиту» — саме так виглядає відмова без витоку.
     *
     * LAN-основа має свою стелю 800 мс: поза домашнім Wi-Fi вона не
     * резолвиться, і водій не має стояти 5 секунд на кожній перебудові, перш
     * ніж почути відмову.
     */
    private suspend fun <T> overBases(
        profileHost: String,
        attempt: suspend (String) -> T?,
    ): T? {
        for (base in GeoEndpoints.config.routingBasesFor(profileHost)) {
            val isLan = base.startsWith("http://192.168.") ||
                base.startsWith("http://10.") ||
                base.startsWith("http://172.") ||
                base.startsWith("http://127.0.0.1") ||
                base.startsWith("http://localhost")
            val out = runCatching {
                if (isLan) {
                    kotlinx.coroutines.withTimeout(800L) {
                        attempt(base)
                    }
                } else {
                    attempt(base)
                }
            }.getOrNull()
            if (out != null) return out
        }
        return null
    }

    open suspend fun route(
        profile: RouteProfile,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): RouteResult? = overBases(profile.host) { base ->
        val url = "$base/route/v1/${profile.slug}/" +
            "$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
        val resp: OsrmResponse = client.get(url).body()
        val r = resp.routes.firstOrNull() ?: return@overBases null
        // GeoJSON coordinates are [lon, lat]; flip to (lat, lon).
        val pts = r.geometry.coordinates.map { it[1] to it[0] }
        if (pts.isEmpty()) return@overBases null
        RouteResult(pts, r.distance, r.duration, profile)
    }

    /**
     * NAV-1 — full-depth routing: turn-by-turn steps, per-segment speeds
     * and (for plain A→B requests) OSRM's alternative corridors.
     * [waypoints] is start, via…, destination — OSRM offers alternatives
     * only on two-point requests, so via-routes come back as one variant.
     * null = honest failure (Law 3), same contract as [route].
     */
    open suspend fun routeDetailed(
        profile: RouteProfile,
        waypoints: List<Pair<Double, Double>>, // (lat, lon), size >= 2
        alternatives: Boolean = true,
    ): List<RouteVariant>? {
        if (waypoints.size < 2) return null
        val coords = waypoints.joinToString(";") { (lat, lon) -> "$lon,$lat" }
        val wantAlternatives = alternatives && waypoints.size == 2
        val cacheKey = "${profile.slug}:$coords:$wantAlternatives"
        val now = System.currentTimeMillis()

        synchronized(detailedRouteCache) {
            val cached = detailedRouteCache[cacheKey]
            if (cached != null && (now - cached.first) < ROUTE_CACHE_TTL_MS) {
                return cached.second
            }
        }

        val variants = overBases(profile.host) { base ->
            val url = "$base/route/v1/${profile.slug}/$coords" +
                "?overview=full&geometries=geojson&steps=true&annotations=speed" +
                "&alternatives=$wantAlternatives"
            parseRouteVariants(client.get(url).body(), profile)
        }

        if (!variants.isNullOrEmpty()) {
            synchronized(detailedRouteCache) {
                detailedRouteCache[cacheKey] = now to variants
            }
        }
        return variants
    }

    /**
     * Fast 1-to-N matrix duration requests via OSRM table API.
     */
    suspend fun table(
        profile: RouteProfile,
        fromLat: Double,
        fromLon: Double,
        destinations: List<Pair<Double, Double>>,
    ): List<Int?>? = runCatching {
        if (destinations.isEmpty()) return@runCatching emptyList()
        val coords = buildString {
            append("$fromLon,$fromLat")
            for ((lat, lon) in destinations) {
                append(";$lon,$lat")
            }
        }
        val destIndices = destinations.indices.map { it + 1 }.joinToString(";")
        val base = GeoEndpoints.config.routingBaseFor(profile.host) ?: return@runCatching null
        val url = "$base/table/v1/${profile.slug}/$coords?sources=0&destinations=$destIndices&annotations=duration"
        val resp: OsrmTableResponse = client.get(url).body()
        val durations = resp.durations.firstOrNull() ?: return@runCatching null
        durations.map { if (it == null) null else it.toInt() }
    }.getOrNull()

    /**
     * ATLAS-2b — map matching via OSRM `/match`. The free-drive road-snap runs
     * HERE now, on the local geo stack, not on Overpass tiles: Overpass is
     * unreachable exactly while driving (the owner's field report), and the
     * self-hosted OSRM already holds the whole road network. Send the recent
     * fix trace (oldest→newest) with each point's own accuracy as its search
     * radius; OSRM returns the trace snapped to the network. We take the LAST
     * matched point — the operator's current road position — plus the matching
     * confidence and the street name.
     *
     * null = honest failure (Law 3): a hung or absent stack leaves the dot on
     * the raw fused fix, never on a guessed road.
     */
    suspend fun match(
        profile: RouteProfile,
        trace: List<Triple<Double, Double, Float>>, // (lat, lon, accuracyM), oldest→newest
    ): RoadMatchResult? = runCatching {
        if (trace.size < 2) return@runCatching null
        val base = GeoEndpoints.config.routingBaseFor(profile.host) ?: return@runCatching null
        val coords = trace.joinToString(";") { (lat, lon, _) -> "$lon,$lat" }
        val radiuses = trace.joinToString(";") { (_, _, acc) ->
            acc.coerceIn(MATCH_MIN_RADIUS_M, MATCH_MAX_RADIUS_M).toInt().toString()
        }
        val url = "$base/match/v1/${profile.slug}/$coords" +
            "?geometries=geojson&overview=false&steps=false&radiuses=$radiuses"
        parseMatch(client.get(url).body())
    }.getOrNull()

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L

        /** Generous enough for `alternatives=true&steps=true` on a cross-city
         *  request against a modest self-hosted instance; a local OSRM answers
         *  in milliseconds. */
        const val REQUEST_TIMEOUT_MS = 12_000L

        /** `/match` per-point search radius floor/ceiling (m). Too small and an
         *  honest ±20 m urban fix finds no road; too large and the matcher
         *  jumps lanes or parallel streets. */
        const val MATCH_MIN_RADIUS_M = 8f
        const val MATCH_MAX_RADIUS_M = 50f
    }
}

/** ATLAS-2b — one OSRM `/match` snap: the LAST trace point on the network. */
data class RoadMatchResult(
    val lat: Double, // snapped position of the newest input point
    val lon: Double,
    val wayName: String?, // matched street name, when OSRM names the way
    val confidence: Float, // the matching's confidence, [0,1]
)

/** Parsing split from transport so the `/match` response shape is unit-testable. */
internal fun parseMatch(body: String): RoadMatchResult? {
    val resp = runCatching { osrmJson.decodeFromString<OsrmMatchResponse>(body) }.getOrNull()
        ?: return null
    if (resp.code != "Ok") return null
    // OSRM returns one tracepoint per INPUT point, null where a point could not
    // be matched. The operator is at the newest, so take the last non-null.
    val tp = resp.tracepoints.lastOrNull { it != null } ?: return null
    val loc = tp.location
    if (loc.size < 2) return null
    val confidence = resp.matchings.getOrNull(tp.matchingsIndex)?.confidence
        ?: resp.matchings.firstOrNull()?.confidence
        ?: return null
    return RoadMatchResult(
        lat = loc[1], // GeoJSON is [lon, lat]
        lon = loc[0],
        wayName = tp.name?.takeIf { it.isNotBlank() },
        confidence = confidence.toFloat(),
    )
}

@Serializable
private data class OsrmMatchResponse(
    val code: String = "",
    val matchings: List<OsrmMatching> = emptyList(),
    val tracepoints: List<OsrmTracepoint?> = emptyList(),
)

@Serializable
private data class OsrmMatching(
    val confidence: Double = 0.0,
)

@Serializable
private data class OsrmTracepoint(
    val location: List<Double> = emptyList(), // [lon, lat]
    @SerialName("matchings_index") val matchingsIndex: Int = 0,
    val name: String? = null,
)

/** Parsing split from transport so the OSRM response shape is unit-testable. */
internal fun parseRouteVariants(body: String, profile: RouteProfile): List<RouteVariant>? {
    val resp = runCatching { osrmJson.decodeFromString<OsrmResponse>(body) }.getOrNull() ?: return null
    val variants = resp.routes.mapNotNull { r ->
        val pts = r.geometry.coordinates.map { it[1] to it[0] }
        if (pts.isEmpty()) return@mapNotNull null
        RouteVariant(
            points = pts,
            distanceMeters = r.distance,
            durationSeconds = r.duration,
            steps = r.legs.flatMap { leg ->
                leg.steps.map { s ->
                    RouteStep(
                        type = s.maneuver.type,
                        modifier = s.maneuver.modifier,
                        roadName = s.name,
                        distanceMeters = s.distance,
                        durationSeconds = s.duration,
                        lat = s.maneuver.location.getOrNull(1) ?: 0.0,
                        lon = s.maneuver.location.getOrNull(0) ?: 0.0,
                        exitNumber = s.maneuver.exit?.takeIf { it in 1..9 },
                        turnAngleDeg = turnAngleOf(
                            s.maneuver.bearingBefore,
                            s.maneuver.bearingAfter,
                        ),
                        bearingAfterDeg = s.maneuver.bearingAfter?.toFloat(),
                        lanes = s.intersections.firstOrNull()?.lanes.orEmpty().map { lane ->
                            LaneInfo(
                                indications = lane.indications.filter { it.isNotBlank() && it != "none" },
                                validForManeuver = lane.valid,
                            )
                        },
                    )
                }
            },
            speedsMps = r.legs.flatMap { it.annotation?.speed ?: emptyList() },
            profile = profile,
        )
    }
    return variants.ifEmpty { null }
}

private val osrmJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class OsrmResponse(
    val code: String = "",
    val routes: List<OsrmRoute> = emptyList(),
)

@Serializable
private data class OsrmTableResponse(
    val code: String = "",
    val durations: List<List<Double?>> = emptyList(),
)

@Serializable
private data class OsrmRoute(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val geometry: OsrmGeometry = OsrmGeometry(),
    val legs: List<OsrmLeg> = emptyList(),
)

@Serializable
private data class OsrmLeg(
    val steps: List<OsrmStep> = emptyList(),
    val annotation: OsrmAnnotation? = null,
)

@Serializable
private data class OsrmStep(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val name: String = "",
    val maneuver: OsrmManeuver = OsrmManeuver(),
    /** Present whenever `steps=true`; `lanes` inside only where OSM is tagged. */
    val intersections: List<OsrmIntersection> = emptyList(),
)

@Serializable
private data class OsrmIntersection(
    val lanes: List<OsrmLane> = emptyList(),
)

@Serializable
private data class OsrmLane(
    val valid: Boolean = false,
    val indications: List<String> = emptyList(),
)

@Serializable
private data class OsrmManeuver(
    val type: String = "",
    val modifier: String? = null,
    val location: List<Double> = emptyList(), // [lon, lat]
    /** Which exit to take off a roundabout. The one thing that makes a
     *  roundabout instruction true; the modifier alone never does. */
    val exit: Int? = null,
    @kotlinx.serialization.SerialName("bearing_before")
    val bearingBefore: Double? = null,
    @kotlinx.serialization.SerialName("bearing_after")
    val bearingAfter: Double? = null,
)

@Serializable
private data class OsrmAnnotation(
    val speed: List<Double> = emptyList(),
)

@Serializable
private data class OsrmGeometry(
    @SerialName("coordinates") val coordinates: List<List<Double>> = emptyList(),
)
