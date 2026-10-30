package local.phantom.companion.data

import android.content.Context
import android.location.Geocoder
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import local.phantom.companion.core.net.geosrc.MinIntervalLimiter
import local.phantom.companion.core.net.map.CrimeaUa
import local.phantom.companion.core.net.map.GeoEndpoints
import local.phantom.companion.core.net.map.GeocodeDialect
import local.phantom.companion.core.net.map.GeocoderKind
import org.json.JSONObject

/**
 * R1.1 — зворотне геокодування для ambient-заголовка чату й досьє місця.
 *
 * 1. [Geocoder] самої системи (те, що вже вміє апарат).
 * 2. Свій налаштований геокодер із [GeoEndpoints] — і більше нічого.
 *
 * Тут стояло ще два тіри: `photon.komoot.io`, далі публічний Nominatim, обидва
 * з `?lat=…&lon=…` у рядку адреси й БЕЗ жодної перевірки конфігурації. Кличе це
 * не людина: `StreamVmFactory.locationAddressProvider` прогріває адресу у фоні
 * на кожному промаху кеша, тобто щоразу, коли оператор зрушив із місця більш
 * ніж на ~100 м (ключ кеша — три знаки після коми). Чужий сервер отримував
 * доріжку координат за те, що людина просто розмовляла в чаті.
 *
 * Нема свого сервера і система мовчить — чесна відповідь `null`. Заголовок
 * просто не покаже вулиці; він і так її не показував, поки кеш холодний.
 */
class ReverseGeocoder(private val context: Context) {

    private val cache = ConcurrentHashMap<String, String>()

    /** Sync cache read for the ambient header; null until warmed. */
    fun cachedAddress(lat: Double, lon: Double): String? = cache[key(lat, lon)]

    /**
     * Адреса («вулиця, номер, місто») для [lat],[lon]: спершу кеш, далі система,
     * далі свій сервер. Нема жодного — `null`, а не здогад із чужого сервера.
     */
    suspend fun coarseAddress(lat: Double, lon: Double): String? {
        cache[key(lat, lon)]?.let { return it }

        val resolved = withContext(Dispatchers.IO) {
            // Tier 1: Android System Geocoder
            if (Geocoder.isPresent()) {
                val sysRes = runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.getDefault())
                        .getFromLocation(lat, lon, 1)
                        ?.firstOrNull()
                        ?.let { a ->
                            val street = a.thoroughfare
                            val number = a.subThoroughfare ?: a.featureName?.takeIf { it.any { c -> c.isDigit() } }
                            val streetPart = when {
                                street != null && number != null -> "$street, $number"
                                street != null -> street
                                number != null -> "№ $number"
                                else -> a.featureName
                            }
                            val loc = a.locality ?: a.subAdminArea ?: a.adminArea
                            listOfNotNull(streetPart, loc).joinToString(", ").ifBlank { null }
                        }
                }.getOrNull()
                if (!sysRes.isNullOrBlank()) return@withContext sysRes
            }

            // Тір 2: свій налаштований геокодер. Основа — лише з конфігурації;
            // зашитого публічного сервера тут немає й бути не може. `null`
            // означає «онлайн-геокодування вимкнено», і це кінець шляху.
            val config = GeoEndpoints.config
            val base = config.geocodeBaseUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
            awaitPublicGeocodeSlot()
            runCatching {
                val url = URL(GeocodeDialect.reverseUrl(base, lat, lon, config.geocoderKind))
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PhantomCompanion/0.2 (operator)")
                    connectTimeout = 4_000
                    readTimeout = 6_000
                }
                if (conn.responseCode !in 200..299) return@runCatching null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                when (config.geocoderKind) {
                    GeocoderKind.NOMINATIM -> shortNominatimAddress(JSONObject(text))
                    GeocoderKind.PHOTON -> shortPhotonAddress(JSONObject(text))
                }
            }.getOrNull()
        }?.let { CrimeaUa.text(it, lat, lon) }

        if (resolved != null) cache[key(lat, lon)] = resolved
        return resolved
    }

    private fun key(lat: Double, lon: Double): String =
        "%.3f,%.3f".format(Locale.US, lat, lon)
}

/**
 * «вулиця, номер, місто» — не весь `display_name`.
 *
 * Значення йде в ambient-заголовок промпта ШІ, де повний рядок Nominatim
 * («22, вулиця Хрещатик, Печерський район, Київ, 01001, Україна») — це шість
 * зайвих токенів і жодного зайвого змісту. Розбір саме той, що був у знятому
 * публічному тірі; змінилась лише адреса, звідки приходить тіло.
 */
private fun shortNominatimAddress(json: JSONObject): String? {
    val addr = json.optJSONObject("address")
        ?: return json.optString("display_name").takeIf { it.isNotBlank() }
    val road = addr.optString("road").takeIf { it.isNotBlank() }
        ?: addr.optString("building").takeIf { it.isNotBlank() }
    val houseNum = addr.optString("house_number").takeIf { it.isNotBlank() }
    val city = addr.optString("city").takeIf { it.isNotBlank() }
        ?: addr.optString("town").takeIf { it.isNotBlank() }
        ?: addr.optString("village").takeIf { it.isNotBlank() }
    val streetPart = when {
        road != null && houseNum != null -> "$road, $houseNum"
        road != null -> road
        houseNum != null -> "№ $houseNum"
        else -> null
    }
    return listOfNotNull(streetPart, city).joinToString(", ").ifBlank { null }
}

/** Те саме для діалекту Photon: GeoJSON, властивості першої ознаки. */
private fun shortPhotonAddress(json: JSONObject): String? {
    val features = json.optJSONArray("features") ?: return null
    if (features.length() == 0) return null
    val props = features.getJSONObject(0).optJSONObject("properties") ?: return null
    val street = props.optString("street").takeIf { it.isNotBlank() }
    val num = props.optString("housenumber").takeIf { it.isNotBlank() }
    val name = props.optString("name").takeIf { it.isNotBlank() }
    val city = props.optString("city").takeIf { it.isNotBlank() }
        ?: props.optString("state").takeIf { it.isNotBlank() }
    val streetWithNum = when {
        street != null && num != null -> "$street, $num"
        street != null -> street
        num != null -> "№ $num"
        else -> name
    }
    return listOfNotNull(streetWithNum, city).joinToString(", ").ifBlank { null }
}

/** Підлога політики Nominatim — 1 запит/с; свій сервер її ж і успадковує. */
internal const val PUBLIC_GEOCODE_MIN_GAP_MS = 1_100L

/**
 * Один бюджет запитів на весь процес.
 *
 * Тір назовні не мав жодного обмежувача: ambient-заголовок сам по собі
 * прогріває адресу на кожному русі оператора, і на сервер летіла черга —
 * 429 і бан на IP приходили саме звідти. Публічних тірів тут більше немає,
 * але лічильник лишається: свій сервер теж можна покласти чергою, а поводитись
 * із ним гірше, ніж із чужим, немає жодної причини.
 */
internal val publicGeocodeLimiter = MinIntervalLimiter(PUBLIC_GEOCODE_MIN_GAP_MS)

internal suspend fun awaitPublicGeocodeSlot(limiter: MinIntervalLimiter = publicGeocodeLimiter) {
    while (!limiter.tryAcquire()) delay(limiter.retryAfterMs())
}
