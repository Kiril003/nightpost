package local.phantom.companion.core.net.live

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.phantom.companion.core.net.geosrc.GeoClock
import local.phantom.companion.core.net.geosrc.SystemGeoClock

/**
 * ATLAS-4 — живий сокет міток.
 *
 * Прискорювач, не джерело істини: те, що сюди не долетіло, донесе звіряльне
 * опитування репозиторію. Через це тут немає жодної спроби «гарантувати
 * доставку» — ні черги підтверджень, ні докачки за розрив. Розрив просто
 * тихо перезбирається, а прогалину закриває опитування, яке саме на час
 * розриву й пришвидшується.
 *
 * Батарея: жодного wakelock і жодного служби переднього плану. Потік
 * холодний — сокета не існує, поки його ніхто не збирає.
 */
class LiveMarksRealtime(
    private val config: LiveMarksConfig,
    engine: HttpClientEngine? = null,
    opener: LiveWireOpener? = null,
    private val clock: GeoClock = SystemGeoClock,
    /** Розкид відкату, [0..1). Виноситься, щоб тест був детермінований. */
    private val roll: () -> Double = { Math.random() },
    /** Єдиний вихід у журнал. Через нього ж доводиться, що ключ не тече. */
    private val journal: (String) -> Unit = { Log.i(TAG, it) },
) : LiveMarksStreamPort {

    private val ktor: KtorWireOpener? = if (opener == null) KtorWireOpener(engine) else null
    private val wires: LiveWireOpener = opener ?: ktor!!

    override val isConfigured: Boolean get() = config.isConfigured

    override fun stream(): Flow<LiveStreamEvent> = flow {
        if (!config.isConfigured) return@flow
        var attempt = 0
        while (true) {
            val startedAt = clock.nowMs()
            val failure = runCatching { pump() }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            // Виняток Ktor несе в тексті повний URL — разом із ключем. Скраб
            // тут не косметика: це та сама межа, що й у журналі підʼєднання.
            val why = RealtimeHandshake.scrub(failure.describe())
            journal("сокет відпав: $why")
            emit(LiveStreamEvent.Dropped(why))
            // Відкат скидає лише сесія, яка ПРОЖИЛА. Сервер, що приймає
            // приєднання і одразу рве (вичерпана квота, перезапуск вузла),
            // інакше тримав би нас у вічному рукостисканні раз на секунду.
            attempt = if (clock.nowMs() - startedAt >= STABLE_SESSION_MS) 1 else attempt + 1
            delay(backoffMs(attempt, roll()))
        }
    }

    /** Відпускає HTTP-клієнт. Потрібне лише власнику, що згортає площину. */
    fun close() {
        ktor?.shutdown()
    }

    /** Одна сесія: приєднатись, слухати, померти. Вихід звідси = розрив. */
    private suspend fun FlowCollector<LiveStreamEvent>.pump() {
        val url = RealtimeHandshake.url(config)
        // Скрабований — інший вигляд цього рядка нікуди не йде.
        journal("підʼєднання ${RealtimeHandshake.scrub(url)}")
        val wire = wires.open(url)
        val beat = Beat()
        try {
            wire.send(PhxCodec.join(config.anonKey))
            coroutineScope {
                val pulse = launch { heartbeat(wire, beat) }
                try {
                    listen(wire, beat)
                } finally {
                    pulse.cancel()
                }
            }
        } finally {
            // Без NonCancellable закриття само впало б на скасуванні (мапа
            // закрилась) — і сокет висів би до серверного тайм-ауту.
            withContext(NonCancellable) { runCatching { wire.close() } }
        }
    }

    private suspend fun FlowCollector<LiveStreamEvent>.listen(wire: LiveWire, beat: Beat) {
        while (true) {
            val frame = PhxCodec.decode(wire.receive() ?: return) ?: continue
            when (frame.event) {
                PhxCodec.EVENT_REPLY -> when {
                    frame.topic == PhxCodec.PHOENIX -> beat.inFlight.set(0)
                    frame.ref == PhxCodec.JOIN_REF -> {
                        // Відмова в підписці — це не розрив каналу: сокет
                        // лишиться відкритим і мовчатиме вічно. Валимо самі.
                        if (!PhxCodec.isOk(frame)) error("підписку відхилено: ${PhxCodec.refusal(frame)}")
                        // «ok» із порожнім списком привʼязок — це «таблиці
                        // немає в публікації». Канал здоровий, змін не буде.
                        if (PhxCodec.grantedBindings(frame) == 0) {
                            error("сервер не дав жодної привʼязки — таблиця поза публікацією?")
                        }
                        // Свідомо ще НЕ Connected: топік прийнято, підписка на
                        // зміни — ні.
                        journal("топік прийнято: ${PhxCodec.TOPIC}")
                    }
                }

                // Тільки тут канал справді починає щось означати.
                PhxCodec.EVENT_SYSTEM -> {
                    val note = PhxCodec.systemNote(frame)
                    if (!PhxCodec.isOk(frame)) error("підписка на зміни не піднялась: $note")
                    journal("підписка жива: $note")
                    emit(LiveStreamEvent.Connected)
                }

                PhxCodec.EVENT_CHANGES -> PhxCodec.insertedMark(frame)?.let {
                    emit(LiveStreamEvent.Inserted(it, clock.nowMs()))
                }

                PhxCodec.EVENT_ERROR, PhxCodec.EVENT_CLOSE -> return
            }
        }
    }

    /**
     * Серцебиття. Рахуються НЕ відповіді за годинником, а невідповіді підряд:
     * так перевірка живе на самих `delay` і не бреше на віртуальному часі.
     * Два удари без відповіді (≈90 с мовчання) — це смерть; одного мало,
     * бо один загублений пакет підтвердження ще не означає мертвий канал.
     */
    private suspend fun heartbeat(wire: LiveWire, beat: Beat) {
        var ref = 1L
        while (true) {
            delay(HEARTBEAT_MS)
            if (beat.inFlight.get() >= MAX_SILENT_BEATS) {
                journal("серцебиття без відповіді — рву канал")
                wire.close()
                return
            }
            beat.inFlight.incrementAndGet()
            wire.send(PhxCodec.heartbeat("hb-${ref++}"))
        }
    }

    // Лічильник чіпають дві корутини — бʼє одна, обнуляє інша.
    private class Beat {
        val inFlight = AtomicInteger(0)
    }

    companion object {
        private const val TAG = "LivePlane"

        const val HEARTBEAT_MS: Long = 30_000L

        /** Скільки ударів поспіль можна лишити без відповіді, поки канал живий. */
        const val MAX_SILENT_BEATS: Int = 2

        /** Скільки сесія мусить прожити, щоб рахуватись успішною для відкату. */
        const val STABLE_SESSION_MS: Long = 60_000L

        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val JITTER = 0.25

        /**
         * Відкат із розкидом. Розкид тут не косметика: без нього десяток
         * пристроїв, що втратили ту саму вежу, ломляться назад одночасно.
         */
        internal fun backoffMs(attempt: Int, roll: Double): Long {
            val step = BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 5)
            val capped = minOf(step, MAX_BACKOFF_MS).toDouble()
            val spread = capped * JITTER
            return (capped - spread + 2 * spread * roll.coerceIn(0.0, 1.0)).toLong().coerceAtLeast(250L)
        }
    }
}

private fun Throwable?.describe(): String = when (this) {
    null -> "канал закрито"
    is ClosedReceiveChannelException -> "канал закрито"
    else -> "${javaClass.simpleName}: ${message?.take(120) ?: "без деталей"}"
}

/** Ktor поверх OkHttp. Клієнт постає при першому підʼєднанні, не раніше. */
private class KtorWireOpener(private val engine: HttpClientEngine?) : LiveWireOpener {

    @Volatile
    private var http: HttpClient? = null

    override suspend fun open(url: String): LiveWire = KtorWire(client().webSocketSession(url))

    fun shutdown() {
        http?.close()
        http = null
    }

    private fun client(): HttpClient = http ?: synchronized(this) {
        http ?: newClient().also { http = it }
    }

    private fun newClient(): HttpClient =
        if (engine == null) HttpClient(OkHttp) { install(WebSockets) } else HttpClient(engine) { install(WebSockets) }
}

private class KtorWire(private val session: DefaultClientWebSocketSession) : LiveWire {

    override suspend fun send(text: String) = session.send(Frame.Text(text))

    override suspend fun receive(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) return frame.readText()
        }
    }

    override suspend fun close() {
        runCatching { session.close() }
    }
}
