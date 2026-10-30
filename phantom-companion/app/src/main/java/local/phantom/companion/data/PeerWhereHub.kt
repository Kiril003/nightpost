package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.peers.PeerBox
import local.phantom.companion.core.data.peers.PeerMotion
import local.phantom.companion.core.data.peers.PeerNear
import local.phantom.companion.core.data.peers.PeerSpot
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPointsRepository
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.PeerShare
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.core.net.peer.PeerWhereBeacon
import local.phantom.companion.core.net.peer.PeerWhereGrid
import local.phantom.companion.core.net.peer.PeerWhereInbox

/** Згода «хто мене бачить» — не положення: її можна й треба памʼятати. */
interface PeerWhereGrants {
    fun load(): Map<String, PeerWhere.Precision>
    fun save(grants: Map<String, PeerWhere.Precision>)
    fun loadEnds(): Map<String, Long> = emptyMap()
    fun saveEnds(ends: Map<String, Long>) {}

    /** Привид: ключ `*` — від усіх. Строки в мілісекундах. */
    fun loadHidden(): Map<String, Long> = emptyMap()
    fun saveHidden(hidden: Map<String, Long>) {}
    fun loadLowBattery(): Boolean = false
    fun saveLowBattery(on: Boolean) {}

    /** «До зустрічі»: кому показ сам гасне, щойно ми поруч. */
    fun loadMeet(): Set<String> = emptySet()
    fun saveMeet(ids: Set<String>) {}
}

/** Що розмова з людиною знає про місце в обидва боки. Координат тут немає — лише рівень, близькість і час. */
data class PeerWhereView(
    val whyNot: WhyNot?,
    val mine: Mine?,
    val theirs: Theirs?,
    /** Показ «до зустрічі» згас сам, бо ми зустрілись. */
    val metAtMs: Long? = null,
) {
    enum class WhyNot { NO_KEY, KEY_ALARM, NOT_INTRODUCED, UNKNOWN }

    /** [untilMs] нуль — до вимкнення. */
    data class Mine(val precision: PeerWhere.Precision, val untilMs: Long, val stand: Boolean = false, val meet: Boolean = false)

    data class Theirs(
        val precision: PeerWhere.Precision,
        val near: PeerWhereGrid.Nearness?,
        val atMs: Long,
        val expiresAtMs: Long,
        val stand: Boolean = false,
    )

    /** Ціль для мапи: район — центр плями, точно — точка. */
    data class Focus(val lat: Double, val lon: Double, val zoom: Double)
}

/**
 * Люди на мапі в обидва боки. Відправник — [PeerWhereBeacon], приймач —
 * [PeerWhereInbox]; обидва живуть лише в памʼяті цього обʼєкта.
 *
 * Годинник один і спить до [PeerWhereBeacon.nextWakeMs]; GNSS сам не будить:
 * береться той вимір, який застосунок уже тримає, і лише навігаційний, бо в
 * інших немає віку, а видати старе за «щойно» — брехня другові.
 */
class PeerWhereHub(
    private val points: PeerPointsRepository,
    private val roster: () -> List<PeerIdentity>,
    private val fix: () -> LocationFix?,
    private val send: (peerId: String, body: String) -> Unit,
    private val grantStore: PeerWhereGrants,
    private val fixAllowed: () -> Boolean,
    private val outbox: () -> List<PeerMessage> = { emptyList() },
    private val forget: (Set<String>) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    doing: (LocationFix, lowBatteryShared: Boolean) -> PeerWhere.State = { _, _ -> PeerWhere.QUIET },
    face: (String) -> String? = { null },
) {
    @Volatile private var doing = doing
    @Volatile private var face = face

    /** Чим людина зайнята зараз і чиє обличчя — підʼєднується при старті, після того як хаб уже живе. */
    fun wire(doing: (LocationFix, Boolean) -> PeerWhere.State, face: (String) -> String?) {
        this.doing = doing
        this.face = face
    }

    private var beacon = PeerWhereBeacon.State()
    private var inbox = PeerWhereInbox.Inbox()
    private var published: Set<String> = emptySet()
    private var meet: Set<String> = emptySet()
    private var met: Map<String, Long> = emptyMap()
    private var scope: CoroutineScope? = null
    private var clock: Job? = null
    private var broom: Job? = null
    private val lock = Any()

    private val _grants = MutableStateFlow<Map<String, PeerWhere.Precision>>(emptyMap())
    val grants: StateFlow<Map<String, PeerWhere.Precision>> = _grants.asStateFlow()

    private val _self = MutableStateFlow(local.phantom.companion.core.data.peers.PeerSelfShow())
    val self: StateFlow<local.phantom.companion.core.data.peers.PeerSelfShow> = _self.asStateFlow()

    private val _hidden = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Від кого сховались і до коли; прострочене сюди не потрапляє. */
    val hidden: StateFlow<Map<String, Long>> = _hidden.asStateFlow()

    private val _changes = MutableStateFlow(0L)

    /** Лічильник змін для веб-розмови: приймач живе повз журнал, тож `outbox` про нього мовчить. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun attach(scope: CoroutineScope) {
        this.scope = scope
        val saved = runCatching { grantStore.load() }.getOrDefault(emptyMap())
        val ends = runCatching { grantStore.loadEnds() }.getOrDefault(emptyMap())
        val at = nowMs()
        synchronized(lock) {
            val people = roster().associateBy { it.id }
            saved.forEach { (id, p) ->
                val peer = people[id] ?: return@forEach
                PeerWhereBeacon.grant(gated(beacon), peer, p, at, untilMs = ends[id] ?: 0L)?.let { beacon = it.state; emit(it.out) }
            }
            meet = runCatching { grantStore.loadMeet() }.getOrDefault(emptySet()).filterTo(HashSet()) { it in beacon.lanes }
            if (beacon.lanes.keys != saved.keys) commitGrants() else _grants.value = beacon.lanes.mapValues { it.value.precision }
            val ghosts = runCatching { grantStore.loadHidden() }.getOrDefault(emptyMap()).filterValues { it > at }
            ghosts.forEach { (id, until) -> beacon = PeerWhereBeacon.hide(beacon, id.takeUnless { it == ALL }, until) }
            _self.value = _self.value.copy(lowBattery = runCatching { grantStore.loadLowBattery() }.getOrDefault(false))
            publishHidden(at)
        }
        rearmClock()
    }

    /**
     * Null — нічого. Хто не [PeerWhere.mayReceive], дозволу не отримує. [untilMs] нуль — до вимкнення.
     * [meet] — лише точно й зі строком: показ гасне сам, щойно ми ближче [MEET_M].
     */
    fun setPrecision(peerId: String, precision: PeerWhere.Precision?, untilMs: Long = 0L, meet: Boolean = false): Boolean {
        if (meet && (precision != PeerWhere.Precision.EXACT || untilMs <= 0L)) return false
        val at = nowMs()
        synchronized(lock) {
            if (precision == null) {
                val step = PeerWhereBeacon.revoke(beacon, peerId, at)
                beacon = step.state
                emit(step.out)
            } else {
                val peer = roster().firstOrNull { it.id == peerId } ?: return false
                val step = PeerWhereBeacon.grant(PeerWhereBeacon.resume(gated(beacon)), peer, precision, at, untilMs = untilMs) ?: return false
                beacon = step.state
                met = met - peerId
                this.meet = if (meet) this.meet + peerId else this.meet - peerId
                emit(step.out)
                feed(at)
            }
            if (precision == null) this.meet = this.meet - peerId
            commitGrants()
        }
        rearmClock()
        return true
    }

    /**
     * Привид одним рухом: [peerId] null — від усіх. [untilMs] нуль — вийти.
     * Той, від кого сховались, бачить лише, що місце давно не оновлювалось.
     */
    fun hide(peerId: String?, untilMs: Long) {
        val at = nowMs()
        synchronized(lock) {
            val until = untilMs.takeIf { it > at } ?: 0L
            beacon = PeerWhereBeacon.hide(beacon, peerId, until)
            publishHidden(at)
            runCatching { grantStore.saveHidden(_hidden.value) }
            _changes.value += 1
        }
        rearmClock()
    }

    fun shareLowBattery(on: Boolean) {
        _self.value = _self.value.copy(lowBattery = on)
        runCatching { grantStore.saveLowBattery(on) }
        _changes.value += 1
    }

    private fun publishHidden(at: Long) {
        val lanes = beacon.lanes.mapValues { it.value.hiddenUntilMs }.filterValues { it > at }
        val all = beacon.hiddenAllUntilMs.takeIf { it > at }
        _hidden.value = if (all != null) lanes + (ALL to all) else lanes
        _self.value = _self.value.copy(hiddenAllUntilMs = all ?: 0L)
    }

    /** Налаштування показу для листа собі: `see:<id>`, `ghost:*`, `ghost:<id>`, `battery`. */
    fun selfSettings(): Map<String, String> = synchronized(lock) {
        val at = nowMs()
        buildMap {
            beacon.lanes.values.forEach { put("see:${it.peerId}", "${it.precision.name.lowercase()};${it.untilMs}") }
            _hidden.value.filterValues { it > at }.forEach { (id, until) -> put("ghost:$id", until.toString()) }
            if (_self.value.lowBattery) put("battery", "on")
        }
    }

    /** Налаштування з іншого мого пристрою. Показ іде лише з телефона — тут він лише застосовується. */
    fun applySetting(key: String, value: String?) {
        when {
            key.startsWith("see:") -> {
                val id = key.removePrefix("see:")
                val p = value?.substringBefore(';')?.let { w -> PeerWhere.Precision.entries.firstOrNull { it.name.equals(w, true) } }
                setPrecision(id, p, value?.substringAfter(';', "0")?.toLongOrNull() ?: 0L)
            }
            key.startsWith("ghost:") -> hide(key.removePrefix("ghost:").takeUnless { it == ALL }, value?.toLongOrNull() ?: 0L)
            key == "battery" -> shareLowBattery(value == "on")
        }
    }

    /** «Зникнути»: стерти в усіх, забути всі дозволи. */
    fun vanish() {
        val at = nowMs()
        synchronized(lock) {
            val step = PeerWhereBeacon.vanish(beacon, at)
            beacon = PeerWhereBeacon.resume(step.state)
            emit(step.out)
            commitGrants()
        }
        clock?.cancel()
    }

    /**
     * Новий вимір від чипа. Без цього «Показати» без свіжого виміру в ту мить не
     * слало нічого й ніколи: годинник маяка без виміру не будиться.
     */
    fun onFix() {
        val at = nowMs()
        synchronized(lock) {
            if (!PeerWhereBeacon.wantsFix(beacon, at)) return
            val before = beacon
            feed(at)
            if (beacon == before) return
        }
        rearmClock()
    }

    fun ask(peerId: String) {
        PeerWhere.encode(PeerWhere.Ask(nowMs()))?.let { send(peerId, it) }
    }

    /** Вхідний кадр — сюди, повз журнал: див. `PeerMesh.onWhere`. */
    fun absorb(message: PeerMessage) {
        val at = nowMs()
        synchronized(lock) {
            if (PeerWhereInbox.isAsk(message.body)) {
                feed(at)
                val step = PeerWhereBeacon.onAsk(gated(beacon), message.fromId, at)
                beacon = step.state
                emit(step.out)
                return
            }
            val sender = roster().firstOrNull { it.id == message.fromId }
            val allowed = sender != null && sender.dhPubKey.isNotBlank() && !sender.keyAlarm
            inbox = PeerWhereInbox.absorb(inbox, message.fromId, message.body, at, allowed)
            publish(at)
            meetUp(at)
        }
        rearmBroom()
    }

    /** Лише для стенда debug-збірки: справжній кадр через справжній приймач, від вигаданого відправника з чесним іменем. */
    fun debugStand(name: String, precision: PeerWhere.Precision?, northM: Double = 900.0, fromId: String = STAND) {
        val at = nowMs()
        val here = fix() ?: return
        val lat = here.lat + northM / 111_320.0
        val frame = when (val level = precision?.level) {
            null -> if (precision == null) PeerWhere.Erase(at) else PeerWhere.Point(lat, here.lon, 20, at, at + 15 * 60_000L, 1)
            else -> PeerWhere.Area(PeerWhereGrid.cellOf(lat, here.lon, level), at, at + 30 * 60_000L, 1)
        }
        val body = PeerWhere.encode(frame) ?: return
        synchronized(lock) {
            if (precision == null) standNames.remove(fromId) else standNames[fromId] = name
            inbox = PeerWhereInbox.absorb(inbox, fromId, body, at, allowed = true)
            publish(at)
        }
        rearmBroom()
    }

    /** Лише стенд debug-збірки: картка «Ви показуєте» без жодного кадру в дріт. */
    fun debugMine(peerId: String, precision: PeerWhere.Precision?, untilMs: Long = 0L) {
        synchronized(lock) {
            if (precision == null) standMine.remove(peerId) else standMine[peerId] = PeerWhereView.Mine(precision, untilMs, stand = true)
            _changes.value += 1
        }
    }

    private val standNames = HashMap<String, String>()
    private val standMine = HashMap<String, PeerWhereView.Mine>()

    fun view(peerId: String): PeerWhereView {
        val at = nowMs()
        val peer = roster().firstOrNull { it.id == peerId }
        val whyNot = when {
            peer == null -> PeerWhereView.WhyNot.UNKNOWN
            PeerWhere.mayReceive(peer) -> null
            peer.dhPubKey.isBlank() -> PeerWhereView.WhyNot.NO_KEY
            peer.keyAlarm -> PeerWhereView.WhyNot.KEY_ALARM
            else -> PeerWhereView.WhyNot.NOT_INTRODUCED
        }
        val here = fix()?.takeIf { it.locatesTheDevice }
        return synchronized(lock) {
            val lane = beacon.lanes[peerId]?.takeIf { beacon.on }
            val mine = lane?.let { PeerWhereView.Mine(it.precision, it.untilMs, meet = peerId in meet) } ?: standMine[peerId]?.takeIf { it.untilMs == 0L || it.untilMs > at }
            val theirs = PeerWhereInbox.standingFor(inbox, peerId, at)?.let { s ->
                PeerWhereView.Theirs(
                    precision = s.position.precision,
                    near = here?.let { PeerWhereInbox.nearness(it.lat, it.lon, s) },
                    atMs = s.position.atMs,
                    expiresAtMs = s.expiresAtMs,
                    stand = peerId in standNames,
                )
            }
            PeerWhereView(whyNot, mine, theirs, metAtMs = met[peerId]?.takeIf { mine == null })
        }
    }

    fun focus(peerId: String): PeerWhereView.Focus? = synchronized(lock) {
        when (val p = PeerWhereInbox.standingFor(inbox, peerId, nowMs())?.position) {
            null -> null
            is PeerWhere.Point -> PeerWhereView.Focus(p.lat, p.lon, 16.0)
            is PeerWhere.Area -> PeerWhereGrid.centreOf(p.cell).let { (lat, lon) ->
                PeerWhereView.Focus(lat, lon, if (p.cell.level == PeerWhereGrid.Level.DISTRICT) 12.5 else 15.0)
            }
        }
    }

    fun sweep() {
        synchronized(lock) {
            val at = nowMs()
            inbox = PeerWhereInbox.sweep(inbox, at)
            publish(at)
        }
    }

    /** Ключ підмінили чи людину прибрали — дозвіл і її точка гаснуть разом. */
    fun onRoster(people: List<PeerIdentity>) {
        synchronized(lock) {
            beacon = PeerWhereBeacon.prune(beacon, people)
            val ok = people.filter { it.dhPubKey.isNotBlank() && !it.keyAlarm }.map { it.id }.toSet()
            inbox = inbox.copy(standing = inbox.standing.filterKeys { it in ok })
            if (_grants.value.keys != beacon.lanes.keys) commitGrants()
            publish(nowMs())
        }
    }

    private fun commitGrants() {
        val now = beacon.lanes.mapValues { it.value.precision }
        _grants.value = now
        _changes.value += 1
        runCatching { grantStore.save(now) }
        runCatching { grantStore.saveEnds(beacon.lanes.mapValues { it.value.untilMs }.filterValues { it > 0L }) }
        meet = meet.filterTo(HashSet()) { it in beacon.lanes }
        runCatching { grantStore.saveMeet(meet) }
    }

    // Час виміру — наш годинник, не супутниковий: той «зараз» випереджає nowMs на мілісекунди,
    // і маяк вважав такий вимір майбутнім, тобто несвіжим, — кадр не йшов зовсім.
    private fun feed(at: Long) {
        fixOnly(at)
        meetUp(at)
    }

    // Without the grant the beacon must not replay a fix it cached before the revoke.
    private fun gated(s: PeerWhereBeacon.State) = if (fixAllowed()) s else PeerWhereBeacon.forgetPosition(s)

    // Кешований фікс провайдера переживає відкликаний дозвіл ОС; без цієї перевірки кадри йшли б далі.
    private fun outgoingFix(): LocationFix? =
        if (!fixAllowed()) null else fix()?.takeIf { it.isNavigationGrade && !it.dead && it.locatesTheDevice }

    private fun fixOnly(at: Long) {
        val f = outgoingFix() ?: return
        val step = PeerWhereBeacon.onFix(
            beacon,
            PeerWhereBeacon.Fix(f.lat, f.lon, f.accuracyM?.takeIf { it > 0f }?.toInt(), at, runCatching { doing(f, _self.value.lowBattery) }.getOrDefault(PeerWhere.QUIET)),
            at,
        )
        beacon = step.state
        emit(step.out)
    }

    /**
     * Обидва точно й ближче [MEET_M] — показ «до зустрічі» гасне сам. Перед стиранням — остання
     * точка позачергово: без неї той бік бачить лише стару, далеку, і свій показ не гасить.
     */
    private fun meetUp(at: Long) {
        if (meet.isEmpty()) return
        val here = outgoingFix() ?: return
        val close = meet.filter { id ->
            val p = PeerWhereInbox.standingFor(inbox, id, at)?.position as? PeerWhere.Point ?: return@filter false
            metres(here.lat, here.lon, p.lat, p.lon) <= MEET_M
        }
        if (close.isEmpty()) return
        meet = meet - close.toSet()
        met = met + close.associateWith { at }
        fixOnly(at)
        close.forEach { id ->
            val last = PeerWhereBeacon.onAsk(gated(beacon), id, at)
            beacon = last.state
            emit(last.out)
            val step = PeerWhereBeacon.revoke(beacon, id, at)
            beacon = step.state
            emit(step.out)
        }
        commitGrants()
    }

    private fun wake() {
        val at = nowMs()
        synchronized(lock) {
            feed(at)
            val step = PeerWhereBeacon.onTick(gated(beacon), at)
            beacon = step.state
            emit(step.out)
            if (_grants.value.keys != beacon.lanes.keys) commitGrants()
            if (_hidden.value.values.any { it <= at }) {
                publishHidden(at)
                runCatching { grantStore.saveHidden(_hidden.value) }
                _changes.value += 1
            }
        }
        val gone = PeerWhereInbox.spent(outbox(), at)
        if (gone.isNotEmpty()) forget(gone)
    }

    private fun emit(out: List<PeerWhereBeacon.Out>) = out.forEach { send(it.peerId, it.body) }

    private fun rearmClock() {
        clock?.cancel()
        val host = scope ?: return
        clock = host.launch {
            while (true) {
                val next = synchronized(lock) { PeerWhereBeacon.nextWakeMs(beacon, nowMs()) } ?: break
                delay((next - nowMs()).coerceAtLeast(CLOCK_FLOOR_MS))
                wake()
            }
        }
    }

    private fun rearmBroom() {
        broom?.cancel()
        val host = scope ?: return
        broom = host.launch {
            while (true) {
                val at = nowMs()
                val next = synchronized(lock) { inbox.standing.values.minOfOrNull { it.expiresAtMs } } ?: break
                delay((next - at).coerceAtLeast(CLOCK_FLOOR_MS))
                sweep()
            }
        }
    }

    private fun publish(at: Long) {
        val names = roster().associateBy { it.id }
        val here = fix()?.takeIf { it.locatesTheDevice }
        val live = PeerWhereInbox.live(inbox, at)
        val ids = live.map { NODE + it.peerId }.toSet()
        (published - ids).forEach(points::drop)
        if (published != ids || live.isNotEmpty()) _changes.value += 1
        published = ids
        if (live.isEmpty()) return
        points.upsertBatch(live.map { s -> pointOf(s, names[s.peerId], here) })
    }

    private fun pointOf(s: PeerWhereInbox.Standing, who: PeerIdentity?, here: LocationFix?): PeerPoint {
        val near = here?.let { PeerWhereInbox.nearness(it.lat, it.lon, s) }?.let { PeerNear.valueOf(it.name) }
        val base = PeerPoint(
            nodeId = NODE + s.peerId,
            threadId = s.peerId,
            name = standNames[s.peerId] ?: who?.name.orEmpty(),
            lat = 0.0,
            lon = 0.0,
            atMs = s.position.atMs,
            verified = who?.isVerified == true,
            share = PeerShare(ShareMode.WHERE, s.expiresAtMs),
            precision = PeerPrecision.valueOf(s.position.precision.name),
            near = near,
            motion = when (s.position.state.motion) {
                PeerWhere.Motion.UNKNOWN -> null
                PeerWhere.Motion.STILL -> PeerMotion.STILL
                PeerWhere.Motion.FOOT -> PeerMotion.FOOT
                PeerWhere.Motion.VEHICLE -> PeerMotion.VEHICLE
            },
            spot = when (s.position.state.spot) {
                PeerWhere.Spot.NONE -> null
                PeerWhere.Spot.HOME -> PeerSpot.HOME
                PeerWhere.Spot.WORK -> PeerSpot.WORK
            },
            lowBattery = s.position.state.lowBattery,
            face = runCatching { face(s.peerId) }.getOrNull(),
        )
        return when (val p = s.position) {
            is PeerWhere.Area -> {
                val (lat, lon) = PeerWhereGrid.centreOf(p.cell)
                val b = PeerWhereGrid.boundsOf(p.cell)
                base.copy(lat = lat, lon = lon, box = PeerBox(b.south, b.west, b.north, b.east))
            }
            is PeerWhere.Point -> base.copy(lat = p.lat, lon = p.lon, accuracyM = p.accuracyM?.toFloat())
        }
    }

    private companion object {
        const val NODE = "where:"
        const val ALL = "*"
        const val STAND = "stand-debug"
        const val CLOCK_FLOOR_MS = 1_000L
        const val MEET_M = 50.0

        fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
            return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
        }
    }
}
