package local.phantom.companion.core.net.map

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * NAV-4 — the dossier fetchers: keyless Overpass & Wikipedia sources.
 */
class PlaceDossierClient(
    private val wikipediaUrl: String = "https://uk.wikipedia.org/w/api.php",
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val overpassEndpoints = OverpassMirrors.ENDPOINTS

    /** The OSM element behind the tap: full tags, honest or absent. */
    data class PlaceDetail(
        val osmId: Long,
        val lat: Double,
        val lon: Double,
        val tags: Map<String, String>,
    )

    /** A Wikipedia article anchored near the point. */
    data class WikiCard(
        val title: String,
        val extract: String,
        val thumbnailUrl: String?,
        val pageUrl: String,
    )

    /** The building volume at the point (Crystal City's data, as facts). */
    data class BuildingCard(
        val levels: Int?,
        val heightM: Double?,
        val buildingType: String?,
        val street: String?,
        val houseNumber: String?,
        val name: String?,
    )

    /** One transit route relation serving a stop. */
    data class RouteLineRef(
        val relationId: Long,
        val ref: String?,
        val name: String?,
        val mode: String,
        val colour: String?,
        val from: String?,
        val to: String?,
    )

    /**
     * Resolves OSM elements within [radiusM] of the point, nearest-first.
     * Matches both named venues and tagged infrastructure (stops, ATMs, shelters, chargers).
     */
    suspend fun fetchNamedAround(lat: Double, lon: Double, radiusM: Int = 60): List<PlaceDetail> {
        val query = """
            [out:json][timeout:10];
            (
              node(around:$radiusM,$lat,$lon);
              way(around:$radiusM,$lat,$lon);
            );
            out tags center 25;
        """.trimIndent()
        return overpass(query).mapNotNull { el ->
            val la = el.effectiveLat ?: return@mapNotNull null
            val lo = el.effectiveLon ?: return@mapNotNull null
            val tags = el.tags ?: emptyMap()
            if (tags.isEmpty()) return@mapNotNull null
            PlaceDetail(el.id, la, lo, tags)
        }.sortedBy { haversineM(lat, lon, it.lat, it.lon) }
    }

    /**
     * The building footprint containing/nearest the point — levels, height, type, address.
     */
    suspend fun fetchBuildingAt(lat: Double, lon: Double): BuildingCard? {
        val query = """
            [out:json][timeout:10];
            way["building"](around:25,$lat,$lon);
            out tags center 3;
        """.trimIndent()
        val tags = overpass(query).firstOrNull()?.tags ?: return null
        return BuildingCard(
            levels = tags["building:levels"]?.toDoubleOrNull()?.toInt(),
            heightM = tags["height"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull(),
            buildingType = tags["building"]?.takeIf { it != "yes" },
            street = tags["addr:street"],
            houseNumber = tags["addr:housenumber"],
            name = tags["name"],
        )
    }

    /**
     * Transit route relations serving a stop at the point.
     */
    suspend fun fetchStopRoutes(lat: Double, lon: Double, radiusM: Int = 80): List<RouteLineRef> {
        val query = """
            [out:json][timeout:12];
            relation["route"~"^(bus|tram|trolleybus|subway|train|light_rail|share_taxi)${'$'}"](around:$radiusM,$lat,$lon);
            out tags 40;
        """.trimIndent()
        return overpass(query).mapNotNull { el ->
            val tags = el.tags ?: return@mapNotNull null
            RouteLineRef(
                relationId = el.id,
                ref = tags["ref"],
                name = tags["name"],
                mode = tags["route"] ?: "bus",
                colour = tags["colour"]?.takeIf { it.startsWith("#") },
                from = tags["from"],
                to = tags["to"],
            )
        }.distinctBy { it.ref ?: it.name ?: it.relationId.toString() }
    }

    /**
     * NAV-6 — геометрія одного відношення маршруту, ЗШИТА в звʼязні ланцюги.
     *
     * Повертає список ланцюгів, а не одну ламану: між незвʼязаними членами
     * відношення немає дороги, і одна ламана намалювала б там пряму хорду
     * через місто. Розбір і зшивання — у [TransitRelationGeometry].
     */
    suspend fun fetchRouteSegments(relationId: Long): List<List<Pair<Double, Double>>> {
        val query = "[out:json][timeout:15];relation($relationId);out geom;"
        val body = overpassRaw(query) ?: return emptyList()
        return TransitRelationGeometry.parse(body, json)
    }

    /** Wikipedia articles anchored near the point (backward compatible alias). */
    suspend fun fetchWiki(lat: Double, lon: Double, nameHint: String? = null): WikiCard? =
        fetchWikipediaArticle(lat, lon, 400, nameHint)

    /** Wikipedia articles anchored near the point. */
    suspend fun fetchWikipediaArticle(
        lat: Double,
        lon: Double,
        radiusM: Int = 200,
        nameHint: String? = null,
    ): WikiCard? {
        val url = geosearchUrl(lat, lon, radiusM)
        return withContext(Dispatchers.IO) {
            val body = httpGet(url) ?: return@withContext null
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@withContext null
            val geosearch = root["query"]?.jsonObject?.get("geosearch")?.jsonArray ?: return@withContext null
            val pageIds = geosearch.mapNotNull { it.jsonObject["pageid"]?.jsonPrimitive?.content }
            if (pageIds.isEmpty()) return@withContext null

            val detailsUrl = "$wikipediaUrl?action=query&pageids=${pageIds.joinToString("|")}" +
                "&format=json&prop=extracts|pageimages&exintro=1&explaintext=1" +
                "&piprop=thumbnail&pithumbsize=400"
            val detailsBody = httpGet(detailsUrl) ?: return@withContext null
            val pages = runCatching {
                json.parseToJsonElement(detailsBody).jsonObject["query"]
                    ?.jsonObject?.get("pages")?.jsonObject
            }.getOrNull() ?: return@withContext null

            val cards = pages.values.mapNotNull { node ->
                val p = node.jsonObject
                val title = p["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val extract = p["extract"]?.jsonPrimitive?.content?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                WikiCard(
                    title = title,
                    extract = extract,
                    thumbnailUrl = p["thumbnail"]?.jsonObject?.get("source")?.jsonPrimitive?.content,
                    pageUrl = "https://uk.wikipedia.org/wiki/" +
                        URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                )
            }
            val hint = nameHint?.lowercase()?.trim()
            if (hint != null) {
                cards.firstOrNull { it.title.lowercase().contains(hint) || hint.contains(it.title.lowercase()) }
                    ?: cards.firstOrNull()
            } else cards.firstOrNull()
        }
    }

    internal fun geosearchUrl(lat: Double, lon: Double, radiusM: Int): String =
        "$wikipediaUrl?action=query&list=geosearch&gsradius=$radiusM" +
            "&gscoord=${coarse(lat)}|${coarse(lon)}&gslimit=5&format=json&prop=extracts|pageimages" +
            "&exintro=1&explaintext=1&piprop=thumbnail&pithumbsize=400"

    private fun coarse(deg: Double): String = String.format(Locale.US, "%.3f", deg)

    private suspend fun overpass(query: String): List<OverpassElement> {
        val body = overpassRaw(query) ?: return emptyList()
        return runCatching {
            json.decodeFromString<OverpassResponse>(body).elements
        }.getOrDefault(emptyList())
    }

    private suspend fun overpassRaw(query: String): String? {
        val normalized = query.replace("\n", " ").trim()
        val data = URLEncoder.encode(normalized, "UTF-8")
        for (mirror in overpassEndpoints) {
            val refusal = OverpassBudget.shared.acquire(mirror)
            if (refusal != null) {
                Log.w(TAG, "досьє: ${OverpassMirrors.hostOf(mirror)} пропущено — ${refusal.detail}")
                continue
            }
            val res = httpGet("$mirror?data=$data")
            if (!res.isNullOrBlank()) return res
        }
        Log.w(TAG, "досьє: Overpass недоступний (не «нічого немає») — усі ${overpassEndpoints.size} дзеркал мовчать")
        return null
    }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 4_000
                readTimeout = 8_000
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

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val TAG = "PlaceDossierClient"
        const val USER_AGENT = OverpassMirrors.USER_AGENT
    }
}
