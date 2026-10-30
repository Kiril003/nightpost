package local.phantom.companion.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.peer.PeerCall
import local.phantom.companion.core.net.peer.PeerCallLine
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRoomLive
import org.json.JSONObject

/**
 * Дорога дзвінка між сіткою й рушієм у вебі.
 *
 * До цього дороги не було взагалі: рушій у зібраному веб-месенджері складав
 * пропозицію й слав її мостом, а міст робив `POST` на
 * `http://<адреса>/api/v1/messenger/call/inbound` — адресу, якої на телефоні не
 * слухає ніхто. Жоден дзвінок не доходив, і провалювався він на транспорті, ще
 * до того, як хтось згадає про звук.
 *
 * **Рушія тут немає й не буде.** Він є, він повний і він у вебі:
 * `RTCPeerConnection`, обмін ICE, сторож затримки, екран із «взяти/відхилити».
 * Тіло сигналу возиться ЦІЛИМ і віддається йому слово в слово
 * ([WEB_SINK] → `window.__phantomCallSignal`); телефон не розбирає SDP і не
 * складає його назад.
 *
 * Телефон робить те, чого веб зробити не може, бо WebView живе лише поки
 * месенджер на екрані:
 *  1. везе кадр запечатаним конвертом по [PeerMesh];
 *  2. підіймає апарат, коли дзвонять у закритий застосунок;
 *  3. тримає сигнали, що приїхали до того, як веб підʼєднався, і віддає їх,
 *     щойно він зʼявиться — інакше пропозиція, яка приїхала за мить до того,
 *     як людина відкрила месенджер, гинула б у тиші;
 *  4. відхиляє, не відкриваючи застосунку («взяти» вимагає мікрофона й рушія,
 *     «відхилити» — рівно одного кадру).
 *
 * ## Кімната, а не лінія
 *
 * [PeerCallLine.Room] тримає СПИСОК ніг. [invite] заводить окрему ногу на
 * КОЖНОГО учасника гілки — це і є сітка: N розмов з одним номером дзвінка, а
 * не одна розмова «з групою». До 16.09 [invite] (тоді `postToGroup`) писала
 * одну ногу на `group.id`, і другий учасник, який спробував би дотягнутись до
 * третього, отримував «зайнято».
 */
class PeerCallHub(
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Кадр назад тому самому апарату. Правда — коли його справді прийняли. */
    private val send: suspend (peerId: String, frame: String) -> Boolean,
    /** Чи є пряма дорога до цього апарата просто зараз. */
    private val reachable: (peerId: String) -> Boolean = { true },
    /** Лінія змінилась: підняти апарат, погасити плашку. */
    private val onLine: (PeerCallLine.Line) -> Unit = {},
    private val selfId: () -> String = { "" },
    /** Склад гілки, як його знає цей телефон. Null — такої гілки тут немає. */
    private val groupMembers: (groupId: String) -> List<String>? = { null },
    /** Відео вмикнули чи вимкнули посеред розмови: служба мусить змінити свій тип. */
    private val onVideo: (Boolean) -> Unit = {},
    /** Імʼя людини зі свого списку. Кадр сітки імені не несе, а рушій вебу читає лише `display_name`. */
    private val nameOf: (peerId: String) -> String? = { null },
    /** «Дзвінки» в приватності: кому дозволено, щоб у цього телефона задзвонило. */
    private val accepts: (peerId: String) -> Boolean = { true },
) {

    private val _room = MutableStateFlow<PeerCallLine.Room?>(null)

    /** Правда про дзвінок: номер кімнати й стан кожної ноги окремо. */
    val room: StateFlow<PeerCallLine.Room?> = _room.asStateFlow()

    private val _line = MutableStateFlow<PeerCallLine.Line>(PeerCallLine.Line.Idle)

    /**
     * Проєкція кімнати на один стан — для шторки й плитки, куди список ніг не
     * влізе. Хто хоче знати, ХТО саме в розмові, читає [room].
     */
    val line: StateFlow<PeerCallLine.Line> = _line.asStateFlow()

    private val _lineOpen = MutableStateFlow(false)

    /** Дзвонить, набирає або говорить. Для замкненого екрана й сну WebView. */
    val lineOpen: StateFlow<Boolean> = _lineOpen.asStateFlow()

    private val _video = MutableStateFlow(false)

    val video: StateFlow<Boolean> = _video.asStateFlow()

    private val _live = MutableStateFlow<Map<String, PeerRoomLive.Room>>(emptyMap())

    /** Розмови гілок, про які сказали учасники, — за номером дзвінка. */
    val live: StateFlow<Map<String, PeerRoomLive.Room>> = _live.asStateFlow()

    private var mine: PeerRoomLive.Mine? = null

    /** Куди віддавати тіло сигналу. Null — веба на екрані немає. */
    private var web: ((String) -> Unit)? = null

    // WebView підʼєднується раніше, ніж бандл оголосив приймача: кадр у цю мить гинув.
    private var ready = false

    /**
     * Те, що приїхало, поки веба не було.
     *
     * Обмежене: пропозиція живе хвилину ([PeerCall.RING_LIFE_MS]), а кандидатів
     * за цей час набігає десятки. Черга без стелі — це памʼять, яку наповнює
     * чужий апарат. Стеля помножена на [PeerCallLine.MAX_LEGS]: у кімнаті з
     * вісьмома ногами кандидатів рівно у вісім разів більше, і черга на одну
     * ногу викинула б опис сьомої, поки людина відкриває застосунок.
     */
    private val pending = ArrayDeque<String>()

    private var scope: CoroutineScope? = null
    private var ticker: Job? = null

    fun attach(owner: CoroutineScope) {
        scope = owner
        if (ticker?.isActive == true) return
        ticker = owner.launch {
            while (isActive) {
                delay(TICK_MS)
                roomStep()
                _live.update { PeerRoomLive.expire(it, nowMs()) }
                val verdict = PeerCallLine.expire(_room.value, nowMs()) ?: continue
                settle(verdict)
            }
        }
    }

    /**
     * Веб відкрився. Кадри їдуть до нього лише після [webReady] — у тому
     * порядку, в якому приїхали: опис мусить лягти раніше за кандидатів.
     */
    fun attachWeb(sink: (String) -> Unit) = synchronized(pending) {
        web = sink
        flushLocked()
    }

    /** Бандл оголосив `__phantomCallSignal`. Кожен новий WebView каже це сам. */
    fun webReady() = synchronized(pending) {
        ready = true
        flushLocked()
    }

    fun detachWeb() = synchronized(pending) {
        web = null
        ready = false
    }

    private fun flushLocked() {
        val sink = web?.takeIf { ready } ?: return
        while (pending.isNotEmpty()) runCatching { sink(pending.removeFirst()) }
    }

    /**
     * Кадр приїхав із сітки. Уже відчинений ключем пари й перевірений
     * підписом — [PeerMesh] не кличе сюди нічого іншого.
     */
    fun absorb(message: PeerMessage) {
        if (PeerRoomLive.isLive(message.body)) {
            val note = PeerRoomLive.decode(message.body) ?: return
            val members = groupMembers(note.groupId)
            _live.update { PeerRoomLive.absorb(it, message.fromId, note, members, nowMs()) }
            return
        }
        val signal = PeerCall.decodeSignal(message.body)
        // Лише новий дзвінок: нога вже живої кімнати — це розмова, яку людина сама прийняла.
        if (signal?.what == PeerCall.Signalling.OFFER && _room.value?.callId != signal.callId &&
            !runCatching { accepts(message.fromId) }.getOrDefault(true)
        ) {
            val frame = frameFor(PeerCallLine.Reply(signal.callId, PeerCall.Signalling.HANGUP, PeerCallLine.CALLS_OFF)) ?: return
            scope?.launch { runCatching { send(message.fromId, frame) } }
            return
        }
        val verdict = PeerCallLine.apply(_room.value, message.fromId, message.body, nowMs())
        val named = if (signal?.what == PeerCall.Signalling.OFFER) withName(verdict, message.fromId) else verdict
        settle(inGroup(named, message))
        if (signal == null) return
        if (signal.what == PeerCall.Signalling.OFFER && _lineOpen.value && _room.value?.callId == signal.callId) {
            setVideo(isVideo(signal.payload))
        }
    }

    private fun withName(verdict: PeerCallLine.Verdict, fromId: String): PeerCallLine.Verdict {
        val payload = verdict.toWeb ?: return verdict
        val name = runCatching { nameOf(fromId) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return verdict
        val body = runCatching { JSONObject(payload) }.getOrNull() ?: return verdict
        return verdict.copy(toWeb = body.put("display_name", name).toString())
    }

    private fun inGroup(verdict: PeerCallLine.Verdict, message: PeerMessage): PeerCallLine.Verdict {
        val room = verdict.room ?: return verdict
        if (room.groupId != null) return verdict
        val signal = PeerCall.decodeSignal(message.body) ?: return verdict
        if (signal.what != PeerCall.Signalling.OFFER || signal.callId != room.callId) return verdict
        val groupId = PeerRoomLive.groupOfOffer(signal.payload) ?: return verdict
        if (groupMembers(groupId)?.contains(message.fromId) != true) return verdict
        return verdict.copy(room = room.copy(groupId = groupId))
    }

    /**
     * Веб щойно надіслав кадр ОДНІЙ нозі. Повертає, чи його СПРАВДІ прийняли
     * на тому кінці: «набираю» над кадром у нікуди був рівно тією вадою,
     * заради якої усе це.
     *
     * [payload] — тіло, яке склав рушій, слово в слово. Телефон додає до нього
     * лише `kind`, `call_id` і `from_node_id`, бо саме їх читає рушій на тому
     * боці, а мостовий виклик їх не несе.
     */
    suspend fun post(
        peerId: String,
        kind: String,
        callId: String,
        payload: JSONObject,
        myNodeId: String,
        groupId: String? = null,
    ): Boolean {
        val what = PeerCall.Signalling.ofWeb(kind) ?: return false
        if (!reachable(peerId)) return false
        val frame = frameOf(kind, what, callId, payload, myNodeId) ?: return false
        if (what == PeerCall.Signalling.OFFER) setVideo(isVideo(payload.toString()))
        val landed = runCatching { send(peerId, frame) }.getOrDefault(false)
        // Кімната йде за рушієм навіть коли кадр не доїхав: людина вже бачить
        // свій екран дзвінка, і розходитись із ним не можна. «Не доїхало»
        // каже відповідь, а не мовчазно інший стан.
        noteSent(peerId, callId, what, groupId)
        return landed
    }

    /**
     * Запросити гілку. Кожному учаснику — СВОЯ нога, свій кадр, свій вирок.
     *
     * Це не розсилка одного стану на всіх: нога — це зʼєднання з КОНКРЕТНИМ
     * апаратом, і людина мусить бачити окремо, кому подзвонило, кому ні, а
     * хто вже говорить. Одне булеве значення на N людей ховало б рівно те, що
     * їй треба знати.
     *
     * Стеля тут ЗАМІРЯНА, а не вигадана ([PeerCallLine.MAX_LEGS]): кімната,
     * яка взяла більше ніг, ніж витримує апарат, не стає більшою — вона стає
     * тихою. Тим, хто не влазить, кадр не шлеться взагалі, і [GroupPost.tooMany]
     * каже скільком саме, щоб поверхня могла це промовити.
     */
    suspend fun invite(
        groupId: String,
        memberIds: List<String>,
        kind: String,
        callId: String,
        payload: JSONObject,
        myNodeId: String,
    ): GroupPost {
        val what = PeerCall.Signalling.ofWeb(kind) ?: return GroupPost(0, memberIds.size, 0)
        val frame = frameOf(kind, what, callId, payload, myNodeId)
            ?: return GroupPost(0, memberIds.size, 0)
        if (what == PeerCall.Signalling.OFFER) setVideo(isVideo(payload.toString()))

        // Ті, хто вже має ногу в цій кімнаті, місця не займають удруге.
        val current = _room.value?.takeIf { it.callId == callId }
        val already = PeerCallLine.liveLegs(current).map { it.peerId }.toSet()
        val fresh = memberIds.filterNot { it in already }
        val room = PeerCallLine.roomLeft(current)
        val taking = fresh.take(room)
        val tooMany = fresh.size - taking.size

        var delivered = 0
        for (memberId in already.intersect(memberIds.toSet()) + taking) {
            if (!reachable(memberId)) {
                // Ноги немає дороги — але нога є, і вона мусить існувати в
                // кімнаті як «не дійшло», а не зникнути з екрана мовчки.
                noteSent(memberId, callId, what, groupId)
                continue
            }
            if (runCatching { send(memberId, frame) }.getOrDefault(false)) delivered++
            noteSent(memberId, callId, what, groupId)
        }
        return GroupPost(delivered, memberIds.size, tooMany)
    }

    /**
     * Реальні стани з N учасниками — як і в тексту: одне «так/ні» на всіх було
     * б брехнею. [total] — скільком копія мала піти ([PeerGroup.fanOut] уже
     * викинув самого відправника); [delivered] — скільком СПРАВДІ пішла;
     * [tooMany] — скільком не пішла тому, що кімната вперлась у заміряну стелю.
     */
    data class GroupPost(val delivered: Int, val total: Int, val tooMany: Int = 0)

    /** Відхилити зі шторки, не відкриваючи застосунку. */
    suspend fun declineNow(): Boolean {
        val verdict = PeerCallLine.decline(_room.value) ?: return false
        return settleAndSend(verdict)
    }

    /** Вийти з кімнати: кожній живій нозі — окремий «поклав». */
    suspend fun leaveNow(): Boolean {
        val verdict = PeerCallLine.leave(_room.value) ?: return false
        return settleAndSend(verdict)
    }

    /** Забрати одну ногу, не роняючи решти розмови. */
    suspend fun dropLeg(peerId: String): Boolean {
        val verdict = PeerCallLine.drop(_room.value, peerId) ?: return false
        return settleAndSend(verdict)
    }

    /** «Прочитано»: поверхня показала, чому дзвінка не стало. */
    fun clearOver() {
        if (_line.value is PeerCallLine.Line.Over) {
            _room.value = null
            publish(PeerCallLine.Line.Idle, changed = true)
        }
    }

    // ── внутрішнє ────────────────────────────────────────────────────────

    private fun frameOf(
        kind: String,
        what: PeerCall.Signalling,
        callId: String,
        payload: JSONObject,
        myNodeId: String,
    ): String? {
        val body = JSONObject(payload.toString())
            .put("kind", kind)
            .put("call_id", callId)
            .put("from_node_id", myNodeId)
        return PeerCall.encodeSignal(PeerCall.Signal(callId, what, body.toString()))
    }

    private fun noteSent(
        peerId: String,
        callId: String,
        what: PeerCall.Signalling,
        groupId: String?,
    ) {
        val verdict = PeerCallLine.noteSent(_room.value, peerId, callId, what, nowMs(), groupId)
        _room.value = verdict.room
        publish(PeerCallLine.face(verdict.room), verdict.changed)
    }

    private suspend fun settleAndSend(verdict: PeerCallLine.Verdict): Boolean {
        _room.value = verdict.room
        publish(PeerCallLine.face(verdict.room), verdict.changed)
        if (verdict.out.isEmpty()) return true
        // Кожній нозі свій кадр і свій вирок: той, кому «поклав» не доїхав,
        // мусить бути видимий окремо від тих, кому доїхав.
        var all = true
        for (out in verdict.out) {
            val frame = frameFor(out.reply)
            if (frame == null) {
                all = false
                continue
            }
            if (!runCatching { send(out.toPeerId, frame) }.getOrDefault(false)) all = false
        }
        return all
    }

    private fun settle(verdict: PeerCallLine.Verdict) {
        _room.value = verdict.room
        verdict.toWeb?.let(::toWeb)
        for (out in verdict.out) {
            val frame = frameFor(out.reply) ?: continue
            scope?.launch { runCatching { send(out.toPeerId, frame) } }
        }
        publish(PeerCallLine.face(verdict.room), verdict.changed)
    }

    /**
     * Шторці кажуть лише тоді, коли ЇЙ є що перемалювати. Кімната міняється
     * частіше за проєкцію: девʼята нога, що стала в чергу під час розмови,
     * лишає обличчя тим самим — і дзвеніти через неї не можна.
     */
    private fun publish(face: PeerCallLine.Line, changed: Boolean) {
        val was = _line.value
        _line.value = face
        val open = face is PeerCallLine.Line.Ringing || face is PeerCallLine.Line.Dialing ||
            face is PeerCallLine.Line.Talking
        _lineOpen.value = open
        if (!open) _video.value = false
        if (changed && face != was) onLine(face)
        roomStep()
    }

    private fun roomStep() {
        val say = synchronized(this) {
            val step = PeerRoomLive.step(mine, _room.value, _live.value, nowMs())
            mine = step.mine
            step.say
        }
        say.forEach(::announce)
    }

    private fun announce(note: PeerRoomLive.Note) {
        val me = selfId().ifBlank { return }
        val members = groupMembers(note.groupId) ?: return
        _live.update { PeerRoomLive.absorb(it, me, note, members, nowMs()) }
        val frame = PeerRoomLive.encode(note) ?: return
        val owner = scope ?: return
        for (id in members) {
            if (id == me || !reachable(id)) continue
            owner.launch { runCatching { send(id, frame) } }
        }
    }

    /**
     * Тіло складається САМЕ ТУТ, бо саме тут є `org.json`, — і складається в
     * тій формі, яку читає рушій: `{call_id, kind, reason}`. [PeerCallLine]
     * повертає опис, а не готовий рядок, щоб форма не роздвоїлась на два місця.
     */
    private fun frameFor(reply: PeerCallLine.Reply): String? {
        val body = JSONObject()
            .put("call_id", reply.callId)
            .put("kind", reply.what.webKind)
            .put("reason", reply.reason)
        return PeerCall.encodeSignal(
            PeerCall.Signal(reply.callId, reply.what, body.toString()),
        )
    }

    // Службу будимо лише коли лінія вже відкрита: набір з порожньої лінії несе відео в плашку Dialing сам.
    private fun setVideo(video: Boolean) {
        if (_video.value == video) return
        _video.value = video
        if (_lineOpen.value) onVideo(video)
    }

    private fun isVideo(payload: String): Boolean = runCatching {
        Json.parseToJsonElement(payload).jsonObject["media"]?.jsonPrimitive?.contentOrNull == "video"
    }.getOrDefault(false)

    private fun toWeb(payload: String) = synchronized(pending) {
        while (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(payload)
        flushLocked()
    }

    companion object {
        /** Імʼя, яким зібраний веб-месенджер сам себе оголошує приймачем. */
        const val WEB_SINK = "__phantomCallSignal"

        /** Секунда: межа «не взяли» — хвилина, точніше тут нічого не важить. */
        private const val TICK_MS = 1_000L

        /** Опис плюс кандидати — на КОЖНУ ногу повної кімнати, з запасом. */
        private const val MAX_PENDING = 64 * PeerCallLine.MAX_LEGS
    }
}
