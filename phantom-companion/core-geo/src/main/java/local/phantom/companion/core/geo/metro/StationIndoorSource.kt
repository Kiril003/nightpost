package local.phantom.companion.core.geo.metro

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.map.OverpassBudget
import local.phantom.companion.core.net.map.OverpassElement
import local.phantom.companion.core.net.map.OverpassMirrors
import local.phantom.companion.core.net.map.OverpassResponse

/**
 * Звідки беруться сирі елементи OSM для графа станції.
 *
 * Порт, а не клас, з однієї причини: різниця між «мережа не відповіла» і
 * «мережа відповіла, але станції в OSM немає» — це різні відповіді КОРИСТУВАЧУ,
 * і плутати їх не можна. Тому контракт тризначний:
 *
 *  - `null` — жодне дзеркало не відповіло. Ми НЕ знаємо, що там є. Казати
 *    «схеми станції немає» в цій ситуації означало б збрехати про світ, а не
 *    про мережу.
 *  - порожній список — Overpass відповів, у радіусі нічого підземного немає.
 *  - непорожній список — є з чого будувати граф.
 *
 * Окремий тип ще й тому, що він робить [MetroStationLayer] тестованим без
 * мережі: у тестах підставляється фікстура з реальною формою відповіді.
 */
internal fun interface StationIndoorSource {

    /**
     * Виконати готовий Overpass QL.
     *
     * @return `null` — мережа недоступна; інакше елементи відповіді.
     */
    suspend fun fetch(query: String): List<OverpassElement>?
}

/**
 * Реальне джерело: Overpass через дзеркала.
 *
 * Свій маленький клієнт, а не `core-net.OverpassClient`, тому що той не має
 * публічного методу «виконай ось цей QL» — у нього кожен запит зашитий
 * окремою функцією, а `runQuery` приватний. Дублювати сюди ще один
 * fetch-метод у чужому модулі було б гіршим рішенням, ніж тримати тут
 * тридцять рядків, які роблять рівно одне.
 *
 * Дзеркала беруться з `OverpassMirrors` у `:core-net` — не копія списку, а
 * той самий список: один ендпойнт означає одну точку відмови, а на
 * заглушеному або DNS-фільтрованому каналі оператора «overpass-api.de не
 * відповів» дорівнює «карта порожня». Спільний і бюджет запитів, тож
 * запит схеми станції не зʼїдає квоту в обхід решти мапи.
 */
internal class OverpassStationSource(
    private val clientProvider: () -> HttpClient = { defaultClient },
    private val endpoints: List<String> = MIRRORS,
) : StationIndoorSource {

    override suspend fun fetch(query: String): List<OverpassElement>? {
        // Перенос рядка в URL-параметрі частина дзеркал відкидає, частина
        // приймає — саме звідси береться «у мене працює, у нього ні».
        val data = query.replace('\n', ' ')
        val client = clientProvider()
        for (base in endpoints) {
            val refusal = OverpassBudget.shared.acquire(base)
            if (refusal != null) {
                Log.w(TAG, "indoor overpass ${host(base)} пропущено: ${refusal.detail}")
                continue
            }
            val res = try {
                client.get(base) { parameter("data", data) }.body<OverpassResponse>().elements
            } catch (ce: CancellationException) {
                // Скасування — не збій мережі: воно має піднятись до того, хто
                // скасував, інакше корутина «успішно» повернула б порожнечу.
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "indoor overpass ${host(base)} failed: ${t.javaClass.simpleName} ${t.message}")
                null
            }
            if (res != null) {
                Log.i(TAG, "indoor overpass ${host(base)} -> ${res.size} elements")
                return res
            }
        }
        Log.w(TAG, "indoor overpass: усі ${endpoints.size} дзеркал мовчать")
        return null
    }

    private fun host(url: String): String = url.substringAfter("://").substringBefore("/")

    internal companion object {
        private const val TAG = "IndoorOverpass"

        val MIRRORS: List<String> = OverpassMirrors.ENDPOINTS

        /**
         * Один клієнт на процес. Ліниво, бо інструмент створюється при збірці
         * контейнера застосунку, а мережа йому потрібна лише коли оператор
         * реально спитає про станцію.
         *
         * Таймаути більші за серверний бюджет запиту (25 с): сокет, який
         * здається раніше за сервер, — це гарантований «нічого не знайшов» на
         * повільному роумінгу.
         */
        private val defaultClient: HttpClient by lazy {
            HttpClient(OkHttp) {
                install(UserAgent) { agent = OverpassMirrors.USER_AGENT }
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 30_000
                    requestTimeoutMillis = 35_000
                }
            }
        }
    }
}
