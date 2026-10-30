package local.phantom.companion.net

import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.BuildConfig
import local.phantom.companion.data.PairedDevice
import local.phantom.companion.data.PairedDeviceStore

/**
 * Thin REST surface against PHANTOM OS.
 *
 *   POST /api/v1/pair/claim          (no auth)
 *   POST /api/v1/pair/refresh        (no auth, body-signed)
 *   POST /api/v1/sensors/mobile_batch (device JWT)
 *   PATCH /api/v1/auth/me            (device JWT)
 *
 * Ktor + OkHttp gives us connection pooling and a stable WS engine reused by
 * [PhantomLink]. One `HttpClient` is shared across REST and WS so OkHttp's
 * pool isn't fragmented.
 */
class PhantomApi(
    private val store: PairedDeviceStore,
    val address: NodeAddress = NodeAddress(store),
) {

    val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Строк токена й повтор після 401. Тут, бо [refresh] живе тут же. */
    val auth: NodeAuth by lazy { NodeAuth(this, store) }

    val client: HttpClient = HttpClient(OkHttp) {
        engine {
            // OkHttp's connection pool is shared across requests. WS reuses
            // the same engine so we don't pay TLS handshake again.
            preconfigured = local.phantom.companion.core.net.pair.PinnedTrust
                .apply(okhttp3.OkHttpClient.Builder()) { store.load()?.serverCertSha256 ?: "" }
                .build()
        }
        install(ContentNegotiation) { json(this@PhantomApi.json) }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
        install(HttpRequestRetry) {
            retryOnExceptionOrServerErrors(maxRetries = 2)
            exponentialDelay()
        }
        install(Logging) {
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }
    }

    @Serializable
    data class ClaimRequest(
        val pair_id: String,
        val client_pub: String,
        val device_pub_ed25519: String,
        val nonce_echo: String,
        val client_proof: String,
        val device: Map<String, String>,
    )

    @Serializable
    data class ClaimResponse(
        val device_jwt: String,
        val device_id: String,
        val expires_at: String,
        val server_proof: String,
        val user: User,
    )

    @Serializable data class User(val id: String, val username: String, val role: String)

    @Serializable
    data class RefreshRequest(val old_token: String, val nonce_b64: String, val signature_b64: String)

    @Serializable data class RefreshResponse(val device_jwt: String, val expires_at: String)

    suspend fun claim(baseUrl: String, body: ClaimRequest): ClaimResponse {
        val res = client.post("$baseUrl/api/v1/pair/claim") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = res.bodyAsText()
        if (res.status != HttpStatusCode.OK) {
            throw PhantomApiException("claim failed: ${res.status} ${text.take(200)}")
        }
        return runCatching {
            json.decodeFromString(ClaimResponse.serializer(), text)
        }.getOrElse { t ->
            throw PhantomApiException("Malformed claim response: ${t.message}. Raw: ${text.take(100)}")
        }
    }

    suspend fun refresh(baseUrl: String, body: RefreshRequest): RefreshResponse {
        val res = client.post("$baseUrl/api/v1/pair/refresh") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = res.bodyAsText()
        if (res.status != HttpStatusCode.OK) {
            throw PhantomApiException("refresh failed: ${res.status} ${text.take(200)}")
        }
        return runCatching {
            json.decodeFromString(RefreshResponse.serializer(), text)
        }.getOrElse { t ->
            throw PhantomApiException("Malformed refresh response: ${t.message}. Raw: ${text.take(100)}")
        }
    }

    @Serializable
    data class MobileBatch(
        val device_ts_ms: Long,
        val gps: Gps? = null,
        val motion_class: String? = null,
        val mic_rms: Float? = null,
        val body: Body? = null,
        val ble: List<BleObs> = emptyList(),
        val wifi: List<WifiObs> = emptyList(),
    ) {
        @Serializable data class Gps(val lat: Double, val lon: Double, val accuracy_m: Double? = null)
        @Serializable data class Body(val bpm: Float? = null, val hrv: Float? = null)
        @Serializable data class BleObs(val mac: String? = null, val name: String? = null, val rssi: Int)
        @Serializable
        data class WifiObs(
            val mac: String,
            val ssid: String = "",
            val rssi: Int = -100,
            val encryption: String = "unknown",
            val channel: Int = 0,
        )
    }

    @Serializable
    data class MobileBatchAck(
        val ok: Boolean,
        val batch_id: Long,
        val received_at: String,
    )

    suspend fun postMobileBatch(batch: MobileBatch): MobileBatchAck {
        val paired = store.load() ?: throw PhantomApiException("not paired")
        val res = client.post("${node()}/api/v1/sensors/mobile_batch") {
            bearerAuth(paired.deviceJwt)
            contentType(ContentType.Application.Json)
            setBody(batch)
        }
        if (res.status != HttpStatusCode.OK) {
            throw PhantomApiException("mobile_batch failed: ${res.status}")
        }
        return json.decodeFromString(MobileBatchAck.serializer(), res.bodyAsText())
    }

    @Serializable
    data class HandoffOut(
        val id: String,
        val kind: String,
        val status: String,
        val accepted_by_device: String? = null,
        val rejected_by_device: String? = null,
    )

    /**
     * Phase 1-G — `POST /api/v1/handoff/{id}/{action}` per CONTRACT §2.12.
     * `action` MUST be one of `accept`, `reject`, `cancel`. The device JWT
     * carried in the bearerAuth header gives the server the actor identity
     * — no body is required.
     *
     * Throws [PhantomApiException] on non-2xx. The caller wraps in
     * Result<Unit> so the VM can keep the sheet open for retry on failure.
     */
    suspend fun handoffAction(id: String, action: String): HandoffOut {
        require(action in setOf("accept", "reject", "cancel")) {
            "handoff action must be one of accept|reject|cancel; got: $action"
        }
        require(id.isNotBlank()) { "handoff id must be non-blank" }
        val paired = store.load() ?: throw PhantomApiException("not paired")
        val res = client.post("${node()}/api/v1/handoff/$id/$action") {
            bearerAuth(paired.deviceJwt)
            contentType(ContentType.Application.Json)
        }
        if (res.status != HttpStatusCode.OK) {
            throw PhantomApiException("handoff $action failed: ${res.status}")
        }
        return json.decodeFromString(HandoffOut.serializer(), res.bodyAsText())
    }

    // ── Симбіот ───────────────────────────────────────────────────────────
    //
    // Пульс і листи між тілами. Пульс шлемо рідко: радіо коштує батареї,
    // а місце людини не міняється щосекунди.

    @Serializable
    data class PresenceOut(
        val battery_pct: Int? = null,
        val charging: Boolean? = null,
        val network: String? = null,
        val foreground: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val accuracy_m: Double? = null,
        val motion: String? = null,
    )

    @Serializable
    data class SymbioteCommand(
        val id: String,
        val verb: String,
        val args: Map<String, String> = emptyMap(),
        val issued_by: String = "",
        val requires: String = "",
    )

    @Serializable
    data class InboxOut(val commands: List<SymbioteCommand> = emptyList())

    @Serializable
    data class ResultIn(
        val command_id: String,
        val ok: Boolean,
        val detail: String = "",
        val payload: Map<String, String> = emptyMap(),
    )

    suspend fun postPresence(presence: PresenceOut) {
        val paired = store.load() ?: return
        client.post("${node()}/api/v1/symbiote/presence") {
            bearerAuth(paired.deviceJwt)
            contentType(ContentType.Application.Json)
            setBody(presence)
        }
    }

    suspend fun fetchInbox(): List<SymbioteCommand> {
        val paired = store.load() ?: return emptyList()
        val res = client.get("${node()}/api/v1/symbiote/inbox") {
            bearerAuth(paired.deviceJwt)
        }
        if (res.status != HttpStatusCode.OK) return emptyList()
        return json.decodeFromString(InboxOut.serializer(), res.bodyAsText()).commands
    }

    /** Дієслово, яке телефон просить виконати ПК. */
    @Serializable
    data class CommandIn(
        val verb: String,
        val args: Map<String, String> = emptyMap(),
    )

    /** Що ПК відповів. `ok = false` — це теж відповідь, а не збій звʼязку. */
    @Serializable
    data class CommandOut(
        val ok: Boolean = false,
        val detail: String = "",
        val payload: Map<String, String> = emptyMap(),
    )

    /**
     * Телефон каже ПК зробити щось. Досі канал був однобічний.
     *
     * `postPresence`, `fetchInbox`, `postResult` — усі троє обслуговують ОДИН
     * напрямок: ПК опитує чергу, телефон виконує. Пʼять дієслів `pc.*` були
     * оголошені на телефоні й мертві саме тому, що четвертої адреси тут не
     * існувало — не через заборону, а через відсутність виклику.
     *
     * Приймач на вузлі вже вміє відмовити за спроможністю (403
     * `capability_denied`), тож окремої логіки відмови тут не треба: вона
     * приїде текстом у `detail`.
     *
     * МЕЖА, і вона важлива: успішна відповідь означає, що ПК ПРИЙНЯВ дієслово,
     * а не що воно щось зробило. Керування вводом на Wayland — окреме питання,
     * і жоден код відповіді про нього не свідчить. Хто перевірятиме цей шлях,
     * хай дивиться НАСЛІДОК на ПК, а не 200 тут.
     */
    suspend fun sendCommand(command: CommandIn): CommandOut {
        val paired = store.load()
            ?: return CommandOut(detail = "телефон не спарований з жодним ПК")
        val res = client.post("${node()}/api/v1/symbiote/command") {
            bearerAuth(paired.deviceJwt)
            contentType(ContentType.Application.Json)
            setBody(command)
        }
        val body = res.bodyAsText()
        if (res.status != HttpStatusCode.OK) {
            // 403 capability_denied приходить сюди ж: вузол на дроті й свідомо
            // відмовив. Це не «не докричались» — і плутати їх не можна.
            return CommandOut(
                ok = false,
                detail = body.ifBlank { "вузол відмовив: ${res.status}" },
            )
        }
        return runCatching { json.decodeFromString(CommandOut.serializer(), body) }
            .getOrElse { CommandOut(ok = true, detail = body) }
    }

    suspend fun postResult(result: ResultIn) {
        val paired = store.load() ?: return
        client.post("${node()}/api/v1/symbiote/result") {
            bearerAuth(paired.deviceJwt)
            contentType(ContentType.Application.Json)
            setBody(result)
        }
    }

    @Serializable
    data class ChatIn(val content: String, val input_method: String = "text", val session_id: String? = null)

    /** Розум ПК. Без повторів: 503 після генерації повторно запустив би модель і записав хід двічі. */
    suspend fun askBrain(content: String, sessionId: String?): Pair<Int, String> {
        val res = auth.call { token ->
            client.post("${node()}/api/v1/chat/message") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(ChatIn(content = content, session_id = sessionId))
                retry { noRetry() }
                timeout { requestTimeoutMillis = BRAIN_TIMEOUT_MS; socketTimeoutMillis = BRAIN_TIMEOUT_MS }
            }
        }
        return res.status.value to res.bodyAsText()
    }

    /**
     * Журнал змін памʼяті (docs/memory/SYNC.md) — тим самим спареним каналом, що й
     * решта розмов із ПК: [node] — мережа поруч або реле; TLS приколото до ПК.
     */
    suspend fun memoryChanges(since: Long, limit: Int): Pair<Int, String> {
        val res = auth.call { token ->
            client.get("${node()}/api/v1/memory/changes?since=$since&limit=$limit") { bearerAuth(token) }
        }
        return res.status.value to res.bodyAsText()
    }

    suspend fun pushMemoryChanges(body: String): Pair<Int, String> {
        val res = auth.call { token ->
            client.post("${node()}/api/v1/memory/changes") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return res.status.value to res.bodyAsText()
    }

    /**
     * Одноразовий токен Gemini Live від ПК («Подзвонити PHANTOM»). Ключ лишається
     * на ПК. (HTTP-код, тіло) або null — телефон ні з чим не спарений. Без
     * повторів: токен одноразовий, а 503 «немає ключа» повтором не лікується.
     */
    suspend fun liveToken(): Pair<Int, String>? {
        store.load() ?: return null
        val res = auth.call { token ->
            client.post("${node()}/api/v1/live/token") {
                bearerAuth(token)
                retry { noRetry() }
                timeout { requestTimeoutMillis = LIVE_TOKEN_TIMEOUT_MS }
            }
        }
        return res.status.value to res.bodyAsText()
    }

    internal fun node(): String =
        address.current() ?: store.load()?.lanUrl?.trimEnd('/')
            ?: throw PhantomApiException("not paired")

    suspend fun unpair() {
        address.stop()
        store.clear()
    }

    companion object {
        private const val BRAIN_TIMEOUT_MS = 120_000L
        private const val LIVE_TOKEN_TIMEOUT_MS = 8_000L

        fun deviceMeta(): Map<String, String> = mapOf(
            "name" to (Build.MODEL ?: "android"),
            "model" to "${Build.MANUFACTURER}/${Build.MODEL}",
            "platform" to "android",
            "os_version" to "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})",
        )
    }
}

/** [status] — HTTP-код вузла, коли він узагалі відповів. Його читає [troubleOf]. */
class PhantomApiException(message: String, val status: Int? = null) : RuntimeException(message)
