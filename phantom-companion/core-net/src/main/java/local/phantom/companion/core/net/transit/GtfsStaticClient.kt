package local.phantom.companion.core.net.transit

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.core.isEmpty
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * ATLAS-3 — забирає ОПУБЛІКОВАНИЙ статичний GTFS-архів міста.
 *
 * Окремий клієнт від [GtfsRealtimeClient] навмисно: там протобуф на
 * десятки кілобайт кожні кілька секунд, тут — zip на кілька мегабайт раз
 * на добу. Спільний клієнт мав би або таймаут позицій (і рвав би архів на
 * половині), або таймаут архіву (і тримав би мертві позиції хвилину).
 *
 * ЧОМУ РЕЗУЛЬТАТ ТИПІЗОВАНИЙ, А НЕ `ByteArray?`: споживач мусить уміти
 * сказати людині, ЧОМУ розкладу нема. `null` не відрізняє «мережі нема»
 * від «міста нема в реєстрі» від «сервер віддав 500», а на екрані це три
 * різні речення. Закон 3: мовчання з причиною, ніколи вигаданий час.
 */
class GtfsStaticClient(
    private val userAgent: String = "PhantomCompanion/0.1 (transit GTFS static)",
    /**
     * Стеля тіла. Київський архів — 1,2 МБ, львівський — 3,8 МБ (виміряно
     * 13.09.2026). 48 МБ лишає запас на порядок і все одно не дає
     * зіпсованому/ворожому джерелу зʼїсти памʼять телефона: читаємо
     * потоком і рвемо на межі, а не після `readBytes()` на весь відповідь.
     */
    private val maxBytes: Int = 48 * 1024 * 1024,
    private val engine: HttpClient = HttpClient(OkHttp),
) {

    sealed interface Result {
        /** Архів у руках. [etag] — для умовного запиту наступного разу. */
        data class Ok(val bytes: ByteArray, val etag: String?) : Result

        /** Сервер сказав «не змінилось» — чинний кеш лишається чинним. */
        data object NotModified : Result

        /** Не вийшло, і ось чому — рядок іде людині на екран. */
        data class Failed(val reason: String) : Result
    }

    suspend fun fetch(url: String, ifNoneMatch: String? = null): Result = withContext(Dispatchers.IO) {
        runCatching {
            val response: HttpResponse = engine.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/zip")
                ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
            when {
                response.status.value == 304 -> Result.NotModified
                !response.status.isSuccess() ->
                    Result.Failed("джерело відповіло ${response.status.value}")

                else -> {
                    val body = readCapped(response)
                        ?: return@runCatching Result.Failed("архів більший за ${maxBytes / (1024 * 1024)} МБ")
                    // Порожнє тіло з кодом 200 трапляється, коли міський
                    // сервер перегенеровує архів: краще лишити вчорашній
                    // кеш, ніж збудувати розклад із нуля рейсів.
                    if (body.size < MIN_PLAUSIBLE_ZIP) {
                        Result.Failed("архів порожній (${body.size} Б)")
                    } else {
                        Result.Ok(body, response.headers[HttpHeaders.ETag])
                    }
                }
            }
        }.getOrElse { Result.Failed(it.message ?: it::class.java.simpleName) }
    }

    /** Читає потоком і зупиняється на [maxBytes]; null — джерело перебрало. */
    private suspend fun readCapped(response: HttpResponse): ByteArray? {
        val channel = response.bodyAsChannel()
        val sink = ByteArrayOutputStream(DEFAULT_SINK)
        while (true) {
            val packet = channel.readRemaining(CHUNK.toLong())
            if (packet.isEmpty) break
            val chunk = packet.readBytes()
            if (sink.size() + chunk.size > maxBytes) return null
            sink.write(chunk)
        }
        return sink.toByteArray()
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val DEFAULT_SINK = 1 * 1024 * 1024

        /** Менше за це не буває навіть у zip з одним порожнім записом. */
        const val MIN_PLAUSIBLE_ZIP = 512
    }
}
