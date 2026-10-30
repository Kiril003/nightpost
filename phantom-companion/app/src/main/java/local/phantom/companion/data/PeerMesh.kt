package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.net.peer.PeerBeacon
import local.phantom.companion.core.net.peer.PeerBlob
import local.phantom.companion.core.net.peer.PeerBlock
import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerChannel
import local.phantom.companion.core.net.peer.PeerEnvelope
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerHistory
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerLane
import local.phantom.companion.core.net.peer.PeerLink
import local.phantom.companion.core.net.peer.PeerMeet
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.net.peer.PeerPurge
import local.phantom.companion.core.net.peer.PeerRead
import local.phantom.companion.core.net.peer.PeerReceipt
import local.phantom.companion.core.net.peer.PeerRelay
import local.phantom.companion.core.net.peer.PeerRelayCourier
import local.phantom.companion.core.net.peer.PeerRelayPump
import local.phantom.companion.core.net.peer.PeerRelayTransport
import local.phantom.companion.core.net.peer.PeerRevise
import local.phantom.companion.core.net.peer.PeerSealing
import local.phantom.companion.core.net.peer.PeerSession
import local.phantom.companion.core.net.peer.PeerStranger
import local.phantom.companion.core.net.peer.PeerTyping
import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.core.net.peer.PeerWire
import local.phantom.companion.data.nearby.NearbyCourier
import local.phantom.companion.data.nearby.NearbyPlan
import local.phantom.companion.data.nearby.NoNearby

/** The only place that knows link, sealing, outbox, beacon and roster at once. */
class PeerMesh(
    private val roster: PeerRosterStore,
    /** The conversation on disk; null keeps the old in-memory behaviour. */
    private val log: PeerOutboxStore? = null,
    private val selfId: () -> String?,
    private val privateKey: () -> ByteArray?,
    private val publicKey: () -> String?,
    private val dhPrivateKey: () -> ByteArray?,
    private val dhPublicKey: () -> String?,
    /** What the operator called themselves. Read on IO, never in composition. */
    private val myName: suspend () -> String? = { null },
    private val beacon: PeerBeacon,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Чим ходити до сховка, або null — і тоді цей телефон у бік сховка не
     * робить нічого взагалі. Рішення за [PeerRelayGate], не за цим місцем.
     */
    private val relayOpen: (contacts: Int) -> PeerRelayTransport? = { null },
    /** Чи є мережа назовні. Без неї сховок не варто й пробувати. */
    private val online: () -> Boolean = { false },
    /**
     * Байти шматка — на диск, а в журнал — посилання. Null означає «нікуди
     * класти», і тоді все лишається як було: тіло зі своїми байтами в журналі.
     *
     * Два лямбди, а не `PeerFileStore`, з тієї самої причини, що й решта
     * залежностей тут: сітка не має знати ані про Android, ані про каталоги, і
     * її тести не мають заводити файлову систему, щоб надіслати слово.
     *
     * Повертає посилання, або null — байти не лягли. Викликач тоді лишає
     * справжнє тіло: дорожче, але ціле.
     */
    private val vaultChunk: ((conversationId: String, body: String) -> String?)? = null,
    /**
     * Посилання назад у справжнє тіло, за мить до конверта.
     *
     * Null тут — байтів немає НІДЕ, і це видима поразка листа, а не привід
     * надіслати порожнечу, яку той бік прийме за шматок.
     */
    private val openChunk: ((body: String) -> String?)? = null,
    /** Fires once per believed message; what happens next is not our business. */
    private val onArrived: (PeerMessage) -> Unit = {},
    /**
     * Кадр дзвінка, який щойно відчинився на цьому телефоні.
     *
     * Окремі двері, бо в нього окрема доля: він не лягає в журнал (там жив би
     * SDP, який нікому вдруге не потрібен), не показується рядком і не гримить
     * у шторці. Його справа — підняти апарат, а це вже не клопіт сітки.
     *
     * Кличеться на ВІДЧИНЕНОМУ й ПЕРЕВІРЕНОМУ листі: підпис звірено, адресат —
     * ми. Дзвінок від того, кого немає в реєстрі, сюди не дійде ніколи.
     */
    private val onCall: (PeerMessage) -> Unit = {},
    /** Положення людини: лише в памʼять приймача, повз журнал і повз [onArrived]. */
    private val onWhere: (PeerMessage) -> Unit = {},
    /** «Друкує…» — лише в памʼять приймача, як і [onWhere]. */
    private val onTyping: (PeerMessage) -> Unit = {},
    /** Кого не слухаємо. Питається на КОЖНИХ вхідних дверях, до ключів. */
    private val blocked: () -> Set<String> = { emptySet() },
    /** Рядок став надгробком; [line] — ще з тілом, щоб знайти байти вкладення. */
    private val onUnsent: (conversationId: String, line: PeerMessage) -> Unit = { _, _ -> },
    /** Купюра на телефон поруч, коли немає ні інтернету, ні спільної Wi-Fi. */
    private val nearby: NearbyCourier = NoNearby(),
    /**
     * Скільки пропозиція дзвінка сховком чекає, що апарат того боку її почув.
     * Сервер живий кадр лише пересилає тим, хто слухає двері, і нічого не каже
     * про те, чи слухав хтось; без відповіді «дзвонить» було б вигадкою.
     */
    private val liveAckWaitMs: Long = 8_000L,
) {

    private val channels = PeerChannel.Cache()

    /**
     * The key for each live meeting. Nothing here is ever written down: that
     * absence is the whole forward-secrecy claim, and it is enforced by there
     * being no code path that could persist it.
     */
    private val sessions = PeerSession.Cache()

    /**
     * Що ми знаємо про домовляння ключів з кожним. Рішення — у [PeerMeet].
     *
     * Тут лежить лише швидкоплинне: коли востаннє відмовили і скільки разів
     * поспіль промовчали. Єдиний засвідчений факт — сама зустріч — живе в
     * реєстрі, бо мусить пережити перезапуск.
     *
     * Відкат на довічний ключ пари не має дати заходу, і причина не в
     * обережності. Захід безпечний лише тоді, коли видно, що оновлення справді
     * доходять до людей, а сьогодні вони не доходять: релізні збірки їдуть з
     * versionCode 1, тож картка оновлення не спрацьовує, і завантаження ще й
     * за ліцензією. Відмовити v3 до того — це залишити екіпажі в тиші.
     */
    private val meetState = java.util.concurrent.ConcurrentHashMap<String, PeerMeet.Known>()

    private val held = PeerOutboxHolder(log?.load().orEmpty()) { log?.save(it) }
    val outbox: StateFlow<List<PeerMessage>> = held.messages

    /** The one door: nothing reaches the flow without also being written down. */
    private fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) =
        held.publish(transform)

    /** Другий шлях: коли поруч нікого, слово йде через сховок. */
    private val relay = PeerMeshRelay(
        whoAmI = { cachedId },
        decoySecretOf = { cachedDhKey?.let(PeerRelay::decoySecret) },
        // Скриньку заблокованого не відчиняємо зовсім: жодного розшифрування,
        // жодної квитанції, лист лежить у сховку як недоставлений.
        roster = { blocked().let { no -> roster.peers.value.map { it.id }.filterNot { it in no } } },
        pairKeyOf = { id -> roster.peers.value.firstOrNull { it.id == id }?.let(::channelFor) },
        signingKeyOf = { id ->
            roster.peers.value.firstOrNull { it.id == id }?.let { peer ->
                runCatching { java.util.Base64.getDecoder().decode(peer.pubKey) }.getOrNull()
            }
        },
        readLog = { outbox.value },
        writeLog = ::publish,
        store = ::keepArrived,
        open = { relayOpen(it) },
        nowMs = { nowMs() },
        onReconnect = { wakeOutbox(); wake() },
        fsSeal = { m -> relayFsSeal(m) },
        fsOpen = { raw, peerId -> relayFsOpen(raw, peerId) },
        fsLanded = { raw, peerId -> relayFsKeys?.let { local.phantom.companion.core.net.peer.RelayFsMail.landed(raw, peerId, it) } },
        door = { cachedDoor },
        // "" — нашим ключем не відчиняється: сміття, палиться; null — ключа ще немає.
        openDoorWith = { blob, tag -> cachedDhKey?.let { PeerRelay.openDoor(blob, it, tag).orEmpty() } },
        doorIn = ::onDoor,
        liveIn = { raw -> acceptRelayed(raw) },
    )

    /**
     * Лист зі сховка заходить тими самими дверима, що й лист із дроту, і
     * повертає правду ЛИШЕ коли він справді на диску: від цієї відповіді
     * залежить, чи дозволено спалити копію на сервері.
     */
    private fun keepArrived(message: PeerMessage): Boolean {
        if (PeerBlock.refuses(blocked(), message)) return false
        // Підробку помічника не зберігаємо, але й не просимо сховок тримати її далі.
        if (PeerCard.isAssistantOnly(message.body)) return true
        // Кадр дзвінка зі сховка буває ДВОХ різних родів, і доти їх плутали.
        //
        // Лист лежить у сховку до семи діб, тож кадр віком три години — справді
        // спогад: будити ним апарат означало б дзвонити з позавчора. Але кадр
        // віком три СЕКУНДИ — це дзвінок, який просто не знайшов прямої дороги,
        // і спалювати його мовчки означало рівно те, на що скаржився власник:
        // «воно навіть не дзвонить».
        //
        // Межу не треба вигадувати, вона вже оголошена: [PeerCall.RING_LIFE_MS].
        // Стільки живе сам виклик — після цього [PeerCallLine] гасить його і в
        // того, хто набрав. Кадр, молодший за власне життя дзвінка, за
        // означенням ще дзвінок; старший — уже ні.
        //
        // Правда повертається true в обох випадках: копію на сервері можна
        // палити однаково, інакше вона приходила б щоразу, поки не спливе.
        if (PeerCall.isCall(message.body)) {
            roster.sawIt(message.fromId, nowMs())
            val age = nowMs() - message.sentAtMs
            callTrace("mailbox", message)
            if (age in 0 until PeerCall.RING_LIFE_MS) {
                runCatching { onCall(message) }
            }
            return true
        }
        if (PeerWhere.isWhere(message.body)) {
            roster.sawIt(message.fromId, nowMs())
            runCatching { onWhere(message) }
            return true
        }
        // Сховком друк не їздить; такий кадр — чужа збірка або старий, і він
        // уже неправда.
        if (PeerTyping.isTyping(message.body)) return true
        if (CashRepeat.seen(outbox.value, message)) return true
        val known = outbox.value.any { it.id == message.id }
        publishRevised { PeerOutbox.enqueue(it, vaultArrived(message)) }
        if (outbox.value.none { it.id == message.id }) return false
        if (!known && !landedOnDisk(message.id)) return false
        if (!known) {
            roster.sawIt(message.fromId, nowMs())
            noteIntroduced(message.fromId)
            // Перед [onArrived]: плашка про груповий лист читає імʼя з реєстру,
            // і ключ, покладений після неї, спізнився б рівно на те сповіщення,
            // заради якого його й везли.
            runCatching { learnKeys(message) }
            runCatching { answerHistory(message) }
            runCatching { onArrived(message) }
        }
        return true
    }

    // Шторка й спалена копія на сервері — обіцянка, що лист переживе смерть
    // процесу. Не ліг на диск — геть і з екрана: відправник або сховок повторять.
    private fun landedOnDisk(id: String): Boolean {
        if (held.durable(id)) return true
        publish { log -> log.filterNot { it.id == id } }
        return false
    }

    /** A flow, not a function: building it costs two Keystore unwraps and an interface walk. */
    private val _linkNarrow = MutableStateFlow(false)

    /** Слабкий звʼязок до сховка: текст іде першим, файли чекають ширшої дороги. */
    val linkNarrow: StateFlow<Boolean> = _linkNarrow.asStateFlow()

    private val _myInvite = MutableStateFlow<PeerInvite.Invite?>(null)
    val myInvite: StateFlow<PeerInvite.Invite?> = _myInvite.asStateFlow()

    /** Each accessor unwraps the Keystore; the key does not change while we run. */
    @Volatile private var cachedId: String? = null

    /** Хто ми — читається раз при старті сітки, а не окремо кожним екраном. */
    private val _selfIdFlow = MutableStateFlow("")
    val selfIdFlow: StateFlow<String> = _selfIdFlow.asStateFlow()

    @Volatile private var cachedKey: ByteArray? = null

    @Volatile private var cachedDhKey: ByteArray? = null

    /** [PeerRelay.doorKey] цього телефона. Складається разом з особистістю, на IO. */
    @Volatile private var cachedDoor: ByteArray? = null

    /**
     * Where each known peer was last seen: host AND the port it advertised.
     * Concurrent because discovery, accept and the pump all touch it from
     * different threads — it always did, without saying so.
     */
    private val addresses = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()

    /**
     * Whose messages have a DIRECT route — a host and port seen on this LAN.
     *
     * Не «кому взагалі є куди»: лист до людини, якої тут немає, тепер іде в
     * сховок і чекає там. Порожньо тут означає лише «поруч не видно», і екран
     * має говорити саме це, а не «нікуди».
     */
    private val _addressed = MutableStateFlow<Set<String>>(emptySet())
    val addressed: StateFlow<Set<String>> = _addressed.asStateFlow()

    /** Frames that arrived for us and could not be opened. See [PeerStranger]. */
    private val _strangers = MutableStateFlow<List<PeerStranger>>(emptyList())
    val strangers: StateFlow<List<PeerStranger>> = _strangers.asStateFlow()

    /** The one door onto [addresses], so nothing can learn a route in silence. */
    private fun rememberAddress(peerId: String, host: String, port: Int) {
        if (host.isBlank() || port !in 1..65_535) return
        addresses[peerId] = host to port
        _addressed.value = addresses.keys.toSet()
    }

    /**
     * Скільки разів поспіль нас не впустили. Швидкоплинне: засвідчений факт —
     * що наш рядок у них є — живе в реєстрі.
     */
    private val introState = java.util.concurrent.ConcurrentHashMap<String, PeerIntro.Known>()

    private val link = PeerLink(
        // Ephemeral: the beacon advertises whatever the OS gives us.
        port = 0,
        onMessage = ::accept,
        onHello = ::greet,
        onIntro = ::introduced,
    )

    private var pump: Job? = null
    private var scan: Job? = null
    @Volatile private var activeScope: CoroutineScope? = null

    /** Служба підняла сітку. False — система її не пустила або вбила, і черга стоїть. */
    val running: Boolean get() = activeScope != null

    fun start(scope: CoroutineScope) {
        activeScope = scope
        val me = selfId() ?: return
        meetState.clear()
        seedAddresses()
        link.start(scope)
        scope.launch {
            // Advertise the port actually bound, once it is.
            repeat(BIND_WAIT_TRIES) {
                if (link.boundPort > 0) {
                    beacon.advertise(me, link.boundPort)
                    // Код запрошення несе цей порт. Якщо сітку щойно підняли з
                    // екрана «Люди», перший код склався б з портом 0.
                    refreshInvite()
                    return@launch
                }
                delay(BIND_WAIT_MS)
            }
        }
        if (scan?.isActive != true) rescan()
        scope.launch {
            loadIdentity()
            refreshInvite()
            logInviteForDebug()
        }
        wasOnline = online()
        if (pump?.isActive != true) {
            pump = scope.launch {
                var introducing: Job? = null
                while (isActive) {
                    noticeNetwork()
                    // Сховок першим: він дешевий, а пряма дорога може чекати сокета.
                    if (online()) relay.collectIfDue()
                    _linkNarrow.value = online() && relay.narrow()
                    // Не чекаємо: лист, що вже їде, інший такт не візьме ([inFlight]).
                    launch { flush() }
                    if (introducing?.isActive != true) introducing = launch { introduceMyself() }
                    if (nowMs() - scannedAtMs >= RESCAN_MS) rescan()
                    withTimeoutOrNull(PUMP_INTERVAL_MS) { wakeup.receive() }
                }
            }
        }
    }

    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    /** Такт насоса зараз, а не через [PUMP_INTERVAL_MS]. */
    fun wake() {
        wakeup.trySend(Unit)
    }

    /** Людина повернулась до месенджера: усе, що чекало паузи, — зараз. */
    fun onForeground() {
        reconnected()
        if (!online()) nearby.listen()
    }

    @Volatile private var wasOnline = false
    @Volatile private var scannedAtMs = 0L

    private fun noticeNetwork() {
        val now = online()
        if (now && !wasOnline) reconnected()
        wasOnline = now
    }

    private fun reconnected() {
        relay.reprobe()
        wakeOutbox()
        rescan()
        wake()
    }

    private fun wakeOutbox() {
        val now = nowMs()
        if (PeerOutbox.wake(outbox.value, now) !== outbox.value) publish { PeerOutbox.wake(it, now) }
    }

    /**
     * mDNS наново. Зникнення сервісу Android повідомляє не завжди, а свіжий
     * пошук заново називає тих, хто справді тут.
     */
    private fun rescan() {
        val scope = activeScope ?: return
        scannedAtMs = nowMs()
        scan?.cancel()
        scan = scope.launch {
            beacon.discover().collect { found ->
                if (found.lost) {
                    forgetAddresses(found)
                    return@collect
                }
                // A stranger's address is not a contact and is not stored as one.
                if (roster.peers.value.any { it.id == found.peerId }) {
                    rememberAddress(found.peerId, found.host, found.port)
                    // mDNS is this LAN by construction — the one touch
                    // point allowed to claim «поруч», not just «жива».
                    roster.sawNearby(found.peerId, nowMs())
                }
            }
        }
    }

    /** Зник із мережі — і пряма дорога до нього закрита одразу, а не через 90 с. */
    private fun forgetAddresses(lost: PeerBeacon.Found) {
        val gone = addresses.keys.filter { lost.names(it) }
        if (gone.isEmpty()) return
        gone.forEach { addresses.remove(it) }
        _addressed.value = addresses.keys.toSet()
    }

    fun stop() {
        cachedId = null
        cachedKey = null
        cachedDhKey = null
        cachedDoor = null
        doorOffers.clear()
        doorKnocked.clear()
        doorTriedAt.clear()
        liveAcks.clear()
        channels.clear()
        sessions.clear()
        meetState.clear()
        introState.clear()
        relay.close()
        beacon.stopAdvertising()
        link.stop()
        pump?.cancel()
        scan?.cancel()
        pump = null
        scan = null
        activeScope = null
    }

    /**
     * Примусовий забір зі сховка негайно (наприклад при відкритті чату).
     *
     * Повертає, чи справді пішов забір. `activeScope` ставиться лише у
     * [start], тож при зупиненій службі виклик мовчки не робив НІЧОГО —
     * сьогодні нешкідливо (сховок опитують і за розкладом), завтра обдурить
     * того, хто на нього покладеться. Хай або робить, або каже.
     */
    fun pollRelayNow(): Boolean {
        val scope = activeScope ?: return false
        reconnected()
        scope.launch(Dispatchers.IO) { relay.collect() }
        return true
    }

    /** Writes it down first, then tries. The order is the whole promise. */
    fun send(
        toId: String,
        body: String,
        replyToId: String = "",
        quiet: Boolean = false,
        fw: PeerMessage.Forward? = null,
        qt: String = "",
    ): PeerMessage? {
        val me = cachedId ?: return null
        val key = cachedKey ?: return null
        val message = PeerSealing.seal(
            PeerMessage(
                id = java.util.UUID.randomUUID().toString(),
                fromId = me,
                toId = toId,
                body = body,
                sentAtMs = nowMs(),
                replyToId = replyToId,
                quiet = quiet,
                fw = fw,
                qt = if (replyToId.isBlank()) "" else qt,
            ),
            key,
        )
        publish { PeerOutbox.enqueue(it, message) }
        activeScope?.launch { flush() }
        return message
    }

    /**
     * Кадр дзвінка — зараз або ніяк: LAN, а без нього живий канал сховка в
     * двері адресата. НЕ [send]: у черзі лист живе годинами, а дзвінок мертвий
     * за [PeerCall.RING_LIFE_MS], тож у журнал не пишеться нічого й повторів
     * немає. Завеликий для живого каналу кадр лягає в двері листом — приймач
     * дзвонить лише на кадр, молодший за життя дзвінка.
     *
     * True по LAN — квитанція; по сховку — кадр пішов у канал. False — «не
     * доїхало», і поверхня мусить сказати саме це.
     */
    suspend fun sendCallFrame(toId: String, body: String): Boolean {
        if (!PeerCall.isCall(body)) return false
        return sendNow(toId, body)
    }

    /** Як [sendCallFrame]: зараз або ніяк. Заблокованому не йде навіть «друкую». */
    suspend fun sendTyping(toId: String, body: String): Boolean {
        if (!PeerTyping.isTyping(body) || toId in blocked()) return false
        return sendNow(toId, body)
    }

    private suspend fun sendNow(toId: String, body: String): Boolean {
        val me = cachedId ?: return false
        val signing = cachedKey ?: return false
        val recipient = roster.peers.value.firstOrNull { it.id == toId } ?: return false
        val channel = channelFor(recipient) ?: return false
        val message = PeerSealing.seal(
            PeerMessage(
                id = java.util.UUID.randomUUID().toString(),
                fromId = me,
                toId = toId,
                body = body,
                sentAtMs = nowMs(),
            ),
            signing,
        )
        // Той самий вирок про «поруч», що й у [flush].
        val where = addresses[toId]?.takeIf { recipient.isNearby(nowMs()) }
            ?: return viaLive(recipient, message, channel).also { callTrace("sent-live=$it", message) }
        if (sendDirect(recipient, message, channel, where)) return true
        // «Поруч» живе 90 с після останнього маяка: людина вийшла з Wi-Fi, а
        // кожна спроба в ці 90 с билась у мовчазний порт і не дзвонила.
        return viaLive(recipient, message, channel).also { callTrace("sent-live-after-link=$it", message) }
    }

    private suspend fun sendDirect(
        recipient: PeerIdentity,
        message: PeerMessage,
        channel: ByteArray,
        where: Pair<String, Int>,
    ): Boolean {
        val toId = recipient.id
        val meeting = meetWith(recipient, where)
        if (meeting.act == PeerMeet.Act.HOLD) return false
        val session = meeting.session
        val sealed = if (session != null) {
            PeerEnvelope.sealV4(message, session.sid, session.key)
        } else {
            PeerEnvelope.seal(message, channel)
        } ?: return false
        val key = session?.key ?: channel
        val receipt = PeerReceipt.of(
            key, message.id, message.fromId, message.toId, sealed.nonceB64,
        ) ?: return false
        val landed = link.send(
            host = where.first,
            peerPort = where.second,
            receipt = receipt,
            json = sealed.frame,
        ) is PeerLink.Outcome.Acked
        callTrace("sent-link=$landed", message)
        if (landed) {
            noteIntroduced(toId)
        } else if (session != null) {
            // Могли перезапуститись і забути зустріч — як і в [flush]. Повтору
            // тут немає, але наступний кадр цього ж дзвінка домовиться заново.
            sessions.forgetSid(session.sid)
        }
        return landed
    }

    /**
     * Чи слухач справді піднятий.
     *
     * Оголошене поверхнею «вхідні приймаються» мусить бути ВИМІРОМ: сітка може
     * бути не запущена, і тоді до цього телефона не доїде ніщо.
     */
    val isListening: Boolean get() = link.isListening

    /**
     * v3 під ключем пари: ключ зустрічі домовляється лише сокетом LAN.
     *
     * Пропозиція дзвінка вважається такою, що дійшла, ЛИШЕ коли апарат того
     * боку відповів квитанцією ([LIVE_HEARD]): сервер пересилає живий кадр тим,
     * хто зараз слухає двері, і мовчить, якщо не слухає ніхто. Без цього
     * заблокований екран чи вимкнений телефон давали б «дзвонить» на хвилину.
     * Копії «на потім» пропозиція теж не лишає: тоді вона задзвонила б, коли
     * той, хто набирав, уже почув «не доїхало» й поклав слухавку.
     */
    private suspend fun viaLive(recipient: PeerIdentity, message: PeerMessage, channel: ByteArray): Boolean {
        if (recipient.dhPubKey.isBlank()) return false
        val sealed = PeerEnvelope.seal(message, channel) ?: return false
        val offer = PeerCall.decodeSignal(message.body)?.what == PeerCall.Signalling.OFFER
        val receipt = if (offer) {
            PeerReceipt.of(channel, message.id, message.fromId, message.toId, sealed.nonceB64) ?: return false
        } else {
            null
        }
        val heard = receipt?.let { kotlinx.coroutines.CompletableDeferred<Unit>().also { d -> liveAcks[it] = d } }
        try {
            val way = relay.sendLive(recipient.dhPubKey, recipient.id, sealed.frame)
            callTrace("live-out=$way bytes=${sealed.frame.length}", message)
            val gone = when (way) {
                PeerRelayCourier.Live.SENT -> true
                PeerRelayCourier.Live.TOO_BIG -> relay.dropDoor(recipient.dhPubKey, recipient.id, sealed.frame)
                PeerRelayCourier.Live.NO_CHANNEL, PeerRelayCourier.Live.REFUSED -> false
            }
            if (!gone || heard == null) return gone
            return withTimeoutOrNull(liveAckWaitMs) { heard.await() } != null
        } finally {
            receipt?.let { liveAcks.remove(it) }
        }
    }

    /** Квитанції живих пропозицій, на які ще чекають: квитанція → хто чекає. */
    private val liveAcks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

    /**
     * Живі кадри дзвінка й друку, які вже дійшли: id → коли. Двері рахує
     * кожен, хто бачив код, тож підслуханий кадр можна прислати вдруге; без
     * цього обліку апарат дзвонив би «від Ані», коли захоче чужий.
     */
    private val heardLive = object : LinkedHashMap<String, Long>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > HEARD_LIVE_KEPT
    }

    // Debug: одна розмова дає повну хронологію кадрів на обох апаратах.
    private fun callTrace(path: String, message: PeerMessage) {
        if (!local.phantom.companion.BuildConfig.DEBUG || !PeerCall.isCall(message.body)) return
        val kind = PeerCall.decodeSignal(message.body)?.what?.mark ?: "?"
        runCatching { android.util.Log.i("PhantomCall", "frame $path $kind age=${nowMs() - message.sentAtMs}ms") }
    }

    private fun firstHeard(id: String): Boolean = synchronized(heardLive) { heardLive.put(id, nowMs()) == null }

    /** Чи є куди дзвонити ПРЯМО зараз — те саме питання, що й у [sendCallFrame]. */
    fun canCall(toId: String): Boolean {
        val recipient = roster.peers.value.firstOrNull { it.id == toId } ?: return false
        if (recipient.isNearby(nowMs()) && addresses.containsKey(toId)) return true
        return recipient.dhPubKey.isNotBlank() && relay.liveReady()
    }

    /**
     * Те саме, але для НАБРАНОГО людиною.
     *
     * [send] лишається входом пристрою — ним ідуть маршрут, місце, шматки файла
     * й відповіді, які склав телефон. Через цей вхід їде те, що набрали пальцями,
     * і тільки тут рядок судять на походження: тіло, яке починається як кадр
     * пристрою, не їде взагалі, і людині кажуть чому.
     *
     * Не екранування й не тиха підміна набраного: підмінений рядок — це вже не
     * те, що людина написала, а вона про це не дізнається.
     */
    fun sendTyped(
        toId: String,
        body: String,
        replyToId: String = "",
        quiet: Boolean = false,
        fw: PeerMessage.Forward? = null,
        qt: String = "",
    ): PeerFrames.Typed {
        PeerFrames.refuseTyped(body)?.let { return PeerFrames.Typed.Refused(it) }
        return PeerFrames.Typed.Sent(listOfNotNull(send(toId, body, replyToId, quiet, fw, qt)))
    }

    /**
     * Набране в групі. Судиться САМ текст, а не конверт, який навколо нього
     * складе [PeerGroup.encode]: рядок з початком дії над складом поїхав би
     * командою «вийшов» чи «додав» від імені того, хто його набрав.
     */
    fun sendTypedToGroup(
        group: PeerGroup.Group,
        text: String,
        replyToId: String = "",
        quiet: Boolean = false,
        mentions: List<String> = emptyList(),
        fw: PeerMessage.Forward? = null,
        qt: String = "",
    ): PeerFrames.Typed {
        PeerFrames.refuseTyped(text)?.let { return PeerFrames.Typed.Refused(it) }
        return PeerFrames.Typed.Sent(sendToGroup(group, text, replyToId, quiet, mentions = mentions, fw = fw, qt = qt))
    }

    /**
     * Багато листів однією дією — і одним записом на диск.
     *
     * Журнал шифрується й переписується ЦІЛКОМ на кожне [publish], і пише той
     * самий потік, що й кличе. Тож 171 шматок одного фото, покладений
     * поодинці, — це 171 шифрування всього журналу, кожне довше за попереднє.
     * Тут вони заходять разом, тому й запис один.
     *
     * Мить у [sentAtMs] зростає на крок, а не спільна для всіх: [PeerOutbox.due]
     * впорядковує чергу саме нею, і однакова мітка лишила б порядок шматків на
     * ласку сортування.
     */
    fun sendMany(toId: String, bodies: List<String>): List<PeerMessage> {
        if (bodies.isEmpty()) return emptyList()
        val me = cachedId ?: return emptyList()
        val key = cachedKey ?: return emptyList()
        val at = nowMs()
        val sealed = bodies.mapIndexed { i, body ->
            val letter = PeerMessage(
                id = java.util.UUID.randomUUID().toString(),
                fromId = me,
                toId = toId,
                body = body,
                sentAtMs = at + i,
            )
            // ПІДПИС КЛАДЕТЬСЯ НА СПРАВЖНЄ ТІЛО, і лише потім тіло міняється на
            // посилання. Порядок не косметичний: [PeerSealing.canonical] підписує
            // саме body, тож посилання, підписане замість байтів, не перевірилось
            // би на тому кінці — а перевірка підпису тут і є вся заява про
            // авторство. Так посилання лишається СПОСОБОМ ЗБЕРІГАННЯ: у дріт
            // однаково їде те, що підписано.
            val signed = PeerSealing.seal(letter, key)
            // Байти шматка з журналу — у файлове сховище. Не вдалось покласти
            // (немає місця, немає каталогу) — лист лишається зі справжнім
            // тілом: дорожче, але ціле. Мовчки втратити шматок гірше.
            val stored = vaultChunk?.invoke(toId, body)
            if (stored != null) signed.copy(body = stored) else signed
        }
        publish { current -> sealed.fold(current) { acc, m -> PeerOutbox.enqueue(acc, m) } }
        activeScope?.launch { flush() }
        return sealed
    }

    /**
     * Sealed per recipient — a signature that did not name who it was for could
     * be replayed at anyone else in the group. One instant for all copies, so
     * the chat can fold them back into the one line that was typed.
     */
    fun sendToGroup(
        group: PeerGroup.Group,
        text: String,
        replyToId: String = "",
        quiet: Boolean = false,
        only: Set<String>? = null,
        mentions: List<String> = emptyList(),
        fw: PeerMessage.Forward? = null,
        qt: String = "",
    ): List<PeerMessage> {
        val me = cachedId ?: return emptyList()
        val key = cachedKey ?: return emptyList()
        val body = PeerGroup.encode(group, text)
        val named = mentions.filter { it in group.memberIds && it != me }.distinct().take(MAX_MENTIONS)
        val at = nowMs()
        val targets = PeerGroup.fanOut(group, me, only).ifEmpty { listOf(me) }
        val log = outbox.value
        val sealed = targets.map { toId ->
            PeerSealing.seal(
                PeerMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    fromId = me,
                    toId = toId,
                    body = body,
                    sentAtMs = at,
                    replyToId = PeerGroup.replyForRecipient(log, group.id, replyToId, toId),
                    state = if (toId == me) PeerMessage.State.DELIVERED else PeerMessage.State.QUEUED,
                    quiet = quiet,
                    mentions = named,
                    fw = fw,
                    qt = if (replyToId.isBlank()) "" else qt,
                ),
                key,
            )
        }
        if (sealed.isEmpty()) return emptyList()
        publish { current -> sealed.fold(current) { acc, m -> PeerOutbox.enqueue(acc, m) } }
        activeScope?.launch { flush() }
        return sealed
    }

    /**
     * Зміна складу або назви — звичайний підписаний лист групи, а не місцева
     * правка: склад їде в кожному листі, тож інакше два телефони розійшлись би
     * назавжди, і жоден з них про це не дізнався б.
     *
     * Летить і тому, кого прибрали, і тому, кого щойно додали: перший має право
     * знати, а другий інакше не дізнається про групу взагалі — саме цей лист її
     * йому й складає.
     */
    fun sendGroupAct(
        group: PeerGroup.Group,
        act: PeerGroup.Act,
        newName: String = group.name,
    ): List<PeerMessage> {
        val me = cachedId ?: return emptyList()
        val key = cachedKey ?: return emptyList()
        // Конверт доклеюється ТУТ, а не в того, хто кличе: екранів, що додають
        // людину, більше одного, і кожен забув би його по-своєму — мовчки, бо
        // без ключів «додати» виглядає зробленим і лише не працює.
        val carried = if (act is PeerGroup.Act.Add && act.invite.isBlank()) {
            act.copy(invite = envelopeFor(act.id))
        } else {
            act
        }
        val next = PeerGroup.apply(group, me, carried, newName)
        val body = PeerGroup.encode(next, PeerGroup.encodeAct(carried))
        val at = nowMs()
        val recipients = (group.memberIds + next.memberIds)
            .distinct()
            .filterNot { it == me }
            .take(PeerGroup.MAX_SPACE_MEMBERS)
            .ifEmpty { listOf(me) }
        val sealed = recipients.map { toId ->
            PeerSealing.seal(
                PeerMessage(
                    id = java.util.UUID.randomUUID().toString(),
                    fromId = me,
                    toId = toId,
                    body = body,
                    sentAtMs = at,
                    state = if (toId == me) PeerMessage.State.DELIVERED else PeerMessage.State.QUEUED,
                ),
                key,
            )
        }
        publish { current -> sealed.fold(current) { acc, m -> PeerOutbox.enqueue(acc, m) } }
        if (carried is PeerGroup.Act.Add) sendRoster(next, carried.id)
        activeScope?.launch { flush() }
        return sealed
    }

    /**
     * Ключі всіх, хто вже в гілці, — тому, кого щойно додали.
     *
     * Другий бік стіни. Акт «додай X» доносить ключі X решті; без цього X
     * бачив би склад із тридцяти людей і не міг написати НІКОМУ: кожна копія
     * вмирає в [flush] ще до сокета, бо запечатати її нема чим.
     *
     * Один [sendMany] на всіх, а не лист на людину: журнал шифрується й
     * переписується цілком на кожен запис, тож тридцять один окремий лист — це
     * тридцять один перезапис усього журналу, кожен довший за попередній.
     */
    private fun sendRoster(group: PeerGroup.Group, newcomerId: String) {
        val me = cachedId ?: return
        val bodies = group.memberIds
            .asSequence()
            .filterNot { it == newcomerId }
            .mapNotNull { id ->
                val envelope = if (id == me) myEnvelope() else envelopeFor(id)
                envelope.takeIf { it.isNotBlank() }?.let { id to it }
            }
            .take(PeerGroup.MAX_SPACE_MEMBERS)
            .map { (id, envelope) -> PeerGroup.encode(group, PeerGroup.encodeRoster(id, envelope)) }
            .toList()
        // Обличчя й опис гілки їдуть ТУТ, новачкові особисто, а не віяром на
        // всіх. У заголовку їх немає за побудовою ([PeerGroup.Group.about]),
        // тож без цього доданий бачив би гілку без обличчя, доки хтось із
        // адмінів випадково не змінив би його вдруге, — а «поставив аватар»
        // тихо означало б «поставив собі». Ціна: два кадри одній людині
        // замість 31 копії кожного.
        val kind = PeerGroup.stateOf(outbox.value, group.id)?.setting(PeerGroup.Setting.KIND)
        val card = PeerGroup.newcomerCard(group, kind).map { PeerGroup.encode(group, PeerGroup.encodeAct(it)) }
        val all = bodies + card
        if (all.isNotEmpty()) sendMany(newcomerId, all)
    }

    /**
     * Безадресний конверт члена гілки з реєстру, або порожньо.
     *
     * Імʼя й адреса зрізаються [PeerInvite.envelopeOf]: у гілці не їде жодного
     * людського рядка, а адреса, за якою піде чужий телефон, — це вибір
     * маршруту за нього. Без ключа обміну конверта немає взагалі: порожній
     * `dhPubKey` запечатав би листи значенням, яке знають усі.
     */
    private fun envelopeFor(id: String): String {
        val peer = roster.peers.value.firstOrNull { it.id == id } ?: return ""
        if (peer.pubKey.isBlank() || peer.dhPubKey.isBlank()) return ""
        return PeerInvite.envelopeOf(
            PeerInvite.Invite(
                peerId = peer.id,
                name = "",
                pubKey = peer.pubKey,
                dhPubKey = peer.dhPubKey,
                host = "",
                port = 0,
            ),
        )
    }

    private fun myEnvelope(): String =
        _myInvite.value?.let { PeerInvite.envelopeOf(it) }.orEmpty()

    /**
     * Попросити минуле в тих, хто в гілці вже був.
     *
     * Прохання їде тілом звичайного групового листа — гілка не має місця, є
     * лише люди в ній. Хто відповість, вирішує кожен телефон сам: відповідей
     * буде стільки, скільки живих учасників, і дедуп у [PeerHistory.retold]
     * складений саме на це.
     */
    fun askHistory(group: PeerGroup.Group, sinceMs: Long = 0L): List<PeerMessage> {
        val me = cachedId ?: return emptyList()
        if (me !in group.memberIds) return emptyList()
        return sendToGroup(group, PeerHistory.encodeAsk(sinceMs))
    }

    /**
     * Відповідь на прохання про минуле: пакети переказаних рядків — ТІЛЬКИ
     * тому, хто просив.
     *
     * Не розсилка: минуле, розіслане всій гілці, приїхало б тридцяти людям,
     * які його й так мають. Відповідає кожен сам за себе, і саме тому в пакеті
     * немає підпису авторів — лише підпис того, хто переказує ([PeerHistory]).
     */
    private fun answerHistory(message: PeerMessage) {
        val me = cachedId ?: return
        if (message.fromId == me) return
        val seen = PeerGroup.decode(message.body) ?: return
        // Просити минуле гілки може лише той, хто в ній: інакше склад листа
        // сам собі видавав би перепустку в чужу розмову.
        if (message.fromId !in seen.group.memberIds || me !in seen.group.memberIds) return
        val since = PeerHistory.decodeAsk(seen.text) ?: return
        val known = PeerGroup.stateOf(outbox.value, seen.group.id)
        if (!PeerGroup.headerFits(seen, known)) return
        if (known?.setting(PeerGroup.Setting.HISTORY) == PeerGroup.OFF) return
        val lines = PeerHistory.linesOf(outbox.value, seen.group.id, since)
        if (lines.isEmpty()) return
        val packs = PeerHistory.packs(seen.group.id, lines)
        if (packs.isNotEmpty()) sendMany(message.fromId, packs)
    }

    /**
     * ЄДИНІ двері, якими чужий ключ із гілки потрапляє в реєстр.
     *
     * Три відмови, і жодна з них не зайва:
     *
     *  * склад засвідчує лише той, хто в ньому сам — інакше будь-хто дописував
     *    би ключі в чужий реєстр одним листом, і склад із заголовка того ж
     *    листа був би йому за доказ;
     *  * засув прибраного дріт не знімає ніколи ([accept] — єдине, що його
     *    знімає), інакше «прибрати» не означало б нічого: прибраний вернувся б
     *    у реєстр першим же актом складу від будь-кого;
     *  * наявний ключ не перебивається. Джерело тут [PeerIdentity.KeySource.REMOTE]
     *    — найслабше з усіх; звірений камерою ключ, затертий переказаним,
     *    втратив би звіреність мовчки, і на екрані це виглядало б як завжди.
     */
    private fun learnKeys(message: PeerMessage) {
        val me = cachedId
        val seen = PeerGroup.decode(message.body) ?: return
        if (message.fromId !in seen.group.memberIds) return
        val invite = (PeerGroup.decodeAct(seen.text) as? PeerGroup.Act.Add)?.let(PeerGroup::invited)
            ?: PeerGroup.decodeRoster(seen.text)?.let(PeerGroup::invited)
            ?: return
        if (invite.peerId == me || invite.peerId == message.fromId) return
        if (!PeerGroup.headerFits(seen, PeerGroup.stateOf(outbox.value, seen.group.id))) return
        if (roster.isDeclined(invite.peerId)) return
        val had = roster.peers.value.firstOrNull { it.id == invite.peerId }
        if (had != null && had.dhPubKey.isNotBlank()) return
        roster.upsert(
            PeerIdentity(
                id = invite.peerId,
                name = "",
                kind = PeerIdentity.Kind.PERSON,
                pubKey = invite.pubKey,
                dhPubKey = invite.dhPubKey,
                addedAtMs = nowMs(),
                keySource = PeerIdentity.KeySource.REMOTE,
            ),
        )
    }

    /**
     * Стирання. Рядки зникають ПЕРШИМИ, а число — це вже наслідок: екран, який
     * каже «стерто», поки сховище ще не переписане, вчить не вірити слову.
     *
     * Обсяг перечитується тут, а не приймається з екрана: між показаним числом
     * і дотиком міг прийти ще один лист, і стерти треба те, що є, а не те, що
     * було намальовано.
     */
    fun clearConversation(peerId: String): Int = forget(PeerPurge.conversation(outbox.value, peerId))

    fun clearGroup(groupId: String): Int = forget(PeerPurge.group(outbox.value, groupId))

    fun deleteLine(message: PeerMessage): Int = forget(PeerPurge.line(outbox.value, message))

    /**
     * Правка чи стирання «для всіх» кожної копії власного рядка.
     *
     * Кадр іде лише тим, у кого є ключ; решта копій переписується тут тим самим
     * [PeerRevise.apply], а їхній кадр до черги не потрапляє. Повертає, скільки
     * кадрів лягло в чергу: нуль — правка лишилась тут; null — ключа ще немає й
     * не змінилось нічого.
     */
    fun revise(copies: List<PeerMessage>, act: (targetId: String) -> PeerRevise.Act): Int? {
        val me = cachedId ?: return null
        val key = cachedKey ?: return null
        val at = nowMs()
        val mine = copies.filter { it.fromId == me }
        val frames = mine.mapNotNull { copy ->
            val body = PeerRevise.encode(act(copy.id)) ?: return@mapNotNull null
            val reachable = copy.toId != me &&
                roster.peers.value.any { it.id == copy.toId && it.dhPubKey.isNotBlank() }
            PeerSealing.seal(
                PeerMessage(java.util.UUID.randomUUID().toString(), me, copy.toId, body, at),
                key,
            ) to reachable
        }
        val local = frames.filterNot { it.second }.map { it.first.id }.toSet()
        val ids = mine.map { it.id }.toSet()
        // Своє тіло змінилось, а лист, що ще не пішов, понесе саме його: без нового
        // підпису приймач відкинув би рядок, і правка чекала б на нього вічно.
        publishRevised(
            transform = { current -> frames.fold(current) { acc, (frame, _) -> PeerOutbox.enqueue(acc, frame) } },
            after = { log ->
                log.filterNot { it.id in local }.map {
                    if (it.id in ids && it.deletedAtMs == 0L) PeerSealing.seal(it, key) else it
                }
            },
        )
        activeScope?.launch { flush() }
        return frames.size - local.size
    }

    /** Новіший кадр прочитання заступає старший ще в черзі: доганяти нічого. */
    fun sendRead(toId: String, body: String): PeerMessage? {
        if (!PeerRead.isRead(body)) return null
        val sent = send(toId, body) ?: return null
        publish { PeerRead.compact(it) }
        return sent
    }

    private fun publishRevised(
        after: (List<PeerMessage>) -> List<PeerMessage> = { it },
        transform: (List<PeerMessage>) -> List<PeerMessage>,
    ) {
        var gone = emptyList<PeerMessage>()
        publish { current ->
            val applied = PeerRevise.apply(transform(current))
            gone = applied.unsent
            after(PeerRead.compact(applied.log))
        }
        val me = cachedId
        gone.forEach { runCatching { onUnsent(if (it.fromId == me) it.toId else it.fromId, it) } }
    }

    fun injectMessages(messages: List<PeerMessage>) {
        publish { current ->
            val map = current.associateBy { it.id }.toMutableMap()
            for (m in messages) {
                map[m.id] = m
            }
            map.values.toList().sortedBy { it.sentAtMs }
        }
    }

    /**
     * Єдина зміна журналу, яка щось ЗАБИРАЄ, — і тому та, чия втрата видна
     * найдужче: стерте повертається на екран.
     *
     * Насос крутиться на своїй гілці й теж пише сюди, тож стирання, зроблене
     * окремими читанням і записом, губилось під його записом, порахованим до
     * нього. Атомарність дає [publish]; на диск іде рівно те, що перемогло.
     */
    private fun forget(scope: PeerPurge.Scope): Int {
        if (scope.isEmpty) return 0
        publish { PeerOutbox.delete(it, scope.ids) }
        return scope.count
    }

    /**
     * Забути тіки живого положення — обігнані й протухлі.
     *
     * Окремо від [forget], бо це не дія людини над розмовою, а прибирання за
     * сеансом: без нього журнал беріг би слід чужого дня рівно тим кодом, який
     * писався його не берегти. Правило, ЩО саме забути, лежить у
     * [local.phantom.companion.core.net.peer.PeerLiveInbox.spent].
     */
    fun forgetLive(ids: Set<String>) {
        if (ids.isEmpty()) return
        publish { PeerOutbox.delete(it, ids) }
    }

    /** Чи візьме сховок лист просто зараз — половина відповіді «чи дійде». */
    fun relayReady(): Boolean = online() && relay.ready()

    fun relayLiveReady(): Boolean = relay.liveReady()

    /** Debug-only: a second node needs the code without retyping 200 characters. */
    internal fun logInviteForDebug() {
        if (!local.phantom.companion.BuildConfig.DEBUG) return
        _myInvite.value?.let {
            android.util.Log.i("PhantomInvite", PeerInvite.encode(it))
        }
    }

    /** A hint, never a promise — without it a restart leaves queued messages nowhere to go. */
    private fun seedAddresses() {
        roster.peers.value.forEach { peer ->
            val where = peer.transport ?: return@forEach
            val host = where.substringBeforeLast(':', where)
            val port = where.substringAfterLast(':', "").toIntOrNull() ?: return@forEach
            rememberAddress(peer.id, host, port)
        }
    }

    // ── заява на посилання для вкладення ─────────────────────────────────

    /**
     * Підписана заява пристрою: хто просить, що саме, коли.
     *
     * Поля рівно ті, які читає `platform-site/server/main.py:AttachmentProof`.
     */
    data class AttachmentProof(
        val pubKey: String,
        val nonce: String,
        val issuedMs: Long,
        val signature: String,
    )

    /**
     * Підписати заяву на ЗАПИС обʼєкта такої ваги.
     *
     * **Чому підписує саме тут, а не веб.** Приватний ключ пристрою не
     * перетинає межу WebView ніколи — заради цього міст і існує. Веб, що
     * тримає приватний ключ, це веб, із якого ключ витікає першим XSS.
     *
     * **Чому це НЕ підписувальний оракул.** Ззовні приходить лише ЧИСЛО.
     * Речення складається тут ([PeerBlob.putClaim]), разове число й час
     * беруться тут же, а рід речення — власний ([PeerBlob.CLAIM_DOMAIN]),
     * якого немає більше ніде в продукті. Тобто жодним входом не можна
     * дістати підпис ані під довільним рядком, ані під канонічним рядком
     * листа, ані під payload довідника.
     *
     * Null — заява не складається: немає ключа, або вага поза стелею. Жодного
     * підпису «про всяк випадок».
     */
    fun proveAttachmentPut(size: Long): AttachmentProof? {
        if (size <= 0 || size > PeerBlob.MAX_OBJECT_BYTES) return null
        return sign { pub, nonce, at -> PeerBlob.putClaim(pub, size, nonce, at) }
    }

    /**
     * Підписати заяву на ЧИТАННЯ саме цього обʼєкта.
     *
     * Ключ обʼєкта перевіряється ЗА ФОРМОЮ ([PeerBlob.isObjectKey]) перш ніж
     * потрапити в підписане речення: інакше веб підсунув би сюди
     * `builds/phantom-1.0.apk` і дістав підписану заяву на збірку. Сервер таке
     * відмовляє й сам, але міст не має й просити.
     */
    fun proveAttachmentGet(objectKey: String): AttachmentProof? {
        if (!PeerBlob.isObjectKey(objectKey)) return null
        return sign { pub, nonce, at -> PeerBlob.getClaim(pub, objectKey, nonce, at) }
    }

    /**
     * Свідчення про стан комірки для `/truth/observe`. Як і в заяв на вкладення,
     * речення складається тут і лише з закритого словника — не оракул.
     */
    fun proveTruthObserve(cell: String, kind: String, reading: String): AttachmentProof? {
        val k = local.phantom.companion.core.truth.FactKind.byWire(kind) ?: return null
        if (k !in local.phantom.companion.core.truth.FactKind.CELL || k.readings.none { it.wire == reading }) return null
        if (!CELL_R8.matches(cell)) return null
        return sign { pub, nonce, at ->
            local.phantom.companion.core.truth.TruthWire.observeMessage(
                pub, nonce, at, cell, null, k.wire, "assert", reading, null, null,
            )
        }
    }

    /**
     * Спільна частина: ключ, разове число, час, підпис.
     *
     * Разове число з [java.security.SecureRandom]: заява одноразова, і
     * передбачуване число зробило б її переграваною попри всю решту.
     */
    private fun sign(sentence: (pub: String, nonce: String, issuedMs: Long) -> String): AttachmentProof? {
        val key = cachedKey ?: privateKey() ?: return null
        val pub = publicKey() ?: return null
        val raw = ByteArray(PeerBlob.CLAIM_NONCE_CHARS / 2)
        java.security.SecureRandom().nextBytes(raw)
        val nonce = raw.joinToString("") { "%02x".format(it) }
        val at = nowMs()
        val sig = runCatching {
            local.phantom.companion.core.net.crypto.Ed25519.sign(
                key,
                sentence(pub, nonce, at).toByteArray(Charsets.UTF_8),
            )
        }.getOrNull() ?: return null
        return AttachmentProof(
            pubKey = pub,
            nonce = nonce,
            issuedMs = at,
            signature = java.util.Base64.getEncoder().encodeToString(sig),
        )
    }

    /** Reads the identity off the Keystore once, on IO. */
    private suspend fun loadIdentity() = withContext(Dispatchers.IO) {
        // Двері — раніше за id: насос, що побачив id, мусить бачити й двері,
        // інакше перший план складеться без них і так і житиме до нової доби.
        if (cachedDoor == null) {
            cachedDoor = (cachedId ?: selfId())?.let { id ->
                dhPublicKey()
                    ?.let { runCatching { java.util.Base64.getDecoder().decode(it.trim()) }.getOrNull() }
                    ?.let { PeerRelay.doorKey(it, id) }
            }
        }
        if (cachedId == null) cachedId = selfId()
        if (cachedKey == null) cachedKey = privateKey()
        if (cachedDhKey == null) cachedDhKey = dhPrivateKey()
        cachedId?.let { _selfIdFlow.value = it }
    }

    /**
     * The key this pair shares, derived once and kept only in memory.
     *
     * Null is a refusal, never an empty key: a peer added before this build has
     * no X25519 key, and pretending otherwise would seal messages to a value
     * everybody knows.
     */
    private fun channelFor(peer: PeerIdentity): ByteArray? {
        val me = cachedId ?: return null
        val mine = cachedDhKey ?: return null
        return channels.get(peer.id, peer.dhPubKey) {
            PeerChannel.derive(mine, peer.dhPubKey, me, peer.id)
        }
    }

    /**
     * Ключ пари з [peerId] — для Намірів до мого ПК (розд. 07, IntentWiring).
     * Той самий, яким їздять листи; null — пари немає або ще не знаю себе.
     */
    fun pairKeyFor(peerId: String): ByteArray? =
        roster.peers.value.firstOrNull { it.id == peerId }?.let(::channelFor)

    /**
     * Ключі конверта v5 (docs/crypto/RELAY_FS.md), що стираються. Ставить
     * RelayFsWiring; null — лист сплячому йде v3, як досі. Мої приватні ключі
     * звідси не виходять: назовні — лише пачка prekey і готові кадри.
     */
    @Volatile var relayFsKeys: local.phantom.companion.core.net.peer.RelayFsKeys? = null

    private fun relayFsMe(): local.phantom.companion.core.net.peer.RelayFs.Identity? {
        val ed = cachedKey ?: return null
        val dh = cachedDhKey ?: return null
        return runCatching {
            local.phantom.companion.core.net.peer.RelayFs.Identity(
                local.phantom.companion.core.net.crypto.Ed25519.publicKeyFromPrivate(ed),
                local.phantom.companion.core.net.crypto.X25519.publicKeyFromPrivate(dh),
            )
        }.getOrNull()
    }

    private fun relayFsThem(peerId: String): local.phantom.companion.core.net.peer.RelayFs.Identity? {
        val peer = roster.peers.value.firstOrNull { it.id == peerId } ?: return null
        val dec = java.util.Base64.getDecoder()
        val ed = runCatching { dec.decode(peer.pubKey) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        val dh = runCatching { dec.decode(peer.dhPubKey.trim()) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        return local.phantom.companion.core.net.peer.RelayFs.Identity(ed, dh)
    }

    private fun relayFsSeal(message: PeerMessage): local.phantom.companion.core.net.peer.RelayFsMail.Sealed? {
        val keys = relayFsKeys ?: return null
        val me = relayFsMe() ?: return null
        val them = relayFsThem(message.toId) ?: return null
        val dh = cachedDhKey ?: return null
        return local.phantom.companion.core.net.peer.RelayFsMail.seal(message, me, dh, them, keys)
    }

    private fun relayFsOpen(raw: String, peerId: String): PeerMessage? {
        val keys = relayFsKeys ?: return null
        val me = relayFsMe() ?: return null
        val them = relayFsThem(peerId) ?: return null
        val dh = cachedDhKey ?: return null
        return local.phantom.companion.core.net.peer.RelayFsMail.open(raw, me, dh, peerId, them, keys)
    }

    /** Тіло листа PH-PKB: для [peerId], якщо пачка потрібна; null — слати нічого. */
    fun relayFsBundleFor(peerId: String): String? {
        val keys = relayFsKeys ?: return null
        val ed = cachedKey ?: return null
        return keys.bundleFor(peerId, ed)?.let(local.phantom.companion.core.net.peer.RelayFs::encodeBundle)
    }

    /** Public because the operator can rename themselves after the mesh has started. */
    suspend fun refreshInvite() = withContext(Dispatchers.IO) {
        val me = cachedId ?: selfId() ?: return@withContext
        val pub = publicKey() ?: return@withContext
        val dhPub = dhPublicKey() ?: return@withContext
        _myInvite.value = PeerInvite.Invite(
            peerId = me,
            name = myName()?.trim()?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME,
            pubKey = pub,
            dhPubKey = dhPub,
            host = localAddress().orEmpty(),
            port = link.boundPort.takeIf { it > 0 } ?: 0,
        )
    }

    /**
     * The address rides along so the first message has somewhere to go before
     * the beacon has seen them. Encrypted write — never from composition.
     */
    suspend fun accept(
        invite: PeerInvite.Invite,
        // Defaulted to the weak channel on purpose: a call site that forgets to
        // say where the key came from can only ever under-claim. Only the live
        // camera may pass CAMERA, and only from the surface that owns it.
        source: PeerIdentity.KeySource = PeerIdentity.KeySource.REMOTE,
    ): PeerIdentity = withContext(Dispatchers.IO) {
        val peer = PeerIdentity(
            id = invite.peerId,
            name = invite.name,
            nameShortened = invite.nameShortened,
            nameUnreadable = invite.nameUnreadable,
            kind = PeerIdentity.Kind.PERSON,
            pubKey = invite.pubKey,
            dhPubKey = invite.dhPubKey,
            transport = invite.host.takeIf { it.isNotBlank() }?.let {
                if (invite.port > 0) "$it:${invite.port}" else it
            },
            addedAtMs = nowMs(),
            keySource = source,
        )
        // Додати руками — єдине, що знімає засув із прибраного. Дріт його не
        // знімає ніколи, інакше «прибрати» нічого не означало б.
        roster.allow(invite.peerId)
        roster.upsert(peer)
        rememberAddress(invite.peerId, invite.host, invite.port)
        // Adding them answers whatever of theirs was bouncing off this phone.
        _strangers.value = PeerStranger.forget(_strangers.value, invite.peerId)
        peer
    }

    /** This device's address on the LAN. Blocking — IO context only. */
    private fun localAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    fun retry(id: String) {
        publish { PeerOutbox.retry(it, id) }
        activeScope?.launch { flush() }
    }

    /** Один рядок у групі — це N копій; «ще раз» піднімає весь віяр. */
    fun retryGroupLine(groupId: String, sentAtMs: Long) {
        val ids = PeerGroup.failedFan(outbox.value, groupId, sentAtMs)
        if (ids.isEmpty()) return
        publish { current -> ids.fold(current) { acc, id -> PeerOutbox.retry(acc, id) } }
        activeScope?.launch { flush() }
    }

    fun conversation(peerId: String): List<PeerMessage> =
        PeerOutbox.conversation(outbox.value, peerId)

    /**
     * Пряме доставлення повідомлення в чергу розмови (для локального ШІ-агента,
     * мультиагентної Колегії Агентів або Desktop Relay).
     */
    fun ingestDirect(message: PeerMessage) {
        publish { current ->
            PeerOutbox.enqueue(current, message.copy(state = PeerMessage.State.DELIVERED))
        }
        runCatching { onArrived(message) }
    }

    /**
     * Стан листа, що не їде дротом: питання до локального помічника. Той самий рядок
     * журналу змінює стан («не пішло» → «пішло»), другого запису не зʼявляється.
     */
    fun markDirect(id: String, state: PeerMessage.State) {
        publish { current ->
            current.map { if (it.id == id && it.state != state) it.copy(state = state) else it }
        }
    }

    /**
     * Лист, якого нічим запечатати для адресата.
     *
     * Через [send] він потрапив би в чергу — тобто в обіцянку «повеземо, щойно
     * вузол співрозмовника обізветься», якої нічим виконати: бракує не мережі,
     * а ключа, і сам він не приходить. Тому стан ставиться одразу й свідомо
     * ([PeerOutbox.enqueue] такий не перетирає).
     *
     * Підпис кладеться попри це: він робиться ВЛАСНИМ ключем, а не чужим, і без
     * нього «ще раз» після обміну запрошеннями вислав би неперевірний кадр.
     */
    fun ingestUndeliverable(
        toId: String,
        body: String,
        replyToId: String = "",
        fw: PeerMessage.Forward? = null,
        qt: String = "",
    ): PeerMessage? {
        val me = cachedId ?: return null
        val key = cachedKey ?: return null
        val message = PeerSealing.seal(
            PeerMessage(
                id = java.util.UUID.randomUUID().toString(),
                fromId = me,
                toId = toId,
                body = body,
                sentAtMs = nowMs(),
                replyToId = replyToId,
                fw = fw,
                qt = if (replyToId.isBlank()) "" else qt,
            ),
            key,
        ).copy(state = PeerMessage.State.FAILED)
        publish { PeerOutbox.enqueue(it, message) }
        return message
    }

    /**
     * Лист таким, яким він має ЛЯГТИ В ЖУРНАЛ.
     *
     * Шматок, що приїхав, коштує журналу рівно стільки ж, скільки шматок, що
     * їде: журнал переписується цілком на кожен запис, і сотня чужих шматків
     * робить кожне наступне «привіт» дорожчим за попереднє. Байти при цьому
     * однаково лягають на диск ([PeerFileStore.absorb] далі по дорозі), тож
     * їхня копія в журналі — така сама чиста плата, як і у відправника.
     *
     * Кладеться тут, ДО запису, а не після: між записом і чисткою телефон може
     * померти, і тоді в журналі лишився б важкий рядок назавжди.
     *
     * Складання файла це не ламає: [PeerFileStore.vault] кладе байти тим самим
     * `keepPart`, а [onArrived] далі отримує лист зі СПРАВЖНІМ тілом — тобто
     * бачить рівно те, що бачив.
     */
    private fun vaultArrived(message: PeerMessage): PeerMessage {
        if (!PeerFile.isChunk(message.body) || PeerFile.isVaulted(message.body)) return message
        val vault = vaultChunk ?: return message
        val conversation = if (message.fromId == cachedId) message.toId else message.fromId
        val ref = vault(conversation, message.body) ?: return message
        return message.copy(body = ref)
    }

    /**
     * Лист із тілом, яке справді поїде.
     *
     * Для всього, крім шматка-посилання, це той самий лист. Для посилання —
     * копія зі справжніми байтами зі сховища; підпис при цьому НЕ чіпається й
     * не перескладається, бо він і був покладений на ці самі байти
     * ([sendMany]). Null — байтів немає ніде.
     */
    private fun openVaulted(message: PeerMessage): PeerMessage? {
        if (!PeerFile.isVaulted(message.body)) return message
        val opener = openChunk ?: return null
        val real = opener(message.body) ?: return null
        return message.copy(body = real)
    }

    /**
     * Хто зараз везе який лист. Кілька `flush()` ідуть одночасно ([send], насос,
     * «спробуй зараз»), і кожен лист має їхати одним із них.
     *
     * Колись тут стояв один замок на весь такт: 24.09 на A52 лист `7000b1c1`
     * ліг у сховок ДВІЧІ, бо два такти зняли `due` до того, як перший позначив
     * HELD. Замок це лікував ціною того, що нове слово чекало, поки попередній
     * такт дочекається сокета мовчазного телефона. Тепер замок — на лист.
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun flush() = coroutineScope {
        val now = nowMs()
        // Поруч (LAN) дорога широка за визначенням: вузькість — про сховок.
        // Коротке голосове — мова людини: описи в журналі кажуть, чиї шматки їдуть і вузькою дорогою.
        val log = outbox.value
        val due = PeerLane.order(PeerOutbox.due(log, now), relay.narrow(), now, PeerLane.speechBlobs(log)) { m ->
            addresses[m.toId] != null && roster.peers.value.any { it.id == m.toId && it.isNearby(now) }
        }
        if (due.isEmpty()) return@coroutineScope
        val viaRelay = online() && relay.ready()
        // Одна пропозиція зустрітись на співрозмовника за такт: пʼять листів
        // до одного мовчазного телефона інакше коштували б пʼять таймаутів.
        val meetings = java.util.concurrent.ConcurrentHashMap<String, Deferred<Meeting>>()
        val lanes = Semaphore(FLUSH_PARALLEL)
        for (queued in due) {
            if (queued.fromId != cachedId) continue
            if (!inFlight.add(queued.id)) continue
            launch {
                try {
                    lanes.withPermit { deliver(queued, viaRelay, meetings, this@coroutineScope) }
                } finally {
                    inFlight.remove(queued.id)
                }
            }
        }
    }

    private suspend fun deliver(
        queued: PeerMessage,
        viaRelay: Boolean,
        meetings: java.util.concurrent.ConcurrentHashMap<String, Deferred<Meeting>>,
        tick: CoroutineScope,
    ) {
        // Знімок черги міг застаріти, поки чекали місця: інший такт уже довіз.
        val now = outbox.value.firstOrNull { it.id == queued.id } ?: return
        if (PeerOutbox.due(listOf(now), nowMs()).isEmpty()) return
        // Посилання стає справжнім тілом ТУТ, за мить до конверта — і далі
        // працюємо з ним, бо саме воно підписане й саме воно має поїхати.
        // Байтів немає ніде — лист видимо падає, а не їде порожнім.
        val message = openVaulted(now) ?: run {
            val at = nowMs()
            publish { PeerOutbox.markFailed(it, queued.id, at) }
            return
        }
        val recipient = roster.peers.value.firstOrNull { it.id == message.toId }
        val channel = recipient?.let { channelFor(it) }
        if (recipient == null || channel == null) {
            // Somebody added before this build, or a row whose key will not
            // make one. Failing HERE, before the socket, is the whole point:
            // the words must never reach the wire unsealed, and the operator
            // must see it as FAILED rather than as sent.
            val at = nowMs()
            publish { PeerOutbox.markFailed(it, message.id, at) }
            return
        }
        // isNearby(), не isLive(): сховковий співрозмовник «живий», але його
        // стара LAN-адреса — лише зайвий таймаут.
        val where = if (recipient.isNearby(nowMs())) addresses[message.toId] else null
        if (where == null) {
            tryNearby(message, channel)
            offload(message, viaRelay)
            return
        }
        val meeting = meetings.computeIfAbsent(message.toId) {
            tick.async { meetWith(recipient, where) }
        }
        coroutineScope {
            val lan = async { viaLan(message, channel, where, meeting) }
            // Пряма дорога має фору: коли телефон справді поруч, сервер не
            // дізнається навіть часу. Коли ні — сховок не чекає таймаутів сокета.
            val stored = if (viaRelay) {
                async {
                    val early = withTimeoutOrNull(LAN_HEAD_START_MS) { lan.await() }
                    if (early == true) false else relay.handOff(message)
                }
            } else {
                null
            }
            if (lan.await()) {
                stored?.cancel()
                return@coroutineScope
            }
            if (stored?.await() == true) return@coroutineScope
            if (stored != null && outbox.value.firstOrNull { it.id == message.id }?.attempts != message.attempts) {
                // Сховок ВІДМОВИВ — насос уже витратив на це спробу.
                return@coroutineScope
            }
            if (outbox.value.any { it.id == message.id && it.state == PeerMessage.State.DELIVERED }) return@coroutineScope
            val at = nowMs()
            publish { PeerOutbox.markUnreached(it, message.id, at) }
        }
    }

    /** Пряма дорога для одного листа. true — квитанція прийшла саме на цей кадр. */
    private suspend fun viaLan(
        message: PeerMessage,
        channel: ByteArray,
        where: Pair<String, Int>,
        meeting: Deferred<Meeting>,
    ): Boolean {
        val met = meeting.await()
        // Домовитись не вдалось: найчастіше той телефон вимкнений, а адреса
        // лишилась із минулого разу. Тоді доїде сховок, що біжить поруч.
        if (met.act == PeerMeet.Act.HOLD) return false
        val session = met.session
        // Sealed per attempt, so every retry carries its own nonce.
        val sealed = if (session != null) {
            PeerEnvelope.sealV4(message, session.sid, session.key)
        } else {
            PeerEnvelope.seal(message, channel)
        } ?: return false
        // «доставлено» may only follow a reply nobody else could write.
        // Under a meeting key it is stronger still — it binds delivery to
        // the encounter that carried it.
        val key = session?.key ?: channel
        val receipt = PeerReceipt.of(key, message.id, message.fromId, message.toId, sealed.nonceB64)
            ?: return false
        publish { PeerOutbox.markSent(it, message.id) }
        val landed = link.send(
            host = where.first,
            peerPort = where.second,
            receipt = receipt,
            json = sealed.frame,
        ) is PeerLink.Outcome.Acked
        if (!landed) {
            // They may simply have restarted and forgotten the meeting.
            // Dropping it costs one round trip on the next attempt.
            if (session != null) sessions.forgetSid(session.sid)
            return false
        }
        // Квитанцію пише лише ключ, якого без нашого рядка в їхньому реєстрі
        // не скласти. Дійшлий лист — такий самий доказ, як і знайомство.
        noteIntroduced(message.toId)
        publish { current ->
            PeerOutbox.noteSealing(
                PeerOutbox.markDelivered(current, message.id),
                message.id,
                if (session != null) PeerMessage.Sealing.MEETING_KEY else PeerMessage.Sealing.PAIR_KEY,
            )
        }
        return true
    }

    private val nearbyInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val nearbyTriedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Біжить поруч із чергою, не замість неї: хто перший довіз, той і правий. */
    private fun tryNearby(message: PeerMessage, channel: ByteArray) {
        if (!NearbyPlan.worthTrying(message.body, online())) return
        if (!NearbyPlan.due(nearbyTriedAt[message.id], nowMs())) return
        val scope = activeScope ?: return
        if (!nearbyInFlight.add(message.id)) return
        nearbyTriedAt[message.id] = nowMs()
        scope.launch {
            try {
                if (viaNearby(message, channel)) nearbyTriedAt.remove(message.id)
            } finally {
                nearbyInFlight.remove(message.id)
            }
        }
    }

    // Ключ пари, а не зустрічі: ключ зустрічі домовляється лише сокетом LAN.
    private suspend fun viaNearby(message: PeerMessage, channel: ByteArray): Boolean {
        // Купюра — останньою: отримувач має знати кишеню раніше, ніж малюватиме картку.
        val riders = outbox.value.filter {
            it.toId == message.toId && it.fromId == message.fromId && it.id != message.id &&
                it.state != PeerMessage.State.DELIVERED && it.state != PeerMessage.State.FAILED &&
                NearbyPlan.ridesWithCash(it.body)
        } + message
        val sealed = riders.mapNotNull { m ->
            val s = PeerEnvelope.seal(m, channel) ?: return@mapNotNull null
            val receipt = PeerReceipt.of(channel, m.id, m.fromId, m.toId, s.nonceB64) ?: return@mapNotNull null
            Triple(m.id, s.frame, receipt)
        }
        if (sealed.lastOrNull()?.first != message.id) return false
        val offered = runCatching { nearby.handOff(message.toId, sealed.map { it.second }) }.getOrNull().orEmpty()
        val landed = sealed.filter { (_, _, expected) -> offered.any { PeerReceipt.matches(expected, it) } }.map { it.first }
        if (landed.isNotEmpty()) {
            noteIntroduced(message.toId)
            publish { current ->
                landed.fold(current) { acc, id ->
                    PeerOutbox.noteSealing(PeerOutbox.markDelivered(acc, id), id, PeerMessage.Sealing.PAIR_KEY)
                }
            }
        }
        if (message.id !in landed) {
            nearby.missed()
            return false
        }
        nearby.landed()
        return true
    }

    private val nearbyDoor = Any()

    /**
     * Кадр, що приїхав із телефона поруч: ті самі двері, що й сокет LAN.
     * Під замком: дві копії однієї купюри інакше обидві пройшли б [CashRepeat].
     */
    fun acceptNearby(raw: String): String? = synchronized(nearbyDoor) { accept(raw) }

    /**
     * Прямого шляху немає. Далі — сховок; а коли й його немає, лист просто чекає
     * далі й НЕ витрачає спроби: вимкнений телефон на тому кінці — це відсутність,
     * не відмова.
     */
    private suspend fun offload(message: PeerMessage, viaRelay: Boolean) {
        if (PeerRelay.fallback(online = viaRelay) == PeerRelay.Way.RELAY) {
            if (relay.handOff(message)) return
            // Сховок ВІДМОВИВ — насос уже витратив на це спробу. Додати згори
            // ще й свою паузу означало б порахувати одну невдачу двічі.
            if (outbox.value.firstOrNull { it.id == message.id }?.attempts != message.attempts) return
        }
        val at = nowMs()
        publish { PeerOutbox.markUnreached(it, message.id, at) }
    }

    /**
     * Забір зі сховка ззовні такту — з періодичної роботи, коли сітка не
     * піднята. Особистість читається тут-таки: без неї насос мовчки не робить
     * нічого, і це не мало б жодного симптому.
     */
    suspend fun collectRelay(): PeerRelayPump.Report {
        loadIdentity()
        return relay.collect()
    }

    /** Після [collectRelay]: черга з періодичної роботи, коли служби немає, — без цього лист чекав, поки людина відкриє застосунок. */
    suspend fun drainOutbox() {
        flush()
        // handOff будить живий канал; без служби його сокет висів би у фоновому процесі.
        if (!running) relay.close()
    }

    private class Meeting(
        val session: PeerSession.Session?,
        val act: PeerMeet.Act,
        val why: PeerMeet.Why,
    )

    /**
     * Ключ зустрічі для цього співрозмовника, домовлений тут-таки, якщо його ще
     * немає. Що робити з кожною відповіддю, вирішує [PeerMeet], а не це місце.
     */
    private suspend fun meetWith(peer: PeerIdentity, where: Pair<String, Int>): Meeting {
        val binding = PeerSession.binding(peer)
        sessions.newestFor(peer.id, binding, nowMs())?.let {
            return Meeting(it, PeerMeet.Act.MEET, PeerMeet.Why.MET)
        }
        val known = knownOf(peer)
        // Стримування діє лише на тих, з ким зустрічі ще не було: [PeerMeet
        // .shouldOffer] завжди пропускає доведену пару.
        if (!PeerMeet.shouldOffer(known, nowMs())) {
            return Meeting(null, PeerMeet.Act.PAIR_KEY, PeerMeet.Why.OLD_BUILD)
        }
        val me = cachedId ?: return Meeting(null, PeerMeet.Act.HOLD, PeerMeet.Why.SILENT)
        val signing = cachedKey ?: return Meeting(null, PeerMeet.Act.HOLD, PeerMeet.Why.SILENT)
        val mine = cachedDhKey ?: return Meeting(null, PeerMeet.Act.HOLD, PeerMeet.Why.SILENT)
        val offer = PeerSession.offer(me, peer.id, signing)
            ?: return Meeting(null, PeerMeet.Act.HOLD, PeerMeet.Why.SILENT)
        var session: PeerSession.Session? = null
        val answer = try {
            when (val answered = link.hello(where.first, where.second, offer.frame)) {
                is PeerLink.Outcome.Met -> {
                    session = PeerSession.parse(answered.json)?.let { theirs ->
                        PeerSession.finish(
                            offer = offer,
                            theirHello = theirs,
                            theirSigningPubB64 = peer.pubKey,
                            myStaticDh = mine,
                            theirStaticDhB64 = peer.dhPubKey,
                            nowMs = nowMs(),
                        )
                    }
                    session?.let { sessions.put(it, binding, nowMs()) }
                    if (session != null) PeerMeet.Answer.MET else PeerMeet.Answer.REFUSED_OTHER
                }
                is PeerLink.Outcome.Refused ->
                    if (answered.reason.startsWith(PeerWire.UNKNOWN_VERB)) {
                        PeerMeet.Answer.REFUSED_UNKNOWN_VERB
                    } else {
                        PeerMeet.Answer.REFUSED_OTHER
                    }
                else -> PeerMeet.Answer.SILENT
            }
        } finally {
            offer.discard()
        }
        val decision = PeerMeet.after(known, answer, nowMs())
        remember(peer.id, known, answer)
        return Meeting(session, decision.act, decision.why)
    }

    /** Реєстр знає про зустріч, памʼять — про свіжу відмову й тишу. */
    private fun knownOf(peer: PeerIdentity): PeerMeet.Known {
        val held = meetState[peer.id] ?: PeerMeet.Known()
        return held.copy(metAtMs = maxOf(held.metAtMs, peer.metAtMs))
    }

    private fun remember(peerId: String, known: PeerMeet.Known, answer: PeerMeet.Answer) {
        val next = PeerMeet.noted(known, answer, nowMs())
        meetState[peerId] = next
        // Записується лише доведена зустріч, і тільки коли реєстр її ще не знає.
        if (answer == PeerMeet.Answer.MET) {
            roster.markMet(peerId, next.metAtMs)
            // Зустріч склалась через [link] — вітання чи вхідне, обидва пряма
            // мережа, тож саме тут «поруч» стає правдою.
            roster.sawNearby(peerId, next.metAtMs)
            // Ключ зустрічі складається лише з тим, хто тримає наш рядок, — тож
            // це той самий доказ, що й знайомство, і представлятись більше нема
            // навіщо.
            noteIntroduced(peerId)
        }
    }

    /**
     * Одна дія мусить наповнити обидва реєстри — тож хто прийняв чужий код,
     * той винен свою картку у зворотний бік.
     *
     * Живе в такті насоса, а не в миті сканування: код, вставлений з листа,
     * приходить тоді, коли той телефон може бути де завгодно, і єдина відповідь,
     * яка це витримує, — питати знову за розкладом, поки не вийде.
     */
    private suspend fun introduceMyself() {
        val mine = _myInvite.value ?: return
        val signing = cachedKey ?: return
        val dh = cachedDhKey ?: return
        val delivered = outbox.value
            .filter { it.state == PeerMessage.State.DELIVERED }
            .mapTo(HashSet()) { it.toId }
        for (peer in roster.peers.value) {
            if (!PeerIntro.owes(peer, hasDelivered = peer.id in delivered)) continue
            val where = addresses[peer.id]
            val known = introState[peer.id] ?: PeerIntro.Known()
            if (where == null || known.silentInARow > 0) knock(peer, mine, signing, dh)
            // Адреси ще немає — це відсутність, не відмова: витрачати на неї
            // сходинку драбини означало б відсунути спробу, якої ще не було.
            if (where == null) continue
            if (!PeerIntro.shouldOffer(known, nowMs())) continue
            val offer = PeerIntro.offer(
                me = mine,
                toId = peer.id,
                theirDhPubB64 = peer.dhPubKey,
                mySigningKey = signing,
                myDhPrivate = dh,
            ) ?: continue
            val answer = when (val back = link.intro(where.first, where.second, offer.frame)) {
                is PeerLink.Outcome.Met -> {
                    val card = PeerIntro.open(
                        back.json,
                        myId = mine.peerId,
                        myDhPrivate = dh,
                        echoOf = offer.nonceB64,
                    )
                    if (card?.peerId == peer.id) {
                        PeerIntro.Answer.INTRODUCED
                    } else {
                        PeerIntro.Answer.REFUSED
                    }
                }
                is PeerLink.Outcome.Refused -> PeerIntro.Answer.REFUSED
                else -> PeerIntro.Answer.SILENT
            }
            introState[peer.id] = PeerIntro.noted(known, answer, nowMs())
            if (answer == PeerIntro.Answer.INTRODUCED) noteIntroduced(peer.id)
            // Одне знайомство на такт. Мовчазний телефон коштує таймаут, і
            // черга з них відсунула б листи, які й так уже чекають: доставка
            // важливіша за знайомство, а знайомству нема куди поспішати.
            return
        }
    }

    /**
     * Стук у двері: та сама пропозиція знайомства, але сховком, раз на добу, поки
     * її не прийняли. Двері живуть три доби в запиті адресата, а сервер тримає
     * лист тиждень — тож щоденний стук і є те, що не дає йому застрягти.
     */
    private suspend fun knock(peer: PeerIdentity, mine: PeerInvite.Invite, signing: ByteArray, dh: ByteArray) {
        if (!online() || peer.dhPubKey.isBlank()) return
        val now = nowMs()
        val today = PeerRelay.epochOf(now)
        if (doorKnocked[peer.id] == today) return
        // Сховок не відповів — не стукати ним кожен такт.
        if (now - (doorTriedAt[peer.id] ?: Long.MIN_VALUE / 2) < KNOCK_RETRY_MS || !relay.ready()) return
        doorTriedAt[peer.id] = now
        // Нонс — з власного ключа й доби, не випадковий: після перезапуску
        // [answeredAtDoor] складе його наново й упізнає відповідь на цей стук.
        val nonce = PeerRelay.knockNonce(dh, peer.id, today, mine.name) ?: return
        val offer = PeerIntro.offer(
            me = mine.copy(host = "", port = 0),
            toId = peer.id,
            theirDhPubB64 = peer.dhPubKey,
            mySigningKey = signing,
            myDhPrivate = dh,
            nonce = nonce,
        ) ?: return
        // Нонс — до вкидання: відповідь живим каналом може випередити повернення вкидання.
        doorOffers.merge(peer.id, listOf(offer.nonceB64)) { old, new -> (old + new).takeLast(DOOR_OFFERS_KEPT) }
        if (relay.dropDoor(peer.dhPubKey, peer.id, offer.frame)) doorKnocked[peer.id] = today
    }

    private val doorOffers = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val doorKnocked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val doorTriedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Лист із власних дверей. true — копію можна палити: узято або відкинуто
     * назавжди. false — телефон ще не знає себе, лист полежить до наступного заходу.
     */
    private suspend fun onDoor(raw: String): Boolean {
        if (cachedId == null || cachedKey == null || cachedDhKey == null) return false
        val intro = PeerIntro.parse(raw) ?: return acceptRelayed(raw, live = false)
        // Періодична робота забирає двері й тоді, коли сітку не піднято (порожній
        // реєстр — служби немає), а без власної картки відповісти на стук нічим.
        if (_myInvite.value == null) refreshInvite()
        // Двері питаються в кожному заборі: лишений стук заблокованого їздив би
        // сюди тиждень. Відповіді він не бачить ні так, ні так.
        if (PeerBlock.refusesIntro(blocked(), raw)) return true
        if (answeredAtDoor(raw, intro)) return true
        return when (val knock = admit(raw, viaRelay = true)) {
            is Knock.In -> {
                wake()
                relay.dropDoor(knock.card.dhPubKey, knock.card.peerId, knock.answer)
            }
            Knock.Refused -> true
            Knock.NotYet -> false
        }
    }

    private fun answeredAtDoor(raw: String, intro: PeerIntro.Frame): Boolean {
        val me = cachedId ?: return false
        val dh = cachedDhKey ?: return false
        val nonces = doorOffers[intro.from].orEmpty() + knockedBefore(intro.from, dh)
        if (nonces.isEmpty()) return false
        val card = nonces.firstNotNullOfOrNull { PeerIntro.open(raw, myId = me, myDhPrivate = dh, echoOf = it) }
        if (card?.peerId != intro.from) return false
        doorOffers.remove(intro.from)
        noteIntroduced(intro.from)
        return true
    }

    /**
     * Стуки, які цей телефон міг зробити [peerId] за життя листа в сховку.
     * Стук — чиста функція нонса ([PeerRelay.knockNonce]), тож список
     * складається наново й після перезапуску, який стер [doorOffers]: інакше
     * відповідь, що прийшла після нього, не впізнавалась би й згоряла.
     */
    private fun knockedBefore(peerId: String, dh: ByteArray): List<String> {
        if (roster.peers.value.none { it.id == peerId }) return emptyList()
        val name = _myInvite.value?.name ?: return emptyList()
        val today = PeerRelay.epochOf(nowMs())
        return (0..PeerRelay.TTL_DAYS).mapNotNull { back ->
            PeerRelay.knockNonce(dh, peerId, today - back, name)?.let { java.util.Base64.getEncoder().withoutPadding().encodeToString(it) }
        }
    }

    /**
     * Конверт пари, що прийшов сховком у двері: живим кадром або завеликим
     * сигналом. Перевірки ті самі, що й на дроті, але «поруч» не стає правдою.
     * true — копію можна палити.
     */
    private fun acceptRelayed(raw: String, live: Boolean = true): Boolean {
        if (raw.startsWith(LIVE_HEARD)) {
            liveAcks.remove(raw.removePrefix(LIVE_HEARD))?.complete(Unit)
            return true
        }
        if (PeerBlock.refusesEnvelope(blocked(), raw)) return true
        val env = PeerEnvelope.parse(raw)?.takeIf { it.v == PeerEnvelope.VERSION } ?: return true
        val me = cachedId ?: return false
        if (env.to != me) return true
        val sender = roster.peers.value.firstOrNull { it.id == env.from } ?: return true
        val key = channelFor(sender) ?: return false
        val message = PeerEnvelope.open(raw) { key } ?: return true
        val pub = runCatching { java.util.Base64.getDecoder().decode(sender.pubKey) }.getOrNull() ?: return true
        if (!PeerSealing.verify(message, pub, expectedFromId = sender.id)) return true
        val call = PeerCall.isCall(message.body)
        if (call || PeerTyping.isTyping(message.body)) {
            // Двері чує кожен, хто бачив код: кадр, підслуханий і присланий
            // знову, мусить бути старим або вже чутим. Межа — життя дзвінка з
            // хвилиною на розбіжність годинників в обидва боки, щоб телефон,
            // що відстає, не глушив справжній дзвінок.
            val age = nowMs() - message.sentAtMs
            if (age !in -LIVE_SKEW_MS until PeerCall.RING_LIFE_MS + LIVE_SKEW_MS) return true
            if (!firstHeard(message.id)) return true
        }
        noteIntroduced(sender.id)
        if (call) {
            // Реєстр переписується цілком: не на кожен кандидат ICE.
            if (nowMs() - sender.lastSeenMs >= LIVE_SEEN_EVERY_MS) roster.sawIt(sender.id, nowMs())
            callTrace("live", message)
            runCatching { onCall(message) }
            if (PeerCall.decodeSignal(message.body)?.what == PeerCall.Signalling.OFFER) {
                PeerReceipt.of(key, message.id, message.fromId, message.toId, env.n)?.let { heardOffer(sender, it) }
            }
            return true
        }
        if (PeerTyping.isTyping(message.body)) {
            if (live) runCatching { onTyping(message) }
            return true
        }
        return keepArrived(message.copy(state = PeerMessage.State.DELIVERED, sealing = PeerMessage.Sealing.PAIR_KEY))
    }

    /**
     * «Почув, дзвоню» — тому, хто набирає. Живим каналом, а без нього — в його
     * двері: сервер штовхає покладене тим, хто їх слухає, тож це так само швидко.
     */
    private fun heardOffer(caller: PeerIdentity, receipt: String) {
        val scope = activeScope ?: return
        scope.launch(Dispatchers.IO) {
            val frame = LIVE_HEARD + receipt
            if (relay.sendLive(caller.dhPubKey, caller.id, frame) != PeerRelayCourier.Live.SENT) {
                relay.dropDoor(caller.dhPubKey, caller.id, frame)
            }
        }
    }

    private sealed interface Knock {
        class In(val card: PeerIntro.Introduction, val answer: String) : Knock
        data object Refused : Knock
        data object NotYet : Knock
    }

    /**
     * Хтось прочитав наш код і представляється у зворотний бік.
     *
     * Рядок лягає на диск ПЕРЕД тим, як звідси піде відповідь. Відповідь — це
     * доказ, що ми його маємо; збій між нею й записом лишив би того, хто більше
     * ніколи не представиться, поза реєстром назавжди — рівно та тиша, заради
     * якої це все й робилось.
     */
    private suspend fun introduced(raw: String): String? = (admit(raw, viaRelay = false) as? Knock.In)?.answer

    /** [viaRelay] — сховок не приносить ні адреси в мережі, ні «поруч». */
    private fun admit(raw: String, viaRelay: Boolean): Knock {
        val mine = _myInvite.value ?: return Knock.NotYet
        val me = cachedId ?: return Knock.NotYet
        val signing = cachedKey ?: return Knock.NotYet
        val dh = cachedDhKey ?: return Knock.NotYet
        // Прибраного впізнаємо до будь-якої арифметики на його користь; відмова
        // при цьому та сама, тож із дроту «прибраний» і «стара збірка» не
        // розрізнити.
        val claimed = PeerIntro.parse(raw) ?: return Knock.Refused
        if (roster.isDeclined(claimed.from)) return Knock.Refused
        if (PeerBlock.refusesIntro(blocked(), raw)) return Knock.Refused
        val card = PeerIntro.open(raw, myId = me, myDhPrivate = dh) ?: return Knock.Refused
        val answer = PeerIntro.answer(
            me = if (viaRelay) mine.copy(host = "", port = 0) else mine,
            to = card,
            mySigningKey = signing,
            myDhPrivate = dh,
        ) ?: return Knock.Refused
        val at = nowMs()
        roster.upsert(
            PeerIdentity(
                id = card.peerId,
                name = card.name,
                nameShortened = card.nameShortened,
                kind = PeerIdentity.Kind.PERSON,
                pubKey = card.pubKey,
                dhPubKey = card.dhPubKey,
                transport = card.host.takeIf { it.isNotBlank() && !viaRelay }?.let {
                    if (card.port > 0) "$it:${card.port}" else it
                },
                addedAtMs = at,
                // Найслабший справжній канал: код приніс якийсь застосунок, і
                // ніхто тут його не набирав, і жодна камера його не бачила.
                keySource = PeerIdentity.KeySource.SHARED,
                // Складати це вони могли, лише маючи наш рядок у себе.
                introducedAtMs = at,
            ),
            at,
        )
        if (!viaRelay) {
            rememberAddress(card.peerId, card.host, card.port)
            // Картка прийшла через [link] так само, як вітання — пряма мережа.
            roster.sawNearby(card.peerId, at)
        }
        _strangers.value = PeerStranger.forget(_strangers.value, card.peerId)
        introState.remove(card.peerId)
        return Knock.In(card, answer)
    }

    /** Один запис на пару й більше ніколи: шифрований запис на кожен лист дорогий. */
    private fun noteIntroduced(peerId: String) {
        val peer = roster.peers.value.firstOrNull { it.id == peerId } ?: return
        if (peer.introducedAtMs > 0L) return
        roster.markIntroduced(peerId, nowMs())
        introState.remove(peerId)
    }

    /**
     * Somebody wants to agree a key for this meeting.
     *
     * A stranger is refused before any arithmetic happens on their behalf —
     * the same stance [accept] takes — and the refusal is word-for-word what a
     * build from before the handshake says, so asking this phone tells them
     * nothing about which version it runs.
     */
    private fun greet(raw: String): String? {
        val me = cachedId ?: return null
        val signing = cachedKey ?: return null
        val mine = cachedDhKey ?: return null
        if (PeerBlock.refusesHello(blocked(), raw)) return null
        val hello = PeerSession.parse(raw) ?: return null
        val peer = roster.peers.value.firstOrNull { it.id == hello.from } ?: return null
        val answer = PeerSession.answer(
            theirHello = hello,
            myId = me,
            theirSigningPubB64 = peer.pubKey,
            mySigningKey = signing,
            myStaticDh = mine,
            theirStaticDhB64 = peer.dhPubKey,
            nowMs = nowMs(),
        ) ?: return null
        sessions.put(answer.session, PeerSession.binding(peer), nowMs())
        // Їхнє підписане вітання — такий самий доказ, як наше власне: пара вміє
        // зустрічатись, і з цієї миті слабшати їй нема за що.
        remember(peer.id, knownOf(peer), PeerMeet.Answer.MET)
        return answer.frame
    }

    /**
     * A valid signature from an unknown key is a stranger, not a message — and
     * a frame this pair's key cannot open is not a message either, whatever it
     * claims. Cleartext from an older build lands here and fails at the first
     * step, which is what makes the downgrade impossible rather than merely
     * discouraged.
     *
     * Returns the receipt to answer with — written with the pair key, so it
     * only exists once the message really is on this phone.
     */
    private fun accept(raw: String): String? {
        // До noteUnopened: заблокований не має ставати «незнайомцем» на екрані.
        if (PeerBlock.refusesEnvelope(blocked(), raw)) return null
        val env = PeerEnvelope.parse(raw) ?: return null
        val sender = roster.peers.value.firstOrNull { it.id == env.from }
        // A meeting frame is opened by its meeting key or not at all. Falling
        // back to the permanent key here would hand back everything this
        // version exists to take away, and would let anyone holding that key
        // mint frames that look freshly agreed.
        val key = when {
            sender == null -> null
            env.v == PeerEnvelope.VERSION_SESSION ->
                sessions.bySid(env.sid, PeerSession.binding(sender), nowMs())?.key
            else -> channelFor(sender)
        }
        val message = key?.let { k -> PeerEnvelope.open(raw, sessionKey = { k }) { k } }
        if (sender == null || key == null || message == null) {
            // An unknown meeting looks exactly like a refusal to the sender,
            // which is what makes it drop its session and meet again.
            noteUnopened(env)
            return null
        }
        val pub = runCatching { java.util.Base64.getDecoder().decode(sender.pubKey) }
            .getOrNull() ?: return null
        if (!PeerSealing.verify(message, pub, expectedFromId = sender.id)) return null
        if (message.toId != cachedId) return null

        // A frame may be re-sent while an ACK is in flight; do not announce twice.
        val known = outbox.value.any { it.id == message.id }
        // [link] only calls this for a socket that connected to us directly —
        // the relay's arrivals go through keepArrived, never here.
        roster.sawNearby(sender.id, nowMs())
        // Відчинений лист від них доводить, що наш рядок у них є: без нього не
        // було б чим його запечатати.
        noteIntroduced(sender.id)
        // Відкритий кадр зустрічі доводить те саме, що й вітання: ключ склався.
        if (env.v == PeerEnvelope.VERSION_SESSION) {
            remember(sender.id, knownOf(sender), PeerMeet.Answer.MET)
        }
        // Дзвінок далі в журнал не йде, і це не ощадливість: журнал шифрується
        // й переписується ЦІЛКОМ на кожен запис, а домовляння одного дзвінка —
        // це десятки кадрів із кандидатами. Плюс SDP на диску — це адреси цього
        // апарата, які нікому не потрібні на другий день. Квитанція при цьому
        // пишеться як завжди: кадр справді тут.
        if (PeerCall.isCall(message.body)) {
            callTrace("link", message)
            runCatching { onCall(message) }
            return PeerReceipt.of(key, message.id, message.fromId, message.toId, env.n)
        }
        if (PeerWhere.isWhere(message.body)) {
            runCatching { onWhere(message) }
            return PeerReceipt.of(key, message.id, message.fromId, message.toId, env.n)
        }
        if (PeerTyping.isTyping(message.body)) {
            runCatching { onTyping(message) }
            return PeerReceipt.of(key, message.id, message.fromId, message.toId, env.n)
        }
        if (CashRepeat.seen(outbox.value, message)) {
            return PeerReceipt.of(key, message.id, message.fromId, message.toId, env.n)
        }
        // День і погляд на людину складає лише мій помічник: від людини — підробка, приймаємо й мовчимо.
        if (PeerCard.isAssistantOnly(message.body)) {
            return PeerReceipt.of(key, message.id, message.fromId, message.toId, env.n)
        }
        publishRevised { current ->
            PeerOutbox.enqueue(
                current,
                vaultArrived(message).copy(
                    state = PeerMessage.State.DELIVERED,
                    sealing = if (env.v == PeerEnvelope.VERSION_SESSION) {
                        PeerMessage.Sealing.MEETING_KEY
                    } else {
                        PeerMessage.Sealing.PAIR_KEY
                    },
                ),
            )
        }
        // Ті самі двоє, що й у [keepArrived]: дріт по LAN — окрема дорога, і
        // хвиля, яка навчила лише сховок, лишила б гілку живою тільки в тих,
        // хто далеко одне від одного.
        if (!known && !landedOnDisk(message.id)) return null
        if (!known) {
            runCatching { learnKeys(message) }
            runCatching { answerHistory(message) }
            runCatching { onArrived(message) }
        }
        return PeerReceipt.of(key, message.id, message.fromId, message.toId, env.n)
    }

    /**
     * A refusal that leaves a mark. Everything readable in an unopened frame is
     * the addressing an observer on the LAN can see anyway, so reading it here
     * gives away nothing — and staying silent gives away the whole conversation.
     *
     * Only frames aimed at THIS phone count: the listener is on a shared LAN and
     * a broadcast meant for somebody else is not a drop, it is other people
     * talking.
     */
    private fun noteUnopened(env: PeerEnvelope.Envelope) {
        if (env.to != cachedId) return
        _strangers.value = PeerStranger.note(
            _strangers.value,
            claimedId = env.from,
            inRoster = roster.peers.value.any { it.id == env.from },
            atMs = nowMs(),
        )
    }

    private companion object {
        /** Only for a profile that somehow has no name — not the normal case. */
        const val FALLBACK_NAME = "Nightpost"
        const val MAX_MENTIONS = 50
        const val PUMP_INTERVAL_MS = 2_000L
        const val LAN_HEAD_START_MS = 150L
        const val FLUSH_PARALLEL = 6
        const val RESCAN_MS = 120_000L
        const val BIND_WAIT_TRIES = 40
        const val BIND_WAIT_MS = 50L
        const val DOOR_OFFERS_KEPT = 4
        const val LIVE_SEEN_EVERY_MS = 30_000L
        const val KNOCK_RETRY_MS = 60_000L

        /** Квитанція на живу пропозицію дзвінка: «почув». Не JSON — ні з конвертом, ні зі стуком не сплутати. */
        const val LIVE_HEARD = "PH-HEARD:"

        /** Розбіжність годинників, яку живий кадр дзвінка терпить в обидва боки. */
        const val LIVE_SKEW_MS = 60_000L

        /** Скільки id живих кадрів памʼятаємо: з запасом на кілька дзвінків за вікно вище. */
        const val HEARD_LIVE_KEPT = 512
    }
}

private val CELL_R8 = Regex("^88[0-9a-f]{13}$")
