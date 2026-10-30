package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.data.country.readAsset
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.source.BatchGSource
import java.net.HttpURLConnection
import java.net.URL

/**
 * R4.x — the map's living data. Two REAL, keyless public feeds so the map
 * breathes even in a village where OSM transit/cameras are barren (Law 1 —
 * no invented data):
 *
 *  - [AirAlertClient] — Ukraine air-raid alert state per oblast, live, from
 *    ubilling's keyless AerialAlerts API. This is the thing that actually
 *    matters here, every day.
 *  - [RadarClient] — RainViewer precipitation radar tiles (keyless) for a
 *    "дощ підходить" weather overlay.
 *  - [OblastShapes] — bundled oblast boundary polygons (asset), so alert
 *    state paints the WHOLE country and we can name the operator's own oblast
 *    offline via point-in-polygon.
 */

internal val liveJson = Json { ignoreUnknownKeys = true }

private const val HTTP_TIMEOUT_MS = 12_000

internal fun httpGet(url: String, userAgent: String): String? {
    return runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HTTP_TIMEOUT_MS
            readTimeout = HTTP_TIMEOUT_MS
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }
        conn.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
}

// ── Air-raid alerts ──────────────────────────────────────────────────────────

enum class ThreatType { UNKNOWN, SHAHED, MISSILE, MIG, KAB, ARTILLERY, BALLISTIC, URBAN, CHEMICAL, NUCLEAR }

/**
 * ATLAS-3 — the threat vocabulary is pack DATA, the type is code.
 *
 * The keyword table used to sit here as a literal and doubled as ubilling's
 * classification rules. Both readings now come from the one place the pack
 * declares them: [AdapterSchema.classificationRules] on its region-state
 * feed, keyed by [ThreatType] name. A country whose sky speaks another
 * language ships another table — not another parser.
 *
 * Order carries meaning (most-specific first, so a "kn-23" mention isn't
 * swallowed by the broader "ракет" match), which is why the declaration is
 * an insertion-ordered map and this reader never sorts it.
 */
@Volatile
private var threatPatternsCache: Pair<Map<String, List<String>>, List<Pair<ThreatType, Regex>>>? = null

private fun threatPatterns(): List<Pair<ThreatType, Regex>> {
    val rules = local.phantom.companion.core.data.country.CountryPackRegistry
        .alarmFeed(local.phantom.companion.core.data.country.AlarmFeedRole.REGION_STATE)
        ?.schema?.classificationRules.orEmpty()
    threatPatternsCache?.let { (seen, compiled) -> if (seen == rules) return compiled }
    val compiled = rules.mapNotNull { (name, words) ->
        val type = runCatching { ThreatType.valueOf(name) }.getOrNull() ?: return@mapNotNull null
        type to Regex(words.joinToString("|"), RegexOption.IGNORE_CASE)
    }
    threatPatternsCache = rules to compiled
    return compiled
}

/**
 * Keyless, offline heuristic: maps free-text alert metadata (title/notes/
 * message — whatever a feed actually sends) to a [ThreatType]. Returns
 * [ThreatType.UNKNOWN] on no match or blank text — never guesses a threat
 * the text didn't state (Law 1).
 */
internal fun detectThreatType(text: String?): ThreatType {
    if (text.isNullOrBlank()) return ThreatType.UNKNOWN
    return threatPatterns().firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first
        ?: ThreatType.UNKNOWN
}

/** A raion/hromada/city alert inside an oblast (alerts.in.ua detail rows). */
data class DistrictAlert(
    val name: String,
    val threatType: ThreatType = ThreatType.UNKNOWN,
)

/** One oblast's live alert state. [sinceText] is a ready "N хв тому" phrase. */
data class AlertState(
    val active: Boolean,
    val changedEpochMs: Long?,
    val threatType: ThreatType = ThreatType.UNKNOWN,
    val notes: String? = null,
    /**
     * Sub-oblast alarms (raions/hromadas/cities) reported by the detailed
     * feed. May be non-empty while [active] is false — a raion-level alarm
     * is NOT painted as an oblast-wide one (Law 1), but the sheets say it.
     */
    val districts: List<DistrictAlert> = emptyList(),
) {
    val sinceText: String?
        get() {
            val ms = changedEpochMs ?: return null
            val mins = ((System.currentTimeMillis() - ms) / 60_000L).toInt()
            return when {
                mins < 0 -> null
                mins < 1 -> MapText.s(R.string.mapl_g_just_now)
                mins < 60 -> MapText.s(R.string.mapl_g_mins_ago, mins)
                mins < 24 * 60 -> MapText.s(R.string.mapl_g_hours_ago, mins / 60)
                else -> MapText.s(R.string.mapl_g_days_ago, mins / (24 * 60))
            }
        }
}

/**
 * Keyless Ukraine air-raid alert feed (ubilling AerialAlerts). Returns a map
 * of oblast name ("Житомирська область") → [AlertState], or null on failure
 * (caller keeps the previous snapshot rather than blanking the map).
 */
internal class AirAlertClient(
    private val schema: local.phantom.companion.core.net.map.AdapterSchema,
) {
    private val adapter = local.phantom.companion.core.net.map.UniversalContentAdapter()

    suspend fun fetch(): Map<String, AlertState>? {
        val events = adapter.fetchAndNormalize(schema).firstOrNull().orEmpty()
        // ubilling always reports ~27 oblasts when healthy; an empty result
        // here means the request/parse degraded (network hiccup, shape
        // drift) rather than "no oblasts exist" — treat it like the old
        // client's null-on-failure so the caller keeps its last good
        // snapshot instead of blanking every alert on a transient glitch.
        if (events.isEmpty()) return null
        return events.associate { event ->
            event.id to AlertState(
                active = event.isActive,
                changedEpochMs = event.timestamp.takeIf { it > 0L },
                threatType = runCatching { ThreatType.valueOf(event.primaryType) }.getOrDefault(ThreatType.UNKNOWN),
                notes = event.contextualPayload["text"],
            )
        }
    }
}

/**
 * alerts.in.ua `alert_type` → [ThreatType]. The typed field wins when it is
 * a specific danger; a generic `air_raid` falls back to the free-text notes
 * (which is where «шахеди»/«балістика» actually appear).
 */
internal fun threatFromAlertsInUa(alertType: String?, notes: String?): ThreatType = when (alertType) {
    "artillery_shelling" -> ThreatType.ARTILLERY
    "urban_fights" -> ThreatType.URBAN
    "chemical" -> ThreatType.CHEMICAL
    "nuclear" -> ThreatType.NUCLEAR
    else -> detectThreatType(notes)
}

/** ISO-8601 (`2026-07-18T04:02:13.000Z` / `+03:00`) → epoch ms, null on junk. */
private fun parseIsoEpochMs(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(raw).toEpochMilli() }
        .getOrNull()
}

/**
 * Detailed alert client using alerts.in.ua. Only works if a key is provided.
 * Uses the VaultStore to retrieve `secrets.alerts_in_ua_token`.
 *
 * Beyond the oblast on/off it contributes what ubilling cannot: the typed
 * threat (`alert_type` + notes), the true `started_at`, and the raion/
 * hromada-level rows folded into each oblast's [AlertState.districts].
 */
internal class AlertsInUaClient(
    private val getToken: suspend () -> String?,
    private val endpoint: String,
    private val userAgent: String,
) {
    /**
     * Feed keys must line up with UBILLING's naming (the active-set
     * authority) and the bundled polygons: both say «м. Київ». The old
     * rename to bare «Київ» made every capital-city detail row miss the
     * merge silently.
     */
    private fun normalizeOblast(name: String): String =
        if (name == BatchGSource.KYIV_RAW) BatchGSource.KYIV_CITY else name

    suspend fun fetch(): Map<String, AlertState>? = withContext(Dispatchers.IO) {
        val token = getToken() ?: return@withContext null
        val body = runCatching {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return@withContext null

        runCatching {
            val root = liveJson.parseToJsonElement(body).jsonObject
            val alerts = root["alerts"]?.jsonArray ?: return@runCatching emptyMap()

            val map = mutableMapOf<String, AlertState>()
            val districts = mutableMapOf<String, MutableList<DistrictAlert>>()
            for (alertEl in alerts) {
                val alert = alertEl.jsonObject
                val locationType = alert["location_type"]?.jsonPrimitive?.contentOrNull
                val title = alert["location_title"]?.jsonPrimitive?.contentOrNull ?: continue
                val notes = alert["notes"]?.jsonPrimitive?.contentOrNull
                val threatType = threatFromAlertsInUa(
                    alert["alert_type"]?.jsonPrimitive?.contentOrNull,
                    notes,
                )

                if (locationType == "oblast") {
                    map[normalizeOblast(title)] = AlertState(
                        active = true,
                        changedEpochMs = parseIsoEpochMs(
                            alert["started_at"]?.jsonPrimitive?.contentOrNull,
                        ),
                        threatType = threatType,
                        notes = notes?.takeIf { it.isNotBlank() },
                    )
                } else {
                    // Raion/hromada/city rows carry their oblast by name.
                    val oblast = alert["location_oblast"]?.jsonPrimitive?.contentOrNull ?: continue
                    val key = normalizeOblast(oblast)
                    if (title != oblast) {
                        districts.getOrPut(key) { mutableListOf() } += DistrictAlert(title, threatType)
                    }
                }
            }
            // Attach district rows; an oblast with ONLY raion alarms stays
            // active=false (the map must not paint a whole oblast red for a
            // frontline raion), but the sheets get to name the raions.
            districts.forEach { (oblast, list) ->
                val base = map[oblast] ?: AlertState(active = false, changedEpochMs = null)
                map[oblast] = base.copy(districts = list.distinct())
            }
            map
        }.getOrNull()
    }
}

// ── Precipitation radar ──────────────────────────────────────────────────────

/** A ready-to-use RainViewer raster tile template + the frame's wall time. */
data class RadarOverlay(
    val urlTemplate: String,
    val frameEpochSec: Long,
)

/**
 * Keyless RainViewer radar. Resolves the latest available frame into a
 * `{z}/{x}/{y}` tile template MapLibre can consume directly.
 */
class RadarClient(
    private val endpoint: String = "https://api.rainviewer.com/public/weather-maps.json",
    private val userAgent: String = "PhantomCompanion/0.1 (map radar overlay)",
) {
    suspend fun fetch(): RadarOverlay? = fetchFrames().lastOrNull()

    /**
     * LEVIATHAN-4 — the LIVING radar: the last [maxPast] observed frames plus
     * [maxNowcast] forecast frames, oldest first, ready to be cycled as an
     * animation. Every frame is a real RainViewer raster — the animation is
     * weather history playing, not decoration.
     */
    suspend fun fetchFrames(maxPast: Int = 5, maxNowcast: Int = 2): List<RadarOverlay> =
        withContext(Dispatchers.IO) {
            val body = httpGet(endpoint, userAgent) ?: return@withContext emptyList()
            runCatching {
                val root = liveJson.parseToJsonElement(body).jsonObject
                val host = root["host"]?.jsonPrimitive?.contentOrNull ?: return@runCatching emptyList()
                val radar = root["radar"]?.jsonObject ?: return@runCatching emptyList()
                fun frames(key: String, take: Int, fromEnd: Boolean): List<RadarOverlay> {
                    val arr = radar[key]?.jsonArray ?: return emptyList()
                    val picked = if (fromEnd) arr.toList().takeLast(take) else arr.toList().take(take)
                    return picked.mapNotNull { el ->
                        val o = el.jsonObject
                        val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val time = o["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        // color 4 = Universal Blue, options 1_1 = smooth + show-snow.
                        RadarOverlay("$host$path/256/{z}/{x}/{y}/4/1_1.png", time)
                    }
                }
                frames("past", maxPast, fromEnd = true) + frames("nowcast", maxNowcast, fromEnd = false)
            }.getOrDefault(emptyList())
        }
}

// ── Oblast boundary polygons (bundled asset) ─────────────────────────────────

/**
 * The bundled Ukraine oblast polygons. Loaded once from assets. Serves two
 * jobs: the raw GeoJSON string feeds the map's alert-fill source, and
 * [oblastAt] names the operator's oblast offline (point-in-polygon) so the
 * banner can say "твоя область".
 */
class OblastShapes private constructor(
    val rawGeoJson: String,
    private val polys: List<Pair<String, List<DoubleArray>>>, // name → outer ring [lon,lat,...]
) {
    val describesUkraine: Boolean get() = polys.isNotEmpty()

    /** Oblast whose outer ring contains ([lat],[lon]), else null. */
    fun oblastAt(lat: Double, lon: Double): String? =
        polys.firstOrNull { pointInRing(lon, lat, it.second) }?.first

    /**
     * (lat, lon) centroid of the oblast's outer ring (shoelace formula, with
     * a vertex-average fallback for degenerate rings) — where the map pins
     * the alarm's threat badge.
     */
    fun centroidOf(name: String): Pair<Double, Double>? {
        val ring = polys.firstOrNull { it.first == name }?.second ?: return null
        var area = 0.0; var cx = 0.0; var cy = 0.0
        var j = ring.size - 1
        for (i in ring.indices) {
            val cross = ring[j][0] * ring[i][1] - ring[i][0] * ring[j][1]
            area += cross
            cx += (ring[j][0] + ring[i][0]) * cross
            cy += (ring[j][1] + ring[i][1]) * cross
            j = i
        }
        if (kotlin.math.abs(area) < 1e-12) {
            return ring.sumOf { it[1] } / ring.size to ring.sumOf { it[0] } / ring.size
        }
        val scale = 1.0 / (3.0 * area)
        return (cy * scale) to (cx * scale)
    }

    companion object {
        private const val EMPTY_COLLECTION = "{\"type\":\"FeatureCollection\",\"features\":[]}"

        internal fun of(raw: String) = OblastShapes(raw, runCatching { parse(raw) }.getOrNull() ?: emptyList())

        /** Нема пака — нема регіонів. Не брехня «тут порожньо»: тут НЕ ТУТ. */
        private val NONE = OblastShapes(EMPTY_COLLECTION, emptyList())

        // @Volatile so the boot-time warm-up parse (background thread, see
        // PhantomApp.warmGeoAssets) is visible to the UI thread's load() —
        // otherwise the 708 KB geojson gets re-parsed on the main thread at
        // shell composition, the exact jank the warm-up exists to kill.
        // Keyed by asset path: crossing a border must not serve the previous
        // country's polygons out of a cache that forgot which pack filled it.
        @Volatile
        private var cached: Pair<String, OblastShapes>? = null

        /**
         * Double-checked, so a caller that loses the race to the warm-up thread
         * WAITS for that parse instead of running a second full one. Unlocked
         * this used to mean both threads paid the whole 708 KB — the worst of
         * both worlds on exactly the cold-launch frame we are protecting.
         */
        fun load(context: Context): OblastShapes {
            val pack = local.phantom.companion.core.data.country.CountryPackRegistry.current()
            val asset = pack?.regionGeometryAsset ?: return NONE
            cached?.let { (path, shapes) -> if (path == asset) return shapes }
            synchronized(this) {
                cached?.let { (path, shapes) -> if (path == asset) return shapes }
                // Через пак, не через `context.assets`: у завантаженого пака
                // геометрія лежить у власному каталозі, а не у збірці.
                val raw = pack.readAsset(context, asset) ?: EMPTY_COLLECTION
                val polys = runCatching { parse(raw) }.getOrNull() ?: emptyList()
                return OblastShapes(raw, polys).also { cached = asset to it }
            }
        }

        private fun parse(raw: String): List<Pair<String, List<DoubleArray>>> {
            val feats = liveJson.parseToJsonElement(raw).jsonObject["features"]?.jsonArray
                ?: return emptyList()
            return feats.mapNotNull { fe ->
                val f = fe.jsonObject
                val name = f["properties"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val geom = f["geometry"]?.jsonObject ?: return@mapNotNull null
                if (geom["type"]?.jsonPrimitive?.contentOrNull != "Polygon") return@mapNotNull null
                val outer = geom["coordinates"]?.jsonArray?.firstOrNull()?.jsonArray
                    ?: return@mapNotNull null
                val ring = outer.mapNotNull { pt ->
                    val a = pt.jsonArray
                    val lonV = a.getOrNull(0)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    val latV = a.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    if (lonV != null && latV != null) doubleArrayOf(lonV, latV) else null
                }
                if (ring.size >= 3) name to ring else null
                // Smallest bbox first: «м. Київ» sits inside Київська
                // область's outer ring, and [oblastAt] takes the FIRST
                // containing polygon — a point in the capital must resolve
                // to the city, not the oblast that surrounds it.
            }.sortedBy { (_, ring) ->
                val lons = ring.map { it[0] }; val lats = ring.map { it[1] }
                (lons.max() - lons.min()) * (lats.max() - lats.min())
            }
        }

        /** Ray-casting point-in-polygon on a [lon,lat] ring. */
        private fun pointInRing(lon: Double, lat: Double, ring: List<DoubleArray>): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val xi = ring[i][0]; val yi = ring[i][1]
                val xj = ring[j][0]; val yj = ring[j][1]
                if (((yi > lat) != (yj > lat)) &&
                    (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi)
                ) inside = !inside
                j = i
            }
            return inside
        }
    }
}
