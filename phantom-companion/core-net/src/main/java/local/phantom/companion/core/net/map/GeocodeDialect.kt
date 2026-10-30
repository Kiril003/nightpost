package local.phantom.companion.core.net.map

import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One geocoded place, dialect-agnostic. [name] is the full display string;
 *  callers that want a short label take `name.substringBefore(",")`. */
data class GeoPlace(val lat: Double, val lon: Double, val name: String, val kind: String? = null)

/**
 * Мова геокодера — з локалі пристрою, не з коду.
 *
 * Досі всюди стояло `lang=uk`/`accept-language=uk`: у Києві це правильна
 * відповідь, у Варшаві — українська транслітерація польських вулиць, а там,
 * де `name:uk` немає, просто порожньо. Українська лишається першою на
 * українському пристрої, бо вона перша в його локалі, а не тому, що зашита.
 */
object GeoLang {
    /** Хвіст списку: майже кожен обʼєкт OSM має англійську назву. */
    private const val UNIVERSAL = "en"

    /**
     * `accept-language` для Nominatim. Повний тег іде першим — інакше
     * zh-Hant і pt-BR злилися б зі своїми материковими варіантами.
     */
    fun acceptLanguage(locale: Locale = Locale.getDefault()): String {
        val lang = locale.language.takeIf { it.isNotBlank() } ?: return UNIVERSAL
        val tag = locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: lang
        return listOf(tag, lang, UNIVERSAL).distinct().joinToString(",")
    }

    /** Photon приймає рівно один тег, не список. */
    fun photonLang(locale: Locale = Locale.getDefault()): String =
        locale.language.takeIf { it.isNotBlank() } ?: UNIVERSAL
}

/**
 * GEO-LAW — the one place that knows how each configured geocoder dialect
 * shapes its requests and responses.
 *
 * Before this, the response shape was hardcoded to Nominatim's flat JSON in
 * every caller (feature-stream's clients AND core-symbiote's `geo.search` /
 * `map.point`), so a Photon-configured deployment (`GeocoderKind.PHOTON`) fell
 * back to bare coordinates in the AI tools even though the map's own search had
 * a Photon client. This closes that gap: build the URL and parse the body
 * through here, keyed on [GeoEndpointsConfig.geocoderKind], and every caller
 * speaks both dialects for free.
 *
 * Unparsable/garbage bodies yield an empty result, never an exception — the
 * same honest-failure contract the callers already had.
 */
object GeocodeDialect {
    private val json = Json { ignoreUnknownKeys = true }

    /** Forward-geocode request URL for [query] against [base] (no trailing slash). */
    fun searchUrl(
        base: String,
        query: String,
        limit: Int,
        kind: GeocoderKind,
        locale: Locale = Locale.getDefault(),
    ): String {
        val q = URLEncoder.encode(query, "UTF-8")
        return when (kind) {
            GeocoderKind.NOMINATIM ->
                "$base/search?q=$q&format=jsonv2&limit=$limit&accept-language=${GeoLang.acceptLanguage(locale)}"
            // Photon's search endpoint is /api; lang biases result names.
            GeocoderKind.PHOTON ->
                "$base/api?q=$q&limit=$limit&lang=${GeoLang.photonLang(locale)}"
        }
    }

    /** Reverse-geocode request URL for a point against [base]. */
    fun reverseUrl(
        base: String,
        lat: Double,
        lon: Double,
        kind: GeocoderKind,
        locale: Locale = Locale.getDefault(),
    ): String = when (kind) {
        GeocoderKind.NOMINATIM ->
            "$base/reverse?lat=$lat&lon=$lon&format=jsonv2&accept-language=${GeoLang.acceptLanguage(locale)}"
        GeocoderKind.PHOTON ->
            "$base/reverse?lat=$lat&lon=$lon&lang=${GeoLang.photonLang(locale)}"
    }

    /** Parse a forward-geocode response body into places (may be empty). */
    fun parseSearch(body: String, kind: GeocoderKind): List<GeoPlace> = runCatching {
        when (kind) {
            GeocoderKind.NOMINATIM -> parseNominatimArray(body)
            GeocoderKind.PHOTON -> parsePhotonFeatures(body)
        }
    }.getOrDefault(emptyList())

    /**
     * Parse a reverse-geocode response body into one place (or null).
     * Nominatim returns a single object here; Photon returns a feature
     * collection like its forward search, so we take the first feature.
     */
    fun parseReverse(body: String, kind: GeocoderKind): GeoPlace? = runCatching {
        when (kind) {
            GeocoderKind.NOMINATIM -> parseNominatimObject(json.parseToJsonElement(body).jsonObject)
            GeocoderKind.PHOTON -> parsePhotonFeatures(body).firstOrNull()
        }
    }.getOrNull()

    // ── Nominatim (flat jsonv2) ─────────────────────────────────────────────

    private fun parseNominatimArray(body: String): List<GeoPlace> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { parseNominatimObject(it.jsonObject) }

    private fun parseNominatimObject(o: kotlinx.serialization.json.JsonObject): GeoPlace? {
        val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val name = o["display_name"]?.jsonPrimitive?.content ?: return null
        val kind = o["type"]?.jsonPrimitive?.content
        return GeoPlace(lat, lon, CrimeaUa.text(name, lat, lon), kind)
    }

    // ── Photon (GeoJSON FeatureCollection) ──────────────────────────────────

    private fun parsePhotonFeatures(body: String): List<GeoPlace> {
        val features = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull { el ->
            val f = el.jsonObject
            val coords = f["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val lon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val p = f["properties"]?.jsonObject
            fun s(key: String) = p?.get(key)?.jsonPrimitive?.content
            val name = listOfNotNull(
                s("name"),
                listOfNotNull(s("street"), s("housenumber")).joinToString(" ").ifBlank { null },
                s("city"),
                s("state"),
                s("country"),
            ).joinToString(", ").ifBlank { return@mapNotNull null }
            GeoPlace(lat, lon, CrimeaUa.text(name, lat, lon), kind = s("osm_value") ?: s("osm_key"))
        }
    }
}
