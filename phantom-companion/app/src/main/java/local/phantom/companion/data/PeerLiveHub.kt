package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPointsRepository
import local.phantom.companion.core.data.peers.PeerShare
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerLive
import local.phantom.companion.core.net.peer.PeerLiveInbox
import local.phantom.companion.core.net.peer.PeerLiveShare
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRoster

/**
 * Сеанси «ділюсь положенням» — і жодного шару присутності.
 *
 * Кожен сеанс — це одна людина, названа рукою, і один строк. Гуртового «хто
 * де» тут немає навіть як можливості: сеанс заводиться лише через [start] з
 * конкретним ідентифікатором, а те, що приходить назад, лягає в
 * [PeerLiveInbox], де на людину припадає рівно одна точка.
 *
 * Годинник один на всі сеанси й крутиться ЛИШЕ поки хоч один живий: окремий
 * таймер на контакт означав би десяток прокидань замість одного, а таймер, що
 * цокає завжди, — батарею, витрачену на тишу.
 *
 * Меш сюди заходить лямбдами, а не типом: рівно шість дій, які сеансу потрібні
 * від дроту, — і завдяки цьому весь хаб перевіряється без радіо, Android і
 * реєстру ключів.
 */
class PeerLiveHub(
    private val points: PeerPointsRepository,
    private val roster: () -> List<PeerIdentity>,
    private val fix: () -> LocationFix?,
    /**
     * Чи взагалі дозволено міряти. Без дозволу фікса не буде НІКОЛИ, і це не
     * те саме, що «шукаю»: людині треба сказати різні речі.
     */
    private val fixAllowed: () -> Boolean,
    private val selfId: () -> String,
    private val addressed: () -> Set<String>,
    private val relayOpen: () -> Boolean,
    private val outbox: () -> List<PeerMessage>,
    private val send: (peerId: String, body: String) -> Unit,
    private val forget: (ids: Set<String>) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val _sessions = MutableStateFlow<List<PeerLiveShare.Session>>(emptyList())
    val sessions: StateFlow<List<PeerLiveShare.Session>> = _sessions.asStateFlow()

    /**
     * Те саме, готове для скла: імʼя й вирок «чи дійде» вже всередині.
     *
     * Окремо від [sessions], бо мапа не має права ані ходити в реєстр, ані
     * питати меш — вона малює речення, а не збирає його з трьох джерел.
     */
    private val _banners = MutableStateFlow<List<PeerLiveShare.Banner>>(emptyList())
    val banners: StateFlow<List<PeerLiveShare.Banner>> = _banners.asStateFlow()

    /**
     * Чи є ЩО надсилати — окремо від того, чи воно доїде.
     *
     * Картка контакта читає це поруч із сеансом: доки виміру немає, вона не
     * сміє казати «ділюсь положенням», бо не ділиться нічим.
     */
    private val _position = MutableStateFlow(PeerLiveShare.Position.SEARCHING)
    val position: StateFlow<PeerLiveShare.Position> = _position.asStateFlow()

    /**
     * Чужі маршрути на склі. Джерело ОДНЕ й воно тут: мапа не має права
     * перечитувати журнал розмови, бо тоді «свіжий лист» і «намальоване»
     * стають двома різними відповідями на одне питання.
     */
    private val _peerRoutes = MutableStateFlow<List<PeerLiveShare.RouteMark>>(emptyList())
    val peerRoutes: StateFlow<List<PeerLiveShare.RouteMark>> = _peerRoutes.asStateFlow()

    private var standing: Map<String, PeerLiveInbox.Standing> = emptyMap()
    private var routes: Map<String, PeerLiveInbox.StandingRoute> = emptyMap()
    private var published: Set<String> = emptySet()
    private var scope: CoroutineScope? = null
    private var pulse: Job? = null
    private var broom: Job? = null

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    fun sessionFor(peerId: String): PeerLiveShare.Session? =
        _sessions.value.firstOrNull { it.peerId == peerId }

    /**
     * Що ця людина зараз показує НАМ. Null — не ділиться, і екран мусить
     * сказати саме це, а не мовчати: тиша читається як поламане.
     */
    fun standingFor(peerId: String): PeerLiveInbox.Standing? =
        standing[peerId]?.takeIf { it.expiresAtMs > nowMs() }

    /**
     * Чи дійде — НАПЕРЕД. Без ключа дія мусить сказати чому, а не мовчки
     * покласти лист у чергу, з якої він не поїде ніколи.
     */
    fun reach(peerId: String): PeerLiveShare.Reach {
        val peer = roster().firstOrNull { it.id == peerId }
        return PeerLiveShare.reach(
            hasKey = peer != null && peer.dhPubKey.isNotBlank(),
            meshNow = peerId in addressed(),
            relayOpen = relayOpen(),
        )
    }

    fun start(peerId: String, span: PeerLiveShare.Span): PeerLiveShare.Reach {
        val verdict = reach(peerId)
        if (verdict == PeerLiveShare.Reach.NO_KEY) return verdict
        val at = nowMs()
        _sessions.value = _sessions.value.filterNot { it.peerId == peerId } +
            PeerLiveShare.open(peerId, span, at)
        // Перший тік — негайно: сеанс, що починається тишею на хвилину, людина
        // читає як «не працює», і має рацію. Виміру може ще не бути — тоді
        // перша репліка поїде з першим фіксом, а плашка доти нічого не обіцяє.
        pump(at)
        ensurePulse()
        return verdict
    }

    /**
     * Зупинка руками. Відклик іде листом, але точка на тому кінці однаково
     * помре від власного строку — на доставку відклику ніхто не покладається.
     */
    fun stop(peerId: String) {
        if (_sessions.value.none { it.peerId == peerId }) return
        _sessions.value = _sessions.value.filterNot { it.peerId == peerId }
        send(peerId, PeerLive.encodeStop(nowMs()))
        refreshBanners()
        sweepLog()
    }

    fun stopAll() {
        _sessions.value.map { it.peerId }.forEach(::stop)
    }

    /** Вхідний лист. Викликається з одного входу [AppContainer.onPeerMessage]. */
    fun absorb(message: PeerMessage) {
        val me = selfId()
        if (message.fromId.isBlank() || message.fromId == me) return
        // Маршрут — не службовий вантаж: він лишає рядок у розмові й приходить
        // сюди ЛИШЕ щоб мапа мала одне джерело замість другого читання журналу.
        if (PeerLive.isRoute(message.body)) {
            routes = PeerLiveInbox.absorbRoute(routes, message.fromId, message.body, nowMs())
            publishRoutes()
            rearmBroom()
            return
        }
        if (!PeerLive.isCargo(message.body)) return
        standing = PeerLiveInbox.absorb(standing, message.fromId, message.body, nowMs())
        publishPoints()
        sweepLog()
        rearmBroom()
    }

    /** Такт від споживача: гасить протухле і на склі, і в журналі. */
    fun sweep() {
        val at = nowMs()
        val kept = PeerLiveInbox.sweep(standing, at)
        if (kept.size != standing.size) {
            standing = kept
            publishPoints()
        }
        val keptRoutes = PeerLiveInbox.sweepRoutes(routes, at)
        if (keptRoutes.size != routes.size) {
            routes = keptRoutes
            publishRoutes()
        }
        sweepLog()
    }

    private fun ensurePulse() {
        if (pulse?.isActive == true) return
        val host = scope ?: return
        pulse = host.launch {
            while (_sessions.value.isNotEmpty()) {
                delay(PeerLiveShare.TICK_MS)
                pump(nowMs())
            }
        }
    }

    /**
     * Одна мітла на ВСІ строки — і положення, і маршрутів.
     *
     * Досі мітлу крутив лише шар маршрутів на мапі, тож контакт, що поділився
     * самим положенням, не вимітався звідси ніколи: строк гасив малюнок, а
     * запис лишався в памʼяті процесу — тобто чуже положення переживало власний
     * строк рівно там, куди ніхто не дивиться.
     *
     * Спить рівно до найближчого строку, а не цокає: між листом і його
     * спливом новини немає. Без строків мітли не існує взагалі.
     */
    private fun ensureBroom() {
        if (broom?.isActive == true) return
        val host = scope ?: return
        broom = host.launch {
            while (true) {
                val next = nextExpiryMs()
                if (next == null) {
                    // Строків у майбутньому немає — але це не те саме, що
                    // «нема чого мести»: мітла могла прокинутись уже після
                    // спливу, і тоді вийти мовчки означало б лишити труп.
                    if (standing.isNotEmpty() || routes.isNotEmpty()) sweep()
                    break
                }
                // Підлога — лише щоб строк, який уже минув, не крутив цикл без сну.
                delay((next - nowMs()).coerceAtLeast(BROOM_FLOOR_MS))
                sweep()
            }
        }
    }

    /** Перезавід ЗЗОВНІ: новий лист може принести строк ближчий за той, що спить. */
    private fun rearmBroom() {
        broom?.cancel()
        broom = null
        ensureBroom()
    }

    private fun nextExpiryMs(): Long? {
        val at = nowMs()
        val all = standing.values.map { it.expiresAtMs } + routes.values.map { it.route.expiresAtMs }
        return all.filter { it > at }.minOrNull()
    }

    /** Один прохід: закрити відбулі сеанси, надіслати те, що варте кадру. */
    private fun pump(at: Long) {
        val (over, live) = _sessions.value.partition { PeerLiveShare.over(it, at) }
        if (over.isNotEmpty()) _sessions.value = live
        // Строк САМ гасить точку на тому кінці, тож відклик після спливу
        // сеансу — зайвий кадр по радіо, а не додаткова гарантія.
        val here = heldFix()
        if (here != null) {
            var next = _sessions.value
            next.forEachIndexed { i, session ->
                if (!PeerLiveShare.shouldSend(session, here.lat, here.lon, at)) return@forEachIndexed
                val body = PeerLive.encodeFix(
                    PeerLive.Fix(
                        lat = here.lat,
                        lon = here.lon,
                        accuracyM = here.accuracyM?.takeIf { it > 0f }?.toInt(),
                        // Годинник супутників, якщо його чули: це і є мить виміру.
                        atMs = here.gpsTimeMs ?: at,
                        expiresAtMs = PeerLiveShare.expiryFor(session, at),
                        seq = session.seq,
                    ),
                ) ?: return@forEachIndexed
                send(session.peerId, body)
                next = next.toMutableList().also {
                    it[i] = PeerLiveShare.noteSent(session, here.lat, here.lon, at)
                }
            }
            _sessions.value = next
        }
        refreshBanners()
        sweepLog()
    }

    private fun heldFix(): LocationFix? = fix()?.takeIf { it.locatesTheDevice && !it.dead }

    /**
     * Вирок «чи дійде» перечитується на такті сеансу, не на кожен кадр: він
     * ходить у реєстр і в меш, а плашка перемальовується від будь-чого.
     */
    private fun refreshBanners() {
        val names = roster().associateBy { it.id }
        val where = when {
            heldFix() != null -> PeerLiveShare.Position.HELD
            !fixAllowed() -> PeerLiveShare.Position.NO_PERMISSION
            else -> PeerLiveShare.Position.SEARCHING
        }
        _position.value = where
        _banners.value = _sessions.value.map { s ->
            PeerLiveShare.Banner(
                peerId = s.peerId,
                name = names[s.peerId]?.name.orEmpty(),
                span = s.span,
                endsAtMs = s.endsAtMs,
                reach = reach(s.peerId),
                position = where,
                sent = s.lastSentAtMs > 0L,
            )
        }
    }

    /** Обігнане й протухле — геть із журналу, не лише з екрана. */
    private fun sweepLog() {
        val gone = PeerLiveInbox.spent(outbox(), nowMs())
        if (gone.isNotEmpty()) forget(gone)
    }

    /**
     * Маршрути — назовні готовим реченням.
     *
     * Імʼя береться з реєстру ТУТ, а не малюється як «маршрут від невідомого»:
     * лист приїхав від того, чий ключ у нас є, тож імʼя в реєстрі завжди є.
     */
    private fun publishRoutes() {
        val names = roster().associateBy { it.id }
        _peerRoutes.value = routes.values.map { r ->
            PeerLiveShare.RouteMark(
                peerId = r.peerId,
                // Ніколи порожнє: підпис «маршрут від » на землі — це підпис
                // ні про кого, а короткий ідентифікатор хоч щось називає.
                name = names[r.peerId]?.name?.takeIf { it.isNotBlank() }
                    ?: PeerRoster.shortId(r.peerId),
                route = r.route,
            )
        }
    }

    /**
     * Своє — вкидаємо, своє зникле — знімаємо поіменно.
     *
     * Стерти всю картину не можна: у тому самому сховищі лежать точки з веба,
     * і `replaceAll` прибрав би чужі піни разом зі своїми.
     */
    private fun publishPoints() {
        val names = roster().associateBy { it.id }
        (published - standing.keys).forEach(points::drop)
        published = standing.keys
        if (standing.isEmpty()) return
        points.upsertBatch(
            standing.values.map { s ->
                val who = names[s.peerId]
                PeerPoint(
                    nodeId = s.peerId,
                    threadId = s.peerId,
                    name = who?.name.orEmpty(),
                    lat = s.lat,
                    lon = s.lon,
                    atMs = s.atMs,
                    seq = s.seq,
                    accuracyM = s.accuracyM?.toFloat(),
                    verified = who?.isVerified == true,
                    share = PeerShare(ShareMode.LIVE, s.expiresAtMs),
                )
            },
        )
    }

    private companion object {
        const val BROOM_FLOOR_MS = 1_000L
    }
}
