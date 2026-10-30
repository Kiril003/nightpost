package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerRelay
import local.phantom.companion.core.net.peer.PeerRelayCourier
import local.phantom.companion.core.net.peer.PeerRelayLive
import local.phantom.companion.core.net.peer.PeerRelayPump
import local.phantom.companion.core.net.peer.PeerRelayTransport

/**
 * Другий шлях сітки — той, що йде через сховок.
 *
 * [PeerMesh] тримає реєстр, ключі та журнал; насос сховка хоче їх усі, але не
 * хоче Android. Тому шов саме тут: сюди заходять вузькі лямбди, звідси виходить
 * [PeerRelayPump.World], і весь порядок сховка перевіряється на JVM без
 * пристрою — на тих самих класах, які працюють на телефоні, а не на їх переказі.
 *
 * Транспорт складається лінькувато й живе до [close]: [open] будує OkHttp, і
 * платити за нього телефоном, якому нема кому писати, нема за що.
 *
 * Сховок v2: поруч із транспортом тримається живий канал ([PeerRelayLive]).
 * Поки він підписаний на всі скриньки, лист приходить у мить вкидання, а
 * опитування лишається запасом раз на [SAFETY_POLL_MS]. Немає каналу —
 * опитування раз на [FALLBACK_POLL_MS], а не кожні 2 с, як було.
 */
internal class PeerMeshRelay(
    private val whoAmI: () -> String?,
    private val decoySecretOf: () -> ByteArray?,
    private val roster: () -> List<String>,
    private val pairKeyOf: (String) -> ByteArray?,
    private val signingKeyOf: (String) -> ByteArray?,
    private val readLog: () -> List<PeerMessage>,
    private val writeLog: ((List<PeerMessage>) -> List<PeerMessage>) -> Unit,
    private val store: (PeerMessage) -> Boolean,
    private val open: (contacts: Int) -> PeerRelayTransport?,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Живий канал щойно підписався: черга, що чекала паузи, може йти зараз. */
    private val onReconnect: () -> Unit = {},
    /** Конверт v5 (docs/crypto/RELAY_FS.md); типово немає — лист іде v3. */
    private val fsSeal: (PeerMessage) -> local.phantom.companion.core.net.peer.RelayFsMail.Sealed? = { null },
    private val fsOpen: (raw: String, peerId: String) -> PeerMessage? = { _, _ -> null },
    private val fsLanded: (raw: String, peerId: String) -> String? = { _, _ -> null },
    private val door: () -> ByteArray? = { null },
    private val openDoorWith: (blob: ByteArray, tag: String) -> String? = { _, _ -> null },
    private val doorIn: suspend (raw: String) -> Boolean = { false },
    /** Живий кадр із власних дверей, уже розпечатаний: конверт пари. */
    private val liveIn: (raw: String) -> Unit = {},
) : PeerRelayPump.World {

    @Volatile private var cached: PeerRelayPump? = null
    @Volatile private var courier: PeerRelayCourier? = null
    @Volatile private var redropEpoch = Long.MIN_VALUE
    @Volatile private var transport: PeerRelayTransport? = null
    @Volatile private var live: PeerRelayLive? = null
    @Volatile private var prepared: PeerRelayPump.Prepared? = null
    private var preparedFor: String = ""

    /** Забір і поштовх не мають розбирати ту саму копію двічі одночасно. */
    private val turn = Mutex()

    @Volatile private var redial: Job? = null

    private val events = object : PeerRelayLive.Events {
        override fun onBlobs(blobs: List<PeerRelayTransport.Blob>, storeSinceMs: Long?) {
            val engine = cached ?: return
            val plan = prepared ?: return
            scope.launch { turn.withLock { engine.absorb(plan, blobs, storeSinceMs) } }
        }

        override fun onSubscribed() {
            liveTrace("subscribed")
            onReconnect()
        }

        // На потоці сокета, без пересадки: кандидати ICE мусять прийти в тому
        // порядку, в якому їх вислали.
        override fun onLive(tag: String, data: ByteArray) {
            val key = door() ?: return
            val today = PeerRelay.epochOf(nowMs())
            if ((today - 1..today + 1).none { PeerRelay.doorTagOf(key, it) == tag }) return
            // "" — не відчиняється нашим ключем: чужий кадр у наших дверях.
            val raw = openDoorWith(data, tag)?.takeIf { it.isNotEmpty() } ?: return
            runCatching { liveIn(raw) }
        }

        // Не чекати такту насоса: один відкладений набір рівно на паузу каналу.
        override fun onClosed() {
            val channel = live ?: return
            liveTrace("closed, redial in ${channel.redialInMs(nowMs())}ms")
            redial?.cancel()
            redial = scope.launch {
                delay(channel.redialInMs(nowMs()))
                tickLive()
            }
        }
    }

    private fun liveTrace(what: String) {
        if (!local.phantom.companion.BuildConfig.DEBUG) return
        runCatching { android.util.Log.i("PhantomCall", "relay live $what") }
    }

    private var round = 0L

    private var lastPollMs = 0L

    override fun selfId(): String? = whoAmI()

    override fun contacts(): List<String> = roster()

    override fun decoySecret(): ByteArray? = decoySecretOf()

    override fun pairKey(peerId: String): ByteArray? = pairKeyOf(peerId)

    override fun signingKey(peerId: String): ByteArray? = signingKeyOf(peerId)

    override fun outbox(): List<PeerMessage> = readLog()

    override fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) = writeLog(transform)

    override fun persist(message: PeerMessage): Boolean = store(message)

    override fun relayFsSeal(message: PeerMessage) = fsSeal(message)

    override fun relayFsOpen(raw: String, peerId: String): PeerMessage? = fsOpen(raw, peerId)

    override fun relayFsLanded(raw: String, peerId: String): String? = fsLanded(raw, peerId)

    override fun ownDoor(): ByteArray? = door()

    override fun openDoor(blob: ByteArray, tag: String): String? = openDoorWith(blob, tag)

    override suspend fun onDoor(raw: String): Boolean = doorIn(raw)

    /** Чи є куди йти взагалі. Ворота вирішують, не це місце. */
    fun ready(): Boolean = pump() != null

    /**
     * Слово в сховок. false — не взяв: або відмовив, або його там немає.
     * Канал смикається тут же: квитанція на цей лист прийде саме ним.
     */
    suspend fun handOff(message: PeerMessage): Boolean {
        val engine = pump() ?: return false
        tickLive()
        return engine.handOff(message)
    }

    /** Кадр у двері людини: знайомство, відповідь на нього або завеликий сигнал. */
    suspend fun dropDoor(theirDhPubB64: String, theirId: String, frame: String): Boolean {
        pump() ?: return false
        tickLive()
        val carrier = courier ?: return false
        return carrier.dropDoor(theirDhPubB64, theirId, frame) is PeerRelayCourier.Handoff.Held
    }

    fun sendLive(theirDhPubB64: String, theirId: String, frame: String): PeerRelayCourier.Live =
        courier?.sendLive(theirDhPubB64, theirId, frame) ?: PeerRelayCourier.Live.NO_CHANNEL

    /** Живий канал підписаний — і на власні двері теж: сигнал має куди йти й звідки прийти. */
    fun liveReady(): Boolean = live?.subscribed == true

    /** Мережа змінилась або людина повернулась: спитати сервер наново, без пауз. */
    fun reprobe() {
        val opened = transport ?: return
        scope.launch {
            opened.probe()
            live?.reset()
            tickLive()
        }
    }

    /**
     * Захід по все своє: спершу зняти зі сховка те, чий тиждень вийшов, потім
     * забрати. Порядок такий, бо лист, який сплив, мусить повернутись у чергу
     * ДО того, як цей же захід вирішить, що на телефоні нічого не чекає.
     */
    suspend fun collect(): PeerRelayPump.Report {
        val engine = pump() ?: return PeerRelayPump.Report()
        lastPollMs = nowMs()
        val report = turn.withLock {
            engine.sweepExpired()
            engine.poll(round++)
        }
        val now = nowMs()
        val today = PeerRelay.epochOf(now)
        if (redropEpoch != today) {
            val due = PeerOutbox.staleHeld(readLog(), whoAmI().orEmpty(), today - 1, now).size
            if (due == 0 || engine.redropStale() > 0) redropEpoch = today
        }
        return report
    }

    /**
     * Такт сітки (2 с). Спершу — живий канал: підписаний, перепідписаний на
     * нову добу або набраний знову, коли його пауза минула. Потім забір, але
     * лише коли час: [intervalMs], якщо його названо, інакше — залежно від
     * того, чи живий канал бачить усі скриньки.
     */
    suspend fun collectIfDue(intervalMs: Long? = null): PeerRelayPump.Report {
        tickLive()
        val every = intervalMs ?: if (liveCoversAll()) minOf(SAFETY_POLL_MS, transport?.pollEveryMs ?: SAFETY_POLL_MS) else FALLBACK_POLL_MS
        if (nowMs() - lastPollMs < every) return PeerRelayPump.Report()
        return collect()
    }

    /** Живий канал підписаний на КОЖНУ скриньку — опитувати часто нема чого. */
    fun liveCoversAll(): Boolean = live?.subscribed == true && prepared?.complete == true

    /** Скільки разів набирали живий канал — для заміру. */
    fun liveDials(): Int = live?.dials ?: 0

    /** Дорога до сховка вузька (RTT ≥ 1 с або провали): текст першим, медіа чекає. */
    fun narrow(): Boolean {
        val meter = transport?.meter ?: return false
        meter.recheck()
        return meter.narrow
    }

    @Synchronized
    private fun tickLive() {
        val engine = pump() ?: return
        val now = nowMs()
        val contacts = contacts()
        val key = "${PeerRelay.epochOf(now)}|${door() != null}|${contacts.joinToString(",")}"
        if (key != preparedFor || prepared == null) {
            prepared = engine.prepare()
            preparedFor = key
        }
        val plan = prepared ?: return
        val channel = live ?: transport?.openLive(events)?.also { live = it } ?: return
        channel.ensure(plan.plan.tags, now)
    }

    fun close() {
        redial?.cancel()
        live?.close()
        live = null
        prepared = null
        preparedFor = ""
        transport = null
        cached = null
        courier = null
    }

    @Synchronized
    private fun pump(): PeerRelayPump? {
        cached?.let { return it }
        val watched = roster().size + if (door() != null) 1 else 0
        val opened = open(watched) ?: return null
        transport = opened
        scope.launch { opened.probe() }
        val carrier = PeerRelayCourier(opened, live = { live }, nowMs = nowMs).also { courier = it }
        return PeerRelayPump(this, carrier, nowMs).also { cached = it }
    }

    companion object {
        /** Запас, поки живий канал тримає всі скриньки: 144 заборів на добу. */
        const val SAFETY_POLL_MS = 600_000L

        /** Живого каналу немає: у межах 30–60 с, а не кожні 2 с. */
        const val FALLBACK_POLL_MS = 45_000L
    }
}
