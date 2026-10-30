package local.phantom.companion.core.net.live

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import local.phantom.companion.core.net.geosrc.GeoClock
import local.phantom.companion.core.net.geosrc.GeoHttp
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.MinIntervalLimiter
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.geosrc.SystemGeoClock

/**
 * ATLAS-4 v0 — транспорт живого шару міток (Supabase / PostgREST).
 *
 * Закон приватності цього файлу, по пунктах:
 *   • на сервер їдуть КООРДИНАТИ МІТКИ і нічого більше — позиція оператора
 *     не покидає пристрій навіть як параметр запиту;
 *   • район запитується клітинками геохешу-6 (≈1.2 км), не прямокутником;
 *   • anon-ключ живе в заголовках, ніколи в URL (URL потрапляє в журнали
 *     проксі та в історію крешів);
 *   • `device_tag` пишеться, але НЕ вичитується: власні мітки повертаються
 *     назад і гаснуть у злитті за 20 м, тож ані наш, ані чужий токен не
 *     мандрує в запиті й не осідає на пристрої.
 */

/** Ключі живого шару. Порожні — площини просто немає (чесна відсутність). */
data class LiveMarksConfig(
    val baseUrl: String = "",
    val anonKey: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && anonKey.isNotBlank() && baseUrl.startsWith("https://")

    /** Базовий шлях PostgREST без кінцевого слеша. */
    internal fun restRoot(): String = baseUrl.trimEnd('/') + "/rest/v1/$TABLE"

    companion object {
        const val TABLE: String = "live_marks"

        /**
         * Закритий словник спільного шару. Блокпоста й патруля тут немає і не буде:
         * шар синхронізується між людьми, тож така позначка — краудсорс позицій людей.
         */
        val ALLOWED_KINDS: Set<String> = setOf("ДТП")
    }
}

/** Мітка, яку пристрій віддає у спільний шар. */
data class LiveMarkDraft(
    val kind: String,
    val lat: Double,
    val lon: Double,
    val expiresAtMs: Long,
)

/** Мітка, вичитана зі спільного шару. Провенанс додає вже репозиторій. */
data class LiveMark(
    val id: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val geohash6: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

/**
 * Порт живого шару. Існує, щоб `:core-data` тестувався підробкою без Ktor —
 * і щоб транспорт можна було замінити, не чіпаючи логіку черги й злиття.
 */
interface LiveMarksPort {
    val isConfigured: Boolean

    /** Надсилає мітку. Успіх означає «сервер прийняв», не «всі побачили». */
    suspend fun push(draft: LiveMarkDraft): GeoResult<Unit>

    /**
     * Живі мітки в клітинках [cells] (префікси геохешу), новіші за [sinceMs].
     * Префікс коротший за сам geohash6 — регіон запиту грубший за мітку.
     */
    suspend fun pull(cells: List<String>, sinceMs: Long? = null): GeoResult<List<LiveMark>>
}

class LiveMarksClient(
    private val config: LiveMarksConfig,
    /** Анонімний токен встановлення. Пишеться, ніколи не читається назад. */
    private val deviceTag: String? = null,
    engine: HttpClientEngine? = null,
    private val clock: GeoClock = SystemGeoClock,
    minPullIntervalMs: Long = DEFAULT_MIN_PULL_INTERVAL_MS,
    minPushIntervalMs: Long = DEFAULT_MIN_PUSH_INTERVAL_MS,
) : LiveMarksPort {

    private val http = GeoHttp.newClient(engine, timeoutMs = 12_000L)
    private val pullLimiter = MinIntervalLimiter(minPullIntervalMs, burst = 1, clock = clock)
    // Сплеск на три: оператор, що ставить дві мітки поспіль на перехресті, —
    // нормальна поведінка, а не зловживання.
    private val pushLimiter = MinIntervalLimiter(minPushIntervalMs, burst = 3, clock = clock)

    override val isConfigured: Boolean get() = config.isConfigured

    override suspend fun push(draft: LiveMarkDraft): GeoResult<Unit> {
        if (!config.isConfigured) return GeoResult.Unavailable(SourceUnavailable.MissingApiKey)
        if (draft.kind !in LiveMarksConfig.ALLOWED_KINDS) {
            return GeoResult.Unavailable(SourceUnavailable.RequestRejected("вид «${draft.kind}» поза схемою"))
        }
        val cell = LiveGeohash.encode(draft.lat, draft.lon)
        if (cell.isEmpty()) {
            return GeoResult.Unavailable(SourceUnavailable.RequestRejected("непридатна точка"))
        }
        val now = clock.nowMs()
        val ttlMs = draft.expiresAtMs - now
        if (ttlMs <= 0L) return GeoResult.Unavailable(SourceUnavailable.RequestRejected("мітка вже мертва"))
        // Дзеркало стелі з RLS. Політика все одно відхилить довшу мітку —
        // краще не витрачати на це запит і не отримати 403 як «сервер ліг».
        val capped = minOf(draft.expiresAtMs, now + MAX_TTL_MS)
        if (!pushLimiter.tryAcquire()) {
            return GeoResult.Unavailable(SourceUnavailable.RateLimited(pushLimiter.retryAfterMs()))
        }
        val body = GeoHttp.json.encodeToString(
            MarkInsertDto.serializer(),
            MarkInsertDto(
                kind = draft.kind,
                lat = draft.lat,
                lon = draft.lon,
                geohash6 = cell,
                expiresAt = Instant.ofEpochMilli(capped).toString(),
                deviceTag = deviceTag,
            ),
        )
        return runCatching {
            val response = http.post(config.restRoot()) {
                authHeaders()
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            when (val code = response.status.value) {
                in 200..299 -> GeoResult.Ok(Unit)
                429 -> GeoResult.Unavailable(SourceUnavailable.RateLimited(DEFAULT_RETRY_AFTER_MS))
                401, 403 -> GeoResult.Unavailable(SourceUnavailable.InvalidApiKey("HTTP $code"))
                else -> GeoResult.Unavailable(SourceUnavailable.HttpError(code))
            }
        }.getOrElse { e ->
            GeoResult.Unavailable(SourceUnavailable.Transport("${e.javaClass.simpleName}: ${e.message ?: "без деталей"}"))
        }
    }

    override suspend fun pull(cells: List<String>, sinceMs: Long?): GeoResult<List<LiveMark>> {
        if (!config.isConfigured) return GeoResult.Unavailable(SourceUnavailable.MissingApiKey)
        val clean = cells.filter { it.isNotBlank() && it.all(::isGeohashChar) }.distinct().take(MAX_CELLS)
        if (clean.isEmpty()) return GeoResult.Unavailable(SourceUnavailable.RequestRejected("порожній регіон"))
        if (!pullLimiter.tryAcquire()) {
            return GeoResult.Unavailable(SourceUnavailable.RateLimited(pullLimiter.retryAfterMs()))
        }
        val query = buildString {
            // `device_tag` навмисно поза select — див. закон приватності вгорі.
            append("?select=id,kind,lat,lon,geohash6,created_at,expires_at")
            // Префікс, не рівність: у колонці geohash6, у запиті — коротший
            // геохеш регіону. `*` — PostgREST-подоба `%` у LIKE.
            if (clean.size == 1) {
                append("&geohash6=like.").append(clean.first()).append('*')
            } else {
                append("&or=(")
                append(clean.joinToString(",") { "geohash6.like.$it*" })
                append(')')
            }
            if (sinceMs != null) {
                append("&created_at=gt.").append(Instant.ofEpochMilli(sinceMs).toString())
            }
            append("&order=created_at.desc&limit=").append(MAX_ROWS)
        }
        val raw = GeoHttp.getText(http, config.restRoot() + query, TAG, retries = 1) { authHeaders() }
        val text = when (raw) {
            is GeoResult.Unavailable ->
                // Порожня таблиця віддає «[]», а не порожнє тіло; але якщо тіло
                // все-таки порожнє — це «міток немає», а не збій джерела.
                return if (raw.reason is SourceUnavailable.EmptyPayload) GeoResult.Ok(emptyList()) else raw
            is GeoResult.Ok -> raw.value
        }
        val rows = runCatching {
            GeoHttp.json.decodeFromString(ListSerializer(MarkRowDto.serializer()), text)
        }.getOrElse {
            return GeoResult.Unavailable(SourceUnavailable.Malformed("рядки міток: ${it.message?.take(120)}"))
        }
        val now = clock.nowMs()
        return GeoResult.Ok(rows.mapNotNull { it.toDomain() }.filter { it.expiresAtMs > now })
    }

    fun close() = http.close()

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        header("apikey", config.anonKey)
        header("Authorization", "Bearer ${config.anonKey}")
        header("User-Agent", GeoHttp.USER_AGENT)
    }

    private fun isGeohashChar(c: Char): Boolean = c.isDigit() || (c in 'a'..'z')

    companion object {
        /** Стеля TTL, та сама, що в RLS. */
        const val MAX_TTL_MS: Long = 2L * 60 * 60 * 1000

        /** Мінімальний проміжок між читаннями регіону. */
        const val DEFAULT_MIN_PULL_INTERVAL_MS: Long = 10_000L

        const val DEFAULT_MIN_PUSH_INTERVAL_MS: Long = 3_000L

        /** Стеля клітинок у запиті — довший URL нічого не додає, лише ризик 414. */
        const val MAX_CELLS: Int = 12

        const val MAX_ROWS: Int = 300

        private const val DEFAULT_RETRY_AFTER_MS = 60_000L
        private const val TAG = "LiveMarksClient"

        /** ISO-8601 з Postgres: «2026-08-22T10:00:00+00:00» або з мікросекундами. */
        internal fun parseEpochMs(raw: String?): Long? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
            runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
            // PostgREST без зони («2026-08-22T10:00:00») — це UTC за визначенням
            // timestamptz, тож добудова зони тут не здогадка.
            return runCatching { Instant.parse(text + "Z").toEpochMilli() }.getOrNull()
        }
    }
}

@Serializable
private data class MarkInsertDto(
    val kind: String,
    val lat: Double,
    val lon: Double,
    val geohash6: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
    @kotlinx.serialization.SerialName("device_tag") val deviceTag: String? = null,
)

// Не private: той самий рядок приходить і опитуванням, і сокетом — дві схеми
// розійшлися б у першому ж полі.
@Serializable
internal data class MarkRowDto(
    val id: String? = null,
    val kind: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val geohash6: String? = null,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String? = null,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String? = null,
) {
    fun toDomain(): LiveMark? {
        val la = lat ?: return null
        val lo = lon ?: return null
        val k = kind?.takeIf { it.isNotBlank() } ?: return null
        val expires = LiveMarksClient.parseEpochMs(expiresAt) ?: return null
        if (!la.isFinite() || !lo.isFinite()) return null
        return LiveMark(
            id = id ?: "$k@$la,$lo",
            kind = k,
            lat = la,
            lon = lo,
            geohash6 = geohash6.orEmpty(),
            createdAtMs = LiveMarksClient.parseEpochMs(createdAt) ?: expires - LiveMarksClient.MAX_TTL_MS,
            expiresAtMs = expires,
        )
    }
}
