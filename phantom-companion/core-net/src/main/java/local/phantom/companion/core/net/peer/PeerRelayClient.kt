package local.phantom.companion.core.net.peer

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.crypto.b64ToBytes
import local.phantom.companion.core.net.crypto.bytesToB64

/**
 * Три дії, які телефон уміє зі сховком. Окремо від [PeerRelayClient], бо насос
 * має бути перевірюваним без жодного сокета — тут же й лежить уся логіка, від
 * якої залежить, побачить людина «У СХОВКУ» чи «ЧЕКАЄ».
 */
interface PeerRelayTransport {

    /** Що сервер відповів на вкидання. Розрізняється, бо три випадки коштують різного. */
    enum class Drop {
        /** Сервер тримає. Телефон більше не платить радіо за цей лист. */
        HELD,

        /** Скринька або сховок повні: лист чесно лишається на телефоні. Це «зачекай», не присуд. */
        FULL,

        /** 429: забагато запитів. Теж «зачекай» — спроба листа тут ні до чого. */
        BUSY,

        /** Відмова, яку ми не вміємо назвати. Рахується як звичайна невдала спроба. */
        REFUSED,

        /** Сховка просто немає на звʼязку — це не відмова, це відсутність. */
        NO_CONTACT,
    }

    class Blob(val id: String, val tag: String, val bytes: ByteArray)

    /**
     * Що сховок відповів на вкидання.
     *
     * [heldUntilMs] — строк, названий СЕРВЕРОМ, за його годинником. Саме він, а
     * не наш власний `зараз + TTL`, лягає в облік листа: за ним потім звіряють
     * народження бази сховка ([Pickup.storeSinceMs]), а два різні годинники в
     * одному порівнянні дали б хибне «лист стерто» на кожному телефоні, який
     * трохи поспішає. null — сервер строку не назвав (старий сховок або будь-яка
     * відповідь, крім [Drop.HELD]).
     */
    class Dropped(
        val verdict: Drop,
        val heldUntilMs: Long? = null,
        /** Для [Drop.FULL] і [Drop.BUSY]: скільки сервер просить почекати. */
        val retryAfterMs: Long? = null,
    )

    /**
     * Що сховок віддав за один захід.
     *
     * [storeSinceMs] — мить, коли народилась ЦЯ база сховка (годинник сервера).
     * Єдине, чим відправник відрізняє «твій лист забрали» від «сховок стерто й
     * почато заново»: порожня видача сама по собі не означає ні того, ні того.
     * null — сервер про це не каже, і тоді витирання невідрізниме від тиші.
     */
    class Pickup(val blobs: List<Blob>, val storeSinceMs: Long? = null)

    suspend fun drop(tag: String, blob: ByteArray): Dropped

    /** null — до сервера не достукались; порожні [Pickup.blobs] — скриньки порожні. */
    suspend fun fetch(tags: List<String>): Pickup?

    suspend fun burn(ids: List<String>): Boolean

    /**
     * Багато вкидань одним рухом. Типово — по одному: так поводиться сховок v1
     * і будь-який фейк у тестах. Сховок v2 робить із цього ОДИН запит.
     */
    suspend fun dropMany(items: List<Pair<String, ByteArray>>): List<Dropped> =
        items.map { drop(it.first, it.second) }

    /** Живий канал до цього ж сховка. null — його немає (старий сервер, фейк). */
    fun openLive(events: PeerRelayLive.Events): PeerRelayLive? = null

    /** Сервер оголосив вкидання по живому сокету ([PeerRelayLive.drop]). */
    val liveDrops: Boolean get() = false

    /** Сервер оголосив кошик [PeerRelay.SMALL_BLOB_SIZE]. */
    val smallBlobs: Boolean get() = false

    /** Забути «старий сервер» і спитати здоровʼя наново. */
    suspend fun probe() {}

    /** Що відомо про дорогу до сховка. null — транспорт не міряє (фейк, старий клієнт). */
    val meter: PeerLinkMeter? get() = null

    /** Ці копії вже лягли повз [drop] (живим сокетом); транспорт з другим сховком шле їх і туди. */
    fun mirror(items: List<Pair<String, ByteArray>>) {}

    /** Забирати не рідше, ніж раз на стільки, навіть коли живий канал бачить усе. null — як завжди. */
    val pollEveryMs: Long? get() = null

    /** Скільки чекати відповіді на забір за поточною дорогою. null — транспорт не міряє. */
    val fetchWaitMs: Long? get() = null
}

@Serializable
internal data class DropBody(val tag: String, val blob: String)

@Serializable
private data class FetchBody(val tags: List<String>)

@Serializable
private data class BurnBody(val ids: List<String>)

@Serializable
internal data class HeldDto(val id: String, val tag: String, val blob: String)

@Serializable
internal data class BatchBody(
    val drops: List<DropBody> = emptyList(),
    val burn: List<String> = emptyList(),
    val fetch: List<String>? = null,
)

@Serializable
internal data class BatchDropped(
    val status: String,
    @SerialName("held_until_ms") val heldUntilMs: Long? = null,
    @SerialName("retry_after_s") val retryAfterS: Long? = null,
)

@Serializable
internal data class BatchReply(
    val dropped: List<BatchDropped> = emptyList(),
    val burned: Int = 0,
    val blobs: List<HeldDto>? = null,
    @SerialName("store_since_ms") val storeSinceMs: Long? = null,
)

@Serializable
internal data class HealthReply(
    val ok: Boolean = false,
    @SerialName("blob_sizes") val blobSizes: List<Int> = emptyList(),
    val live: Int = 0,
    @SerialName("legacy_relay") val legacyRelay: Boolean? = null,
)

@Serializable
private data class DropReply(@SerialName("held_until_ms") val heldUntilMs: Long? = null)

@Serializable
private data class FetchReply(
    val blobs: List<HeldDto> = emptyList(),
    @SerialName("store_since_ms") val storeSinceMs: Long? = null,
)

/**
 * Сховок по HTTPS.
 *
 * Жодного заголовка авторизації, жодного ключа ліцензії, жодної куки — і це не
 * недогляд. Чат безкоштовний, тож підставляти сюди облікові дані не було б за
 * що; а якби вони тут були, сервер знав би, ЧИЙ це трафік, і вся решта роботи
 * з приховування адрес нічого не варта.
 *
 * Скласти це поза модулем не можна: єдиний шлях сюди — [PeerRelayGate], і саме
 * тому прапорець «сховок вимкнено» не обійти неуважністю.
 */
class PeerRelayClient internal constructor(
    private val baseUrl: String,
    http: HttpClient? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    override val meter: PeerLinkMeter = PeerLinkMeter(),
    private val connectCapMs: Long = Long.MAX_VALUE,
) : PeerRelayTransport {

    private val json = Json { ignoreUnknownKeys = true }

    // Строки на кожен запит ставляться лише своєму клієнту: чужому (тестовому)
    // без HttpTimeout вони були б помилкою, а не строком.
    private val adaptive = http == null

    private val client: HttpClient = http ?: HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = minOf(timeoutMs, connectCapMs)
        }
        engine { config { dns(PeerStickyDns.shared) } }
        expectSuccess = false
    }

    /**
     * 12 с не вистачає на 2G: DNS, TCP, TLS і запит — пʼять обертань по 1–2 с,
     * плюс ефір тіла. Строк росте з виміряним RTO і розміром, і ніколи не
     * коротший за старий.
     */
    private fun HttpRequestBuilder.paced(bytes: Int) {
        if (!adaptive) return
        val wait = waitFor(bytes)
        timeout {
            requestTimeoutMillis = wait
            connectTimeoutMillis = minOf(connectCapMs, maxOf(timeoutMs, 4 * meter.rtoMs))
        }
    }

    private fun waitFor(bytes: Int): Long =
        (REQUEST_RTTS * meter.rtoMs + bytes * 1000L / PeerLinkMeter.SLOW_BYTES_PER_S)
            .coerceIn(timeoutMs, PeerLinkMeter.MAX_WAIT_MS)

    override val fetchWaitMs: Long get() = if (adaptive) waitFor(FETCH_REPLY_BYTES) else timeoutMs

    /** Відповідь будь-яка — дорога жива; виняток — провал дороги. */
    private suspend fun <T> metered(block: suspend () -> T): T {
        val value = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            meter.fail()
            throw e
        }
        meter.ok()
        return value
    }

    /**
     * Чи вміє сервер v2. null — ще не питали. 404/405 на `/relay/v2/...` означає
     * старий сервер: тоді ходимо v1, доки [probe] (зміна мережі, повернення
     * людини) не спитає знову — сервер за цей час міг оновитись.
     */
    @Volatile private var v2: Boolean? = null

    @Volatile override var liveDrops: Boolean = false
        private set

    @Volatile override var smallBlobs: Boolean = false
        private set

    /** Вимикач старого сховка з цього сервера. null — сервер про нього не каже. */
    @Volatile var legacyRelay: Boolean? = null
        private set

    override suspend fun probe() {
        v2 = null
        val health = runCatching {
            val response = metered { client.get("$baseUrl/relay/v2/health") { paced(0) } }
            if (response.status == HttpStatusCode.OK) json.decodeFromString<HealthReply>(response.bodyAsText()) else null
        }.getOrNull()
        if (health?.ok == true) v2 = true
        if (health?.ok == true) legacyRelay = health.legacyRelay
        liveDrops = health?.ok == true && health.live >= LIVE_DROPS
        smallBlobs = health?.ok == true && PeerRelay.SMALL_BLOB_SIZE in health.blobSizes
    }

    private class Answer(val status: HttpStatusCode, val reply: BatchReply?, val retryAfterMs: Long?)

    /** Один пакет v2. null — v2 немає на сервері або до сервера не дійшли . */
    private suspend fun batch(body: BatchBody): Answer? {
        if (v2 == false) return null
        val bytes = body.drops.sumOf { it.blob.length + 64 } +
            (body.fetch?.let { FETCH_REPLY_BYTES } ?: 0)
        val response = metered {
            client.post("$baseUrl/relay/v2/batch") {
                contentType(ContentType.Application.Json)
                setBody(body)
                paced(bytes)
            }
        }
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed) {
            v2 = false
            return null
        }
        v2 = true
        val reply = if (response.status == HttpStatusCode.OK) {
            runCatching { response.body<BatchReply>() }.getOrNull()
        } else {
            null
        }
        return Answer(response.status, reply, retryAfterOf(response.headers[HttpHeaders.RetryAfter]))
    }

    override suspend fun drop(tag: String, blob: ByteArray): PeerRelayTransport.Dropped =
        dropMany(listOf(tag to blob)).single()

    override suspend fun dropMany(
        items: List<Pair<String, ByteArray>>,
    ): List<PeerRelayTransport.Dropped> {
        if (items.isEmpty()) return emptyList()
        // Довжина, якої сервер не прийме, не має витрачати ані радіо, ані спроби
        // листа: вкидати можна лише те, що зливається з рештою.
        val fit = items.map { (tag, blob) -> PeerRelay.isTag(tag) && PeerRelay.isBlobSize(blob.size) }
        val out = MutableList(items.size) { refused }
        val send = items.indices.filter { fit[it] }
        for (chunk in send.chunked(BATCH_DROPS)) {
            val verdicts = runCatching { dropChunk(chunk.map { items[it] }) }
                .getOrElse { List(chunk.size) { noContact } }
            chunk.forEachIndexed { i, index -> out[index] = verdicts[i] }
        }
        return out
    }

    private suspend fun dropChunk(items: List<Pair<String, ByteArray>>): List<PeerRelayTransport.Dropped> {
        val answer = batch(BatchBody(drops = items.map { DropBody(it.first, bytesToB64(it.second)) }))
            ?: return items.map { dropV1(it.first, it.second) }
        val reply = answer.reply ?: return List(items.size) { verdictOf(answer.status, answer.retryAfterMs) }
        return items.indices.map { i ->
            val one = reply.dropped.getOrNull(i) ?: return@map noContact
            when (one.status) {
                "held" -> PeerRelayTransport.Dropped(PeerRelayTransport.Drop.HELD, one.heldUntilMs)
                "full" -> PeerRelayTransport.Dropped(
                    PeerRelayTransport.Drop.FULL,
                    retryAfterMs = (one.retryAfterS ?: DEFAULT_RETRY_S) * 1000L,
                )
                else -> refused
            }
        }
    }

    private suspend fun dropV1(tag: String, blob: ByteArray): PeerRelayTransport.Dropped {
        val response = metered {
            client.post("$baseUrl/relay/drop") {
                contentType(ContentType.Application.Json)
                setBody(DropBody(tag, bytesToB64(blob)))
                paced(blob.size * 4 / 3)
            }
        }
        return if (response.status == HttpStatusCode.OK) {
            // Строк беремо в сервера. Старий сховок його не називає — тоді
            // null, і облік листа лишається на нашому годиннику, як було.
            PeerRelayTransport.Dropped(
                PeerRelayTransport.Drop.HELD,
                runCatching { response.body<DropReply>().heldUntilMs }.getOrNull(),
            )
        } else {
            verdictOf(response.status, retryAfterOf(response.headers[HttpHeaders.RetryAfter]))
        }
    }

    private fun verdictOf(status: HttpStatusCode, retryAfterMs: Long?): PeerRelayTransport.Dropped =
        when (status) {
            HttpStatusCode.Conflict,
            HttpStatusCode.InsufficientStorage,
            -> PeerRelayTransport.Dropped(
                PeerRelayTransport.Drop.FULL,
                retryAfterMs = retryAfterMs ?: (DEFAULT_RETRY_S * 1000L),
            )
            // «Забагато запитів» — правило сервера, а не вада листа.
            HttpStatusCode.TooManyRequests -> PeerRelayTransport.Dropped(
                PeerRelayTransport.Drop.BUSY,
                retryAfterMs = retryAfterMs ?: (DEFAULT_RETRY_S * 1000L),
            )
            // Сервер відповів, але сховка на ньому немає (ще не викладений)
            // або шлюз лежить. Це відсутність, не присуд листу: REFUSED
            // палив би спробу, і вісім таких відповідей поспіль убили б
            // лист за те, чого він не робив.
            HttpStatusCode.NotFound,
            HttpStatusCode.NotImplemented,
            HttpStatusCode.BadGateway,
            HttpStatusCode.ServiceUnavailable,
            HttpStatusCode.GatewayTimeout,
            -> noContact
            else -> refused
        }

    override suspend fun fetch(tags: List<String>): PeerRelayTransport.Pickup? {
        // Коротший запит звузив би гурт, у якому ховається справжня скринька.
        if (tags.size != PeerRelay.FETCH_TAGS) return null
        if (tags.any { !PeerRelay.isTag(it) }) return null
        return runCatching {
            val answer = batch(BatchBody(fetch = tags))
            val reply: FetchReply = if (answer != null) {
                val got = answer.reply ?: return@runCatching null
                FetchReply(got.blobs.orEmpty(), got.storeSinceMs)
            } else {
                val response = client.post("$baseUrl/relay/fetch") {
                    contentType(ContentType.Application.Json)
                    setBody(FetchBody(tags))
                }
                if (response.status != HttpStatusCode.OK) return@runCatching null
                response.body()
            }
            PeerRelayTransport.Pickup(
                reply.blobs.mapNotNull { held ->
                    val bytes = runCatching { b64ToBytes(held.blob) }.getOrNull()
                        ?: return@mapNotNull null
                    PeerRelayTransport.Blob(held.id, held.tag, bytes)
                },
                reply.storeSinceMs,
            )
        }.getOrNull()
    }

    override suspend fun burn(ids: List<String>): Boolean {
        if (ids.isEmpty() || ids.size > PeerRelay.FETCH_TAGS) return false
        return runCatching {
            val answer = batch(BatchBody(burn = ids))
            if (answer != null) return@runCatching answer.status == HttpStatusCode.OK
            client.post("$baseUrl/relay/burn") {
                contentType(ContentType.Application.Json)
                setBody(BurnBody(ids))
            }.status == HttpStatusCode.OK
        }.getOrDefault(false)
    }

    override fun openLive(events: PeerRelayLive.Events): PeerRelayLive? {
        // Старий сервер живого каналу не має; питати його щоразу — це зайвий
        // запит на кожне перепідключення.
        if (v2 == false) return null
        return PeerRelayLive(PeerRelayLive.urlFor(baseUrl), events, meter = meter)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
        const val REQUEST_RTTS = 6
        const val FETCH_REPLY_BYTES = 16 * 1024
        const val DEFAULT_RETRY_S = 60L
        const val BATCH_DROPS = 32
        const val LIVE_DROPS = 2

        /** `Retry-After` у секундах; дату (друга форма заголовка) сервер не шле. */
        fun retryAfterOf(raw: String?): Long? =
            raw?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1000L }

        val refused = PeerRelayTransport.Dropped(PeerRelayTransport.Drop.REFUSED)
        val noContact = PeerRelayTransport.Dropped(PeerRelayTransport.Drop.NO_CONTACT)
    }
}
