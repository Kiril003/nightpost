package local.phantom.companion.core.net.geosrc

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

/**
 * Транспортна обвʼязка, спільна для всіх нових геоджерел.
 *
 * Тіло відповіді свідомо забирається як текст, а не через
 * ContentNegotiation: три з шести джерел віддають CSV або ставлять
 * `text/plain` на цілком валідний JSON, і автоматичне узгодження типів на
 * них або падає, або мовчки віддає порожнечу. Розбір робить кожен клієнт
 * сам — це також єдине місце, де можна відрізнити «битий JSON» від
 * «мережа обірвалась».
 */
internal object GeoHttp {

    private const val TAG = "GeoHttp"

    /**
     * Спільний парсер. `isLenient` потрібен через WAQI, який віддає AQI
     * рядком («52», іноді «-»); `coerceInputValues` рятує від `null` у
     * полях, оголошених непорожніми в чужих схемах.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Загальний User-Agent — USGS і FIRMS просять ідентифікувати клієнта. */
    const val USER_AGENT: String = "PhantomCompanion/0.1 (geo sources)"

    /**
     * Клієнт із таймаутами під повільний або заглушений канал.
     * Тайм-аут запиту навмисне більший за серверний бюджет джерел (≈20 с),
     * інакше сокет здається раніше, ніж джерело встигає відповісти, і це
     * читається оператором як «даних немає».
     */
    fun newClient(engine: HttpClientEngine?, timeoutMs: Long = 25_000L): HttpClient =
        if (engine == null) {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = timeoutMs
                    requestTimeoutMillis = timeoutMs + 5_000
                }
                // Помилковий статус має дійти сюди як значення, а не як виняток:
                // 429 і 403 несуть різний зміст, і глушити їх спільним catch —
                // це втратити різницю між «зачекай» і «ключ мертвий».
                expectSuccess = false
            }
        } else {
            HttpClient(engine) {
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = timeoutMs
                    requestTimeoutMillis = timeoutMs + 5_000
                }
                expectSuccess = false
            }
        }

    /**
     * GET із поверненням тіла як тексту й типізованою відмовою.
     *
     * Повтор робиться лише там, де він має сенс — транспорт і 5xx. На 4xx
     * повтор нічого не змінить, а на 429 він прямо шкідливий: подовжує
     * бан. [retries] — кількість ДОДАТКОВИХ спроб.
     */
    suspend fun getText(
        client: HttpClient,
        url: String,
        tag: String,
        retries: Int = 1,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): GeoResult<String> {
        var last: SourceUnavailable = SourceUnavailable.Transport("жодної спроби не виконано")
        var attempt = 0
        while (attempt <= retries) {
            val outcome = runCatching { readOnce(client, url, configure) }
                .getOrElse { e ->
                    GeoResult.Unavailable(
                        SourceUnavailable.Transport("${e.javaClass.simpleName}: ${e.message ?: "без деталей"}")
                    )
                }
            when (outcome) {
                is GeoResult.Ok -> return outcome
                is GeoResult.Unavailable -> {
                    last = outcome.reason
                    Log.w(TAG, "$tag: ${last.detail}")
                    if (!isRetryable(last) || attempt == retries) return outcome
                }
            }
            attempt++
        }
        return GeoResult.Unavailable(last)
    }

    private suspend fun readOnce(
        client: HttpClient,
        url: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): GeoResult<String> {
        val response: HttpResponse = client.get(url) { configure() }
        val code = response.status.value
        return when {
            code == 429 -> GeoResult.Unavailable(SourceUnavailable.RateLimited(retryAfterMs(response)))
            code == 401 || code == 403 -> GeoResult.Unavailable(SourceUnavailable.InvalidApiKey("HTTP $code"))
            code !in 200..299 -> GeoResult.Unavailable(SourceUnavailable.HttpError(code))
            else -> {
                val text = response.bodyAsText()
                if (text.isBlank()) GeoResult.Unavailable(SourceUnavailable.EmptyPayload)
                else GeoResult.Ok(text)
            }
        }
    }

    /**
     * `Retry-After` у секундах, як його визначає RFC 9110. Форму з датою
     * ці джерела не використовують, тож нечислове значення зводиться до
     * консервативної хвилини — краще зачекати зайве, ніж отримати бан.
     */
    private fun retryAfterMs(response: HttpResponse): Long {
        val header = response.headers["Retry-After"] ?: return DEFAULT_RETRY_AFTER_MS
        val seconds = header.trim().toLongOrNull() ?: return DEFAULT_RETRY_AFTER_MS
        return if (seconds <= 0L) DEFAULT_RETRY_AFTER_MS else seconds * 1_000L
    }

    private fun isRetryable(reason: SourceUnavailable): Boolean = when (reason) {
        is SourceUnavailable.Transport -> true
        is SourceUnavailable.HttpError -> reason.code >= 500
        else -> false
    }

    private const val DEFAULT_RETRY_AFTER_MS = 60_000L
}

/**
 * Розбирає текст у [T] спільним парсером, зводячи будь-який збій розбору
 * до [SourceUnavailable.Malformed]. Виняток серіалізації назовні — це
 * падіння екрана через чужу зміну схеми.
 */
internal inline fun <reified T> decodeOrMalformed(text: String): GeoResult<T> =
    try {
        GeoResult.Ok(GeoHttp.json.decodeFromString<T>(text))
    } catch (e: Exception) {
        GeoResult.Unavailable(
            SourceUnavailable.Malformed("${e.javaClass.simpleName}: ${e.message?.take(160) ?: "без деталей"}")
        )
    }
