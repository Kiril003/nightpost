package local.phantom.companion.net

import android.util.Log
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import local.phantom.companion.data.PairedDeviceStore
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent WebSocket link to PHANTOM OS.
 *
 * Implements the Mobile Companion §6 transport: phone subscribes to a narrow
 * channel set (sensor / context / state / familiar / pair) instead of pulling
 * raw `agent.stream` traffic. Reconnect walks [BACKOFF_LADDER_MS] (1→2→5→15→60
 * seconds) and only returns to the first rung after a session that was worth
 * having — see [sessionWasUseful].
 */
class PhantomLink(
    private val api: PhantomApi,
    private val store: PairedDeviceStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionRef = AtomicReference<DefaultClientWebSocketSession?>(null)
    private var loop: Job? = null

    private val _connected = MutableStateFlow(LinkStatus.Disconnected)
    val connected: StateFlow<LinkStatus> = _connected.asStateFlow()

    private val _events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<HubEvent> = _events.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    val channels: Set<String> = PHANTOM_LINK_CHANNELS

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { connectLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        scope.launch { sessionRef.getAndSet(null)?.close() }
    }

    private suspend fun connectLoop() {
        var backoffMs = BACKOFF_LADDER_MS.first()
        while (currentCoroutineContext().isActive) {
            val paired = store.load()
            if (paired == null) {
                // Unpaired is the resting state for most devices. Polling it
                // woke a coroutine and decrypted a pref every 2 s, forever, in
                // a boot-started foreground service — for a link that cannot
                // appear without an explicit pairing. Wait for that event.
                _connected.value = LinkStatus.Disconnected
                withTimeoutOrNull(UNPAIRED_RECHECK_MS) { store.changes.first() }
                continue
            }

            val endpoints = api.address.candidates()
                .takeIf { it.isNotEmpty() }
                ?: listOf(paired.lanUrl)

            // Строк токена читається тут, а не на паринзі: телефон міг спати
            // тижнями, і мертвий JWT дав би тихий 401 замість звʼязку.
            val token = api.auth.token() ?: paired.deviceJwt

            val useful = pumpEndpoints(
                endpoints = endpoints,
                onFailure = { baseUrl, t ->
                    Log.w("PhantomLink", "Failed to connect to $baseUrl: ${t.message}")
                },
                session = { baseUrl -> pumpSession(baseUrl, token) },
            )

            // Doze friendliness: wakeup is owned by the foreground service,
            // not WorkManager periodic timers.
            delay(backoffMs)
            backoffMs = nextBackoffMs(backoffMs, useful)
        }
    }

    /**
     * Одна сесія — від рукостискання до розриву. Повертає, чи вона була варта
     * того, щоб починати відлік спроб спочатку.
     */
    private suspend fun pumpSession(baseUrl: String, token: String): Boolean {
        val startedAtMs = System.currentTimeMillis()
        var frames = 0L
        _connected.value = LinkStatus.Connecting
        try {
            val session = api.client.webSocketSession { url(buildWsUrl(baseUrl, token)) }
            sessionRef.set(session)
            _connected.value = LinkStatus.Connected
            // Половина застосунку ходить по REST — хай іде тим самим
            // шляхом, що й звʼязок, інакше говоритиме в мертву адресу.
            api.address.remember(baseUrl)
            Log.i("PhantomLink", "Connected via $baseUrl (channels=${channels.joinToString(",")})")

            // Subscribe — Phase 19-6 channel filter.
            session.send(Frame.Text(json.encodeToString(
                SubscribeMessage.serializer(),
                SubscribeMessage(control = "subscribe", channels = channels.toList()),
            )))

            val homing =
                if (api.address.isDetour(baseUrl)) scope.launch { comeHome(session) }
                else null
            try {
                for (frame in session.incoming) {
                    if (frame !is Frame.Text && frame !is Frame.Binary) continue
                    frames++
                    if (frame is Frame.Text) {
                        // Best-effort decoding — malformed frames are
                        // dropped rather than aborting the whole link.
                        val event = parseHubFrame(frame.readText(), json) ?: continue
                        _events.emit(event)
                    }
                }
            } finally {
                homing?.cancel()
            }
            // Вузол поклав слухавку через токен — продовжуємо строк ЗАРАЗ,
            // щоб наступний оберт кола пішов уже з новим пропуском, а не
            // товкся об ту саму відмову всю драбину пауз.
            if (session.closeReason.await()?.code == WS_CLOSE_UNAUTHORIZED.toShort()) {
                Log.i("PhantomLink", "вузол не прийняв токен — продовжую строк")
                api.auth.refresh(token)
            }
        } finally {
            sessionRef.set(null)
            _connected.value = LinkStatus.Disconnected
        }
        return sessionWasUseful(frames, System.currentTimeMillis() - startedAtMs)
    }

    /**
     * Обхідний шлях тримається лише поки треба.
     *
     * Труба крізь точку зустрічі жива й тоді, коли телефон уже поруч із вузлом
     * — сама вона не обірветься, а рахунок за мобільний трафік і зайва затримка
     * лишаться. Тож поки ми в обході, раз на хвилину стукаємо додому і, щойно
     * там відповідають, кладемо сесію: наступний оберт кола почне з мережі поруч.
     */
    private suspend fun comeHome(session: DefaultClientWebSocketSession) {
        while (currentCoroutineContext().isActive) {
            delay(HOME_PROBE_MS)
            if (!api.address.lanReachable()) continue
            Log.i("PhantomLink", "мережа поруч відповіла — вертаюсь із обхідного шляху")
            api.address.forget()
            runCatching { session.close() }
            return
        }
    }

    private fun buildWsUrl(baseUrl: String, token: String): String =
        buildWebSocketUrl(baseUrl, token)

    suspend fun send(text: String): Boolean = withContext(Dispatchers.IO) {
        val s = sessionRef.get() ?: return@withContext false
        runCatching { s.send(Frame.Text(text)) }.isSuccess
    }
}

/**
 * Channels the phone explicitly subscribes to on top of the server's
 * default mobile filter ({sensor, state, pair, familiar, alert, _meta,
 * context, chat}, main.py:919-928).
 *
 * - `agent.stream`: backend emits proactive/emotion/monologue events on
 *   the dotted name; the phone never received them under the legacy bare
 *   `agent` subscription (COMPANION_CONTRACT.md §10.13).
 * - `profile_sync`: the newer prefs channel duplicates `context` for
 *   `preference_changed` plus carries `profile_imported`, `fact_*`,
 *   `behavior_updated`. Without subscribing, edits routed via
 *   `POST /profile-sync/event` never reach the phone
 *   (COMPANION_CONTRACT.md §10.11).
 *
 * Top-level so unit tests can lock down the contract priorities without
 * constructing an Android-bound [PhantomLink] instance.
 */
internal val PHANTOM_LINK_CHANNELS: Set<String> = setOf(
    "companion_control", "decision_diary",
    "sensor", "context", "state", "familiar", "pair",
    "agent.stream", "profile_sync", "symbiote",
    // Стрічка месенджера. Стандартний фільтр для телефонів її не містить
    // (main.py `_MOBILE_DEFAULT_CHANNELS`), тож без цього рядка `message:new`
    // не доїде — і розмова оживала б лише від ручного перезавантаження.
    "messenger",
)

enum class LinkStatus { Disconnected, Connecting, Connected }

data class HubEvent(val channel: String, val type: String, val raw: JsonObject)

@Serializable
internal data class SubscribeMessage(val control: String, val channels: List<String>)

/**
 * Parse one server→client frame text into a typed [HubEvent], or null
 * when the frame is malformed / has no channel name.
 *
 * Server envelope (websocket_hub.py:73):
 * ```json
 * {"channel": "<name>", "type": "<verb>", "data": {...}, "ts": <unix-ms>}
 * ```
 *
 * Resilience contract:
 *  - Returns null instead of throwing — the caller's loop may not break
 *    over a single bad frame.
 *  - Reads `channel`/`type` via typed accessors. Never falls back to
 *    `JsonElement.toString().trim('"')` because that hack would silently
 *    accept JsonObject/JsonArray values and emit garbage channel names.
 */
internal fun parseHubFrame(text: String, json: Json): HubEvent? {
    val obj = runCatching {
        json.decodeFromString(JsonObject.serializer(), text)
    }.getOrNull() ?: return null
    val channel = (obj["channel"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return null
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return HubEvent(channel, type, obj)
}

/**
 * Translate a PHANTOM OS REST base URL into a WebSocket URL pointing at the
 * multiplexed `/ws` endpoint with the device JWT carried in the query string.
 *
 * Backend mounts the WS at literal `/ws` (`main.py:930`), NOT under the
 * `/api/v1` prefix. Phones that prepend the API prefix get a 404 — see
 * COMPANION_CONTRACT.md §10.1 for the regression history.
 *
 * Internal so [PhantomLink] callers go through the class, but the unit-test
 * suite can hit the helper directly.
 */
internal fun buildWebSocketUrl(baseUrl: String, token: String): String {
    val uri = URI(baseUrl)
    val isTls = uri.scheme.equals("https", ignoreCase = true)
    val wsScheme = if (isTls) "wss" else "ws"
    val host = uri.host ?: baseUrl.removePrefix("http://").removePrefix("https://").split(':').first()
    val port = if (uri.port > 0) uri.port else if (isTls) 443 else 80
    return "$wsScheme://$host:$port/ws?token=$token"
}

/**
 * Try each address in turn. Returns whether the session that answered was
 * worth having; an address that refuses outright hands over to the next one.
 *
 * The loop stops at the first address that produced a live session, however
 * short — that node answered, so the others are not candidates this round.
 */
internal suspend fun pumpEndpoints(
    endpoints: List<String>,
    onFailure: (String, Throwable) -> Unit = { _, _ -> },
    session: suspend (String) -> Boolean,
): Boolean {
    for (baseUrl in endpoints) {
        val useful = try {
            session(baseUrl)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            onFailure(baseUrl, t)
            continue
        }
        return useful
    }
    return false
}

/**
 * Чи була сесія корисною — тобто чи є сенс знову пробувати за секунду.
 *
 * Рукостискання не рахується. Вузол, що приймає зʼєднання й одразу кладе
 * слухавку — вимкнений ПК за точкою зустрічі, відкинутий токен усередині
 * обробника — інакше скидав би відлік щосекунди, назавжди. Тому потрібен
 * кадр, і сесія, що прожила довше за відмову.
 */
internal fun sessionWasUseful(frames: Long, durationMs: Long): Boolean =
    frames > 0 && durationMs >= MIN_USEFUL_SESSION_MS

/** Наступна пауза перед спробою: 1→2→5→15→60 с, і назад на початок лише за корисну сесію. */
internal fun nextBackoffMs(currentMs: Long, useful: Boolean): Long {
    if (useful) return BACKOFF_LADDER_MS.first()
    val rung = BACKOFF_LADDER_MS.indexOfFirst { it >= currentMs }
    if (rung < 0) return BACKOFF_LADDER_MS.last()
    return BACKOFF_LADDER_MS[minOf(rung + 1, BACKOFF_LADDER_MS.lastIndex)]
}

internal val BACKOFF_LADDER_MS = longArrayOf(1_000L, 2_000L, 5_000L, 15_000L, 60_000L)

/** Коротша за це сесія — це відмова, хай навіть у ній був кадр з помилкою. */
internal const val MIN_USEFUL_SESSION_MS = 3_000L

/** Safety net if a pairing lands without a pref callback. */
private const val UNPAIRED_RECHECK_MS = 300_000L

/** Як часто питати «а мережа поруч уже є?», сидячи в обхідному шляху. */
private const val HOME_PROBE_MS = 60_000L
