package local.phantom.companion.feature.stream.ui.map.crystal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import local.phantom.companion.core.net.map.OverpassBudget
import local.phantom.companion.core.net.map.OverpassMirrors
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Crystal City C1 — keyless Overpass fetcher for one building tile.
 *
 * Same etiquette shape as [NominatimClient]: descriptive User-Agent, a
 * process-wide mutex so at most one Overpass request is ever in flight,
 * and a minimum gap between requests. Overpass mirrors are community
 * infrastructure — a phone panning across Kyiv must read as a polite
 * client, not a scraper. The [BuildingTileStore] disk cache in front of
 * this keeps re-fetches to once per tile per week.
 *
 * `out tags geom` inlines way geometry and relation member geometry, so
 * one request yields fully-resolvable footprints with no follow-up
 * node lookups.
 */
class OverpassBuildingsClient(
    private val userAgent: String = NominatimClientUserAgent,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
) {
    private val rateLimitMutex = Mutex()
    private var lastRequestAtMs: Long = 0L

    /** Raw Overpass JSON for the tile, or null when every mirror failed. */
    suspend fun fetchTile(tile: CrystalTileKey): String? = withContext(Dispatchers.IO) {
        val b = CrystalGeo.boundsOf(tile)
        val query = """
            [out:json][timeout:$SERVER_TIMEOUT_S];
            (
              way["building"](${b.south},${b.west},${b.north},${b.east});
              relation["building"]["type"="multipolygon"](${b.south},${b.west},${b.north},${b.east});
              way["leisure"~"^(park|garden)$"](${b.south},${b.west},${b.north},${b.east});
              way["landuse"~"^(forest|grass|meadow|village_green|recreation_ground)$"](${b.south},${b.west},${b.north},${b.east});
              way["natural"~"^(wood|scrub|grassland)$"](${b.south},${b.west},${b.north},${b.east});
              relation["leisure"~"^(park|garden)$"]["type"="multipolygon"](${b.south},${b.west},${b.north},${b.east});
              node["natural"="tree"](${b.south},${b.west},${b.north},${b.east});
            );
            out tags geom meta;
        """.trimIndent()
        rateLimitMutex.withLock {
            // The public instances load-shed bursts with 429/504 (verified
            // live: 3rd rapid request from one IP → 504). Retry rounds with
            // growing pauses turn that into an eventual success instead of
            // punting to the feed's 60s camera-idle backoff.
            // Fallback mirrors are hang-prone (kumi.systems observed accepting
            // the connection then sending nothing for 40s+), and a hung mirror
            // holds this mutex for the full read timeout, stalling every other
            // tile. The primary fails *fast* (sub-second 504), so early rounds
            // retry it alone and mirrors are only tried on the final round.
            var body: String? = null
            for (round in 0 until MAX_ROUNDS) {
                if (round > 0) delay(ROUND_BACKOFF_MS * round)
                val sinceLast = System.currentTimeMillis() - lastRequestAtMs
                if (sinceLast < MIN_GAP_MS) delay(MIN_GAP_MS - sinceLast)
                val candidates = if (round < MAX_ROUNDS - 1) endpoints.take(1) else endpoints.take(MAX_FANOUT)
                body = candidates.firstNotNullOfOrNull { endpoint ->
                    // Локальний MIN_GAP_MS вище лишається як був; спільний бюджет
                    // додається зверху, щоб ці тайли рахувались разом із рештою
                    // трафіку Overpass, а не паралельно з ним.
                    //
                    // Чекати довго тут не можна: цей виклик тримає rateLimitMutex,
                    // тобто гальмує КОЖЕН інший тайл. Тайли — фонові, тож вони
                    // поступаються інтерактивним запитам і пробують у наступному
                    // раунді, а не стоять у черзі бюджету.
                    if (OverpassBudget.shared.acquire(endpoint, BUDGET_WAIT_MS) != null) {
                        return@firstNotNullOfOrNull null
                    }
                    val result = runCatching { post(endpoint, query) }.getOrNull()
                    lastRequestAtMs = System.currentTimeMillis()
                    result
                }
                if (body != null) break
            }
            body
        }
    }

    private fun post(endpoint: String, query: String): String? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 10_000
            readTimeout = (SERVER_TIMEOUT_S + 10) * 1_000
        }
        return try {
            conn.outputStream.bufferedWriter().use {
                // "UTF-8" рядком — перевантаження з Charset є лише з API 33
                // при minSdk 29 без десугарування. Див. NominatimClient.
                it.write("data=" + URLEncoder.encode(query, "UTF-8"))
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val DEFAULT_ENDPOINTS = OverpassMirrors.ENDPOINTS

        /**
         * Скільки дзеркал пробувати в останньому раунді. Зависле дзеркало
         * тримає мʼютекс усі 35 с читання й гальмує кожен інший тайл, тож
         * віяло лишається таким, як було до переїзду на спільний реєстр.
         */
        private const val MAX_FANOUT = 2
        private const val BUDGET_WAIT_MS = 4_000L
        private const val MIN_GAP_MS = 2_000L
        private const val SERVER_TIMEOUT_S = 25
        private const val MAX_ROUNDS = 3
        private const val ROUND_BACKOFF_MS = 4_000L
    }
}

/** Reuse the Nominatim UA so all OSM-infrastructure traffic identifies the same app. */
private val NominatimClientUserAgent =
    local.phantom.companion.feature.stream.ui.map.NominatimClient.DEFAULT_USER_AGENT
