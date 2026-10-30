package local.phantom.companion.core.net.peer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Тіло публікації. Поля `handle` тут немає й не буде: доки воно було, сервер
 * діставав кожне опубліковане імʼя відкритим текстом, хоч пошук уже йшов
 * хешем. Сторож — у `PeerDirectoryHttpTest`, над СЕРІАЛІЗОВАНИМ тілом, а не
 * над цим оголошенням.
 */
@Serializable
private data class PublishBody(
    @SerialName("handle_hash") val handleHash: String,
    val invite: String,
    val exp: Long,
    val sig: String,
)

@Serializable
private data class WithdrawBody(
    @SerialName("handle_hash") val handleHash: String,
    val exp: Long,
    val sig: String,
)

@Serializable
private data class PublishedDto(
    @SerialName("handle_hash") val handleHash: String = "",
    val exp: Long = 0L,
)

@Serializable
private data class FoundDto(
    val invite: String = "",
    val sig: String = "",
    val exp: Long = 0L,
)

@Serializable
private data class DetailDto(val detail: String = "")

/**
 * Довідник по HTTPS — три ручки на тому самому сервері, що й ліцензії.
 *
 * Ані ключа ліцензії, ані куки, ані заголовка авторизації, з тієї ж причини,
 * що й у [PeerRelayClient]: пошук за іменем безкоштовний, а якби він носив
 * облікові дані, сервер знав би, ХТО кого шукає — тобто складав би саме той
 * граф знайомств, якого в протоколі немає.
 *
 * Імені звідси не їде НІКУДИ: ні в пошуку (адреса в шляху — sha256), ні в
 * публікації, ні у відклику (поле `handle_hash`, і підпис накриває той самий
 * хеш). Імʼя лишається на апараті — це і є вся обіцянка довідника, і до v2
 * вона була неправдою рівно в один бік: публікація везла `handle` рядком.
 *
 * Тільки `https://`, і це не сумісність із загальним правилом. Хеш короткого
 * імені перебирається за словником, а поруч із ним у тому самому запиті їде
 * ключ, до якого це імʼя веде: хто чує обидва, той зшиває одне з одним, і вся
 * розмова про приховані адреси нічого не варта. Тому [open] на `http://`
 * повертає null, а не «спробуємо».
 */
class PeerDirectoryClient private constructor(
    private val baseUrl: String,
    http: HttpClient?,
    timeoutMs: Long,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: HttpClient = http ?: HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
        }
        expectSuccess = false
    }

    /**
     * Три відповіді, не дві. «Такого імені немає» каже сервер, і це факт;
     * «не достукались» не каже ніхто, і подавати його як «нікого немає» —
     * це збрехати людині про іншу людину.
     */
    sealed interface Lookup {
        data class Found(val record: PeerDirectory.Record) : Lookup
        data object None : Lookup

        /** Сервер живий, довідник на ньому ще не відкрито — і він каже, чому. */
        data class Closed(val detail: String) : Lookup

        data object Unreachable : Lookup
    }

    sealed interface Outcome {
        data class Published(val handle: String, val untilSeconds: Long) : Outcome
        data object Withdrawn : Outcome

        /** Імʼя вже за кимось іншим. */
        data object Taken : Outcome

        /** Сервер відповів і відмовив — причини він не називає. */
        data object Refused : Outcome

        data class Closed(val detail: String) : Outcome

        data object Unreachable : Outcome
    }

    suspend fun lookup(handle: String): Lookup {
        val name = PeerDirectory.normalise(handle) ?: return Lookup.None
        return runCatching {
            val response = client.get("$baseUrl/directory/lookup/${PeerDirectory.handleHash(name)}")
            when (response.status) {
                HttpStatusCode.OK -> {
                    val dto: FoundDto = response.body()
                    if (dto.invite.isBlank() || dto.sig.isBlank()) {
                        Lookup.None
                    } else {
                        Lookup.Found(
                            PeerDirectory.Record(
                                handle = name,
                                invite = dto.invite,
                                sig = dto.sig,
                                expSeconds = dto.exp,
                            ),
                        )
                    }
                }
                HttpStatusCode.NotFound -> Lookup.None
                // Двері `DIRECTORY_OPEN` на сервері зачинені: він живий і сам
                // каже, чому мовчить. Його слово важливіше за наше «нікого» —
                // це різні речі, і власник має чути саме серверове.
                HttpStatusCode.ServiceUnavailable ->
                    detailOf(response)?.let { Lookup.Closed(it) } ?: Lookup.Unreachable
                else -> Lookup.Unreachable
            }
        }.getOrDefault(Lookup.Unreachable)
    }

    suspend fun publish(
        handle: String,
        invite: PeerInvite.Invite,
        privateKey: ByteArray,
        nowMs: Long,
    ): Outcome {
        val name = PeerDirectory.normaliseForPublish(handle) ?: return Outcome.Refused
        val code = PeerInvite.encode(PeerDirectory.addressless(invite))
        val exp = nowMs / 1_000L + PeerDirectory.MAX_TTL_SECONDS
        // Адреса рахується ОДИН раз і той самий рядок іде і в підпис, і в тіло.
        // Саме розбіжність цих двох була вадою v1: підписувалось імʼя, і воно ж
        // їхало на сервер.
        val digest = PeerDirectory.handleHash(name)
        val sig = PeerDirectory.sign(PeerDirectory.publishPayload(digest, code, exp), privateKey)
        return runCatching {
            val response = client.post("$baseUrl/directory/publish") {
                contentType(ContentType.Application.Json)
                setBody(PublishBody(handleHash = digest, invite = code, exp = exp, sig = sig))
            }
            when (response.status) {
                HttpStatusCode.OK, HttpStatusCode.Created -> {
                    val dto = runCatching { response.body<PublishedDto>() }.getOrNull()
                    Outcome.Published(name, dto?.exp?.takeIf { it > 0L } ?: exp)
                }
                HttpStatusCode.Conflict -> Outcome.Taken
                else -> refusalOrSilence(response)
            }
        }.getOrDefault(Outcome.Unreachable)
    }

    suspend fun withdraw(handle: String, privateKey: ByteArray, nowMs: Long): Outcome {
        val name = PeerDirectory.normalise(handle) ?: return Outcome.Refused
        val exp = nowMs / 1_000L + PeerDirectory.MAX_TTL_SECONDS
        val digest = PeerDirectory.handleHash(name)
        val sig = PeerDirectory.sign(PeerDirectory.withdrawPayload(digest, exp), privateKey)
        return runCatching {
            val response = client.delete("$baseUrl/directory/publish") {
                contentType(ContentType.Application.Json)
                setBody(WithdrawBody(handleHash = digest, exp = exp, sig = sig))
            }
            when (response.status) {
                HttpStatusCode.OK, HttpStatusCode.NoContent -> Outcome.Withdrawn
                // Знятого імені на сервері вже немає — для власника це те саме,
                // що зняли зараз, і другий дотик не має червоніти.
                HttpStatusCode.NotFound -> Outcome.Withdrawn
                else -> refusalOrSilence(response)
            }
        }.getOrDefault(Outcome.Unreachable)
    }

    // Довідника на цьому сервері ще може не бути (не викочений), двері можуть
    // бути зачинені, або лежить шлюз. Жодне з цього не присуд імені: назвати
    // це відмовою означало б послати власника міняти нормальне імʼя.
    private suspend fun refusalOrSilence(response: HttpResponse): Outcome = when (response.status) {
        // Порядок важить: 503 з поясненням мусить лишитись поясненням, а не
        // впасти в німе «не достукались» разом із рештою.
        HttpStatusCode.ServiceUnavailable ->
            detailOf(response)?.let { Outcome.Closed(it) } ?: Outcome.Unreachable
        HttpStatusCode.NotFound,
        HttpStatusCode.NotImplemented,
        HttpStatusCode.BadGateway,
        HttpStatusCode.GatewayTimeout,
        -> Outcome.Unreachable
        else -> Outcome.Refused
    }

    /** Слово сервера про себе, або null — коли він його не сказав. */
    private suspend fun detailOf(response: HttpResponse): String? =
        runCatching { response.body<DetailDto>().detail }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    companion object {

        // Коротше за сховок (12 с) навмисно: там за листа чекає насос у тлі,
        // а тут людина стоїть над полем пошуку й тримає екран.
        const val DEFAULT_TIMEOUT_MS = 8_000L

        private const val HTTPS = "https://"

        /** null — цей телефон у бік довідника не робить нічого взагалі. */
        fun open(
            baseUrl: String,
            http: HttpClient? = null,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): PeerDirectoryClient? {
            val url = baseUrl.trim().trimEnd('/')
            if (!url.startsWith(HTTPS) || url.length <= HTTPS.length) return null
            return PeerDirectoryClient(url, http, timeoutMs)
        }
    }
}
