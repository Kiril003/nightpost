package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.map.CrimeaUa
import local.phantom.companion.core.net.map.GeoLang
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.source.BatchHSource
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * MAP-2 — клієнт геокодування.
 *
 * Два напрямки живуть за РІЗНИМИ правилами, і це навмисно:
 *
 *  • [reverse] («як зветься точка X») — лише свій налаштований сервер. Тір
 *    назовні заборонений: координати — це те, де стоїть оператор.
 *  • [search] («де Хрещатик 22») — лише свій сервер; публічних тірів немає (R0-01, 22.10).
 *
 * [baseUrl] не має типового значення навмисно. Доти тут стояв
 * `= PUBLIC_NOMINATIM`, тобто `NominatimClient()` мовчки означав «публічний
 * сервер OSM» — і будь-який новий виклик отримував чужий сервер, нічого про це
 * не сказавши. Основу тепер зобовʼязаний назвати той, хто будує клієнта; єдине
 * бойове місце — `geocodeClientFor`, і воно бере її з конфігурації.
 */
class NominatimClient(
    private val baseUrl: String,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** Set by `geocodeClientFor` when the configured base is the emulator-only
     *  dev host — unreachable from a real phone, and each attempt costs a full
     *  connect timeout before the next tier gets its turn. */
    private val isEmulatorDev: Boolean = false,
    locale: Locale = Locale.getDefault(),
) : GeocodeClient {
    private val rateLimitMutex = Mutex()
    private var lastRequestAtMs: Long = 0L
    private val acceptLanguage = GeoLang.acceptLanguage(locale)

    override suspend fun search(query: String, limit: Int, near: Pair<Double, Double>?): List<NominatimResult> = withContext(Dispatchers.IO) {
        val raw = query.trim()
        if (raw.isBlank()) return@withContext emptyList()

        // 1. Instant coordinate parsing (e.g. "50.4501, 30.5234" or "50.4501 30.5234")
        val coordMatch = COORD_REGEX.matcher(raw)
        if (coordMatch.matches()) {
            val lat = coordMatch.group(1)?.toDoubleOrNull()
            val lon = coordMatch.group(2)?.toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                val formatted = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon)
                return@withContext listOf(
                    NominatimResult(
                        lat = lat.toString(),
                        lon = lon.toString(),
                        displayName = MapText.s(R.string.mapl_h_coordinates_fmt, formatted),
                        type = "coordinate",
                        address = NominatimAddress(road = MapText.s(R.string.mapl_h_coordinates), houseNumber = formatted),
                    ),
                )
            }
        }

        // Рядок "UTF-8", а НЕ Charsets.UTF_8: перевантаження з Charset існує лише
        // з API 33, а minSdk тут 29 і десугарування в дереві вимкнено скрізь.
        // На Android 10-12 форма з Charset дає NoSuchMethodError у першому ж
        // пошуку — тобто «пошук місць нічого не знаходить» перетворюється на
        // падіння, якого не видно ні на емуляторі (API 34), ні на телефоні
        // власника (Android 16). Не «модернізувати» назад без десугарування.
        val rawEncoded = URLEncoder.encode(raw, "UTF-8")

        // 2. Operator's own configured server answers first: no keystroke reaches a
        //    third party while the sovereign box can still serve it.
        if (!isEmulatorDev && baseUrl.isNotBlank()) {
            rateLimit()
            val res = runCatching { fetchJson(nominatimSearchUrl(baseUrl, rawEncoded, limit, acceptLanguage)) }.getOrNull()
            if (!res.isNullOrBlank()) {
                val parsed = runCatching { json.decodeFromString<List<NominatimResult>>(res).map { it.inUkraine() } }.getOrNull()
                if (!parsed.isNullOrEmpty()) return@withContext parsed
            }
        }

        emptyList()
    }

    /**
     * Зворотне геокодування має РІВНО ОДИН тір — свій сервер. Запасного назовні
     * нема і не буде.
     *
     * Тут стояло три: свій сервер, далі `photon.komoot.io`, далі публічний
     * Nominatim — з `?lat=…&lon=…` просто в рядку адреси. Кличе це не людина:
     * MapScreen геокодує ЦЕНТР КАДРУ через 1,5 с після кожного зсуву далі ніж
     * на 200 м. Оператор возить мапу пальцем — чужий сервер збирає доріжку його
     * координат. І вмикав цю доріжку кожен збій свого сервера: таймаут, LAN
     * поза домашнім Wi-Fi, порожня відповідь.
     *
     * Питання «як зветься те, на що я дивлюсь» не варте розкриття того, де я
     * стою. Нема свого сервера — відповідь `null`; усі три виклики вже вміють
     * її читати (підпис просто лишається порожнім).
     */
    override suspend fun reverse(lat: Double, lon: Double): NominatimResult? = withContext(Dispatchers.IO) {
        if (isEmulatorDev || baseUrl.isBlank()) return@withContext null
        rateLimit()
        val res = runCatching { fetchJson(nominatimReverseUrl(baseUrl, lat, lon, acceptLanguage)) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        runCatching { json.decodeFromString<NominatimResult>(res).inUkraine() }.getOrNull()
    }

    private suspend fun rateLimit() = rateLimitMutex.withLock {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastRequestAtMs
        if (sinceLast < MIN_GAP_MS) {
            delay(MIN_GAP_MS - sinceLast)
        }
        lastRequestAtMs = System.currentTimeMillis()
    }

    private fun fetchJson(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 4_500
            readTimeout = 7_500
        }
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "PhantomCompanion/0.2 (operator)"

        /** Nominatim's usage policy is one request per second, absolute. The old
         *  500 ms broke it on every second call and earns a 429/ban. */
        internal const val MIN_GAP_MS = 1_100L
        private val COORD_REGEX = Pattern.compile("^\\s*([+-]?\\d{1,2}(?:\\.\\d+)?)[,\\s]+([+-]?\\d{1,3}(?:\\.\\d+)?)\\s*$")
    }
}

/** No `countrycodes`: the search answers wherever the operator is standing. */
internal fun nominatimSearchUrl(base: String, encodedQuery: String, limit: Int, acceptLanguage: String): String =
    "$base/search?q=$encodedQuery&format=json&limit=$limit&addressdetails=1&extratags=1&accept-language=$acceptLanguage"

internal fun nominatimReverseUrl(base: String, lat: Double, lon: Double, acceptLanguage: String): String =
    "$base/reverse?lat=$lat&lon=$lon&format=json&addressdetails=1&extratags=1&accept-language=$acceptLanguage"

/**
 * Знімає службові префікси адреси, які збивають геокодер («вул. Хрещатик 22»).
 *
 * Український набір лишається таким, як був, і зачищається будь-де в рядку.
 * Чужомовні скорочення — лише на початку запиту й лише ті, що завжди стоять
 * попереду назви: «Baker Street» і «Via Roma» мають зберегти слово, яке несе
 * їхній сенс.
 */
internal fun normalizeGeoQuery(input: String): String =
    input
        .replace(BatchHSource.UK_ADDRESS_PREFIXES, "")
        .replace(BatchHSource.LEADING_ADDRESS_ABBREV, "")
        .trim()
        .ifBlank { input.trim() }

@Serializable
data class NominatimAddress(
    val road: String? = null,
    @kotlinx.serialization.SerialName("house_number")
    val houseNumber: String? = null,
    val building: String? = null,
    val suburb: String? = null,
    val neighbourhood: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val state: String? = null,
    val postcode: String? = null,
    val country: String? = null,
    val amenity: String? = null,
    val shop: String? = null,
) {
    val streetWithNumber: String? get() {
        val st = road ?: building
        val num = houseNumber
        return when {
            st != null && num != null -> "$st, $num"
            st != null -> st
            num != null -> "№ $num"
            else -> null
        }
    }

    val locality: String? get() = city ?: town ?: village ?: suburb ?: state
}

@Serializable
data class NominatimResult(
    val lat: String,
    val lon: String,
    @kotlinx.serialization.SerialName("display_name")
    val displayName: String,
    val type: String? = null,
    val importance: Double? = null,
    val address: NominatimAddress? = null,
) {
    val latitude: Double get() = lat.toDoubleOrNull() ?: 0.0
    val longitude: Double get() = lon.toDoubleOrNull() ?: 0.0

    fun inUkraine(): NominatimResult {
        val at = { s: String? -> s?.let { CrimeaUa.text(it, latitude, longitude) } }
        return copy(
            displayName = CrimeaUa.text(displayName, latitude, longitude),
            address = address?.copy(
                road = at(address.road),
                city = at(address.city),
                town = at(address.town),
                village = at(address.village),
                suburb = at(address.suburb),
                state = at(address.state),
                country = at(address.country),
            ),
        )
    }

    /** Clean human title (e.g. "вул. Хрещатик, 22" or place name) instead of 100-character raw string */
    val cleanTitle: String get() {
        address?.streetWithNumber?.let { return it }
        val parts = displayName.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return parts.firstOrNull() ?: displayName
    }

    /** Clean subtitle with city / district */
    val cleanSubtitle: String get() {
        val loc = address?.locality
        if (loc != null) return loc
        val parts = displayName.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size > 1) parts.drop(1).take(2).joinToString(", ") else ""
    }
}
