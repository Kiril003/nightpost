package local.phantom.companion.core.net.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.crypto.X25519
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Ключі конверта v5 на телефоні — і, головне, коли їх СТИРАТИ (docs/crypto/RELAY_FS.md §2–§4).
 *
 * Мій бік: підписаний SPK обертається щотижня, старий живе ще [SPK_RETENTION_MS]
 * (лист, покладений у сховок до обертання, мусить відчинитися) і стирається.
 * OPK — окрема пачка на кожен контакт; приватний OPK стирається, щойно лист
 * із ним відчинився. Листи лише під SPK — кеш використаних EK до кінця життя
 * цього SPK, щоб повтор кадру не відчинився вдруге.
 *
 * Їхній бік: пачки контактів; кожен OPK віддається для надсилання рівно раз.
 *
 * Стан цілком лежить у [Slot] (на телефоні — зашифрований файл), кожна зміна —
 * на диск до повернення: OPK, стертий лише в памʼяті, після перезапуску
 * воскрес би.
 */
class RelayFsKeys(
    private val slot: Slot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keypair: () -> X25519.Keypair = { X25519.generateKeypair() },
) {

    interface Slot {
        fun read(): String?
        fun write(value: String)
    }

    @Serializable
    data class MySpk(val id: Long, val priv: String, val pub: String, val sig: String, val createdMs: Long, val retiredMs: Long = 0)

    @Serializable
    data class MyOpk(val id: Long, val priv: String, val pub: String, val peer: String, val issuedMs: Long)

    @Serializable
    data class Theirs(
        val peer: String,
        val spkId: Long,
        val spkPub: String,
        val receivedMs: Long,
        val opks: List<RelayFs.OneTime> = emptyList(),
        val gen: Long = 0,
    )

    /** Відчинений лист: лише id — щоб копію, віддану сховком удруге, підтвердити й спалити. */
    @Serializable
    data class Landed(val peer: String, val id: String, val untilMs: Long)

    @Serializable
    data class State(
        val spks: List<MySpk> = emptyList(),
        val opks: List<MyOpk> = emptyList(),
        val nextSpkId: Long = 1,
        val nextOpkId: Long = 1,
        /** «spkId:ekHex» → до коли памʼятати. */
        val seenEk: Map<String, Long> = emptyMap(),
        val theirs: List<Theirs> = emptyList(),
        /** Який SPK я вже віддав кожному контакту. */
        val sentSpk: Map<String, Long> = emptyMap(),
        /** «spkId:ekHex» → відчинений лист. Тексту тут немає й не буде. */
        val landed: Map<String, Landed> = emptyMap(),
    )

    /**
     * Чим запечатано останній лист — у памʼяті, до перезапуску. Після нього
     * екран чесно каже «ще не було», а не вигадує минуле.
     */
    data class Note(val sealing: PeerMessage.Sealing, val why: String, val atMs: Long)

    /** Що віддати відправнику для одного листа. */
    data class Choice(val spkId: Long, val spkPub: ByteArray, val opkId: Long?, val opkPub: ByteArray?)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()
    private val lastOut = ConcurrentHashMap<String, Note>()
    private val lastIn = ConcurrentHashMap<String, Note>()
    private var state: State = slot.read()?.let { runCatching { json.decodeFromString<State>(it) }.getOrNull() } ?: State()

    fun snapshot(): State = synchronized(lock) { state }

    private fun change(transform: (State) -> State) = synchronized(lock) {
        val next = transform(state)
        if (next != state) {
            state = next
            slot.write(json.encodeToString(next))
        }
    }

    private fun b64(raw: ByteArray) = Base64.getEncoder().encodeToString(raw)
    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    // ── мій бік ─────────────────────────────────────────────────────────────

    /** Поточний SPK; обертає й стирає за розкладом. [edPrivate] — мій довічний Ed25519 (підпис). */
    fun currentSpk(edPrivate: ByteArray): MySpk {
        val now = clock()
        change { s ->
            val live = s.spks.filter { it.retiredMs == 0L || now - it.retiredMs < SPK_RETENTION_MS }
            val current = live.firstOrNull { it.retiredMs == 0L }
            if (current != null && now - current.createdMs < SPK_ROTATE_MS) return@change s.copy(spks = live)
            val kp = keypair()
            val fresh = MySpk(s.nextSpkId, b64(kp.privateKey), b64(kp.publicKey), b64(RelayFs.signSpk(edPrivate, s.nextSpkId, kp.publicKey)), now)
            kp.privateKey.fill(0)
            val retired = live.map { if (it.retiredMs == 0L) it.copy(retiredMs = now) else it }
            s.copy(spks = retired + fresh, nextSpkId = s.nextSpkId + 1)
        }
        return synchronized(lock) { state.spks.first { it.retiredMs == 0L } }
    }

    /**
     * Пачка для [peer], якщо вона потрібна: контакт ще не бачив мого поточного
     * SPK або в нього лишилось менше [OPK_LOW] живих OPK. Null — слати нічого.
     */
    fun bundleFor(peer: String, edPrivate: ByteArray): RelayFs.Bundle? {
        val spk = currentSpk(edPrivate)
        val now = clock()
        var issued: List<MyOpk> = emptyList()
        var needed = false
        change { s ->
            val mine = s.opks.filter { it.peer == peer }
            val spkNew = s.sentSpk[peer] != spk.id
            if (!spkNew && mine.size >= OPK_LOW) return@change s
            needed = true
            var nextId = s.nextOpkId
            issued = if (mine.size < OPK_LOW) {
                List(OPK_BATCH) {
                    val kp = keypair()
                    MyOpk(nextId++, b64(kp.privateKey), b64(kp.publicKey), peer, now).also { kp.privateKey.fill(0) }
                }
            } else emptyList()
            // Понад межу — найстаріші цього контакту йдуть першими (лист під таким
            // OPK потім не відчиниться: чесна відмова, а не розшифрування).
            val kept = (mine + issued).takeLast(MAX_OPKS_PER_PEER)
            s.copy(
                opks = s.opks.filter { it.peer != peer } + kept,
                nextOpkId = nextId,
                sentSpk = s.sentSpk + (peer to spk.id),
            )
        }
        if (!needed) return null
        return RelayFs.Bundle(
            spk = RelayFs.SignedPrekey(spk.id, spk.pub, spk.sig),
            opks = issued.map { RelayFs.OneTime(it.id, it.pub) },
            gen = spk.id,
        )
    }

    fun spkPrivate(id: Long): ByteArray? {
        val now = clock()
        return snapshot().spks.firstOrNull { it.id == id && (it.retiredMs == 0L || now - it.retiredMs < SPK_RETENTION_MS) }?.let { unb64(it.priv) }
    }

    /** Лише OPK, виданий САМЕ цьому контакту: чужий OPK не відчиняє листа від нього. */
    fun opkPrivate(peer: String, id: Long): ByteArray? =
        snapshot().opks.firstOrNull { it.id == id && it.peer == peer }?.let { unb64(it.priv) }

    /**
     * Лист відчинився — стерти, що мусить зникнути. False — це повтор (EK уже
     * бачили під цим SPK), і листа не можна приймати.
     */
    fun consume(peer: String, opened: RelayFs.Opened, letterId: String = ""): Boolean {
        val now = clock()
        var fresh = true
        val key = ekKey(opened.spkId, opened.ek)
        change { s0 ->
            val s = s0.copy(landed = s0.landed.filterValues { it.untilMs > now })
            if (letterId.isEmpty()) return@change s
            val landed = (s.landed + (key to Landed(peer, letterId, now + SPK_ROTATE_MS + SPK_RETENTION_MS)))
            val kept = if (landed.size <= MAX_LANDED) landed else landed.entries.sortedBy { it.value.untilMs }.takeLast(MAX_LANDED).associate { it.key to it.value }
            s.copy(landed = kept)
        }
        change { s ->
            val opkId = opened.opkId
            if (opkId != null) {
                val had = s.opks.any { it.id == opkId && it.peer == peer }
                if (!had) {
                    fresh = false
                    return@change s
                }
                return@change s.copy(opks = s.opks.filterNot { it.id == opkId && it.peer == peer })
            }
            val seen = s.seenEk.filterValues { it > now }
            if (key in seen) {
                fresh = false
                return@change s.copy(seenEk = seen)
            }
            s.copy(seenEk = seen + (key to now + SPK_ROTATE_MS + SPK_RETENTION_MS))
        }
        return fresh
    }

    private fun ekKey(spkId: Long, ek: ByteArray): String = spkId.toString() + ":" + ek.joinToString("") { "%02x".format(it) }

    /** Id листа, який ЦЯ копія вже принесла (сховок віддав її вдруге), або null. */
    fun landedId(peer: String, raw: String): String? {
        val f = RelayFs.parse(raw) ?: return null
        if (f.from != peer) return null
        val ek = runCatching { Base64.getDecoder().decode(f.ek) }.getOrNull() ?: return null
        val hit = snapshot().landed[ekKey(f.spk, ek)] ?: return null
        return hit.id.takeIf { hit.peer == peer && hit.untilMs > clock() }
    }

    /** Скільки МОЇХ одноразових ключів зараз видано [peer] і ще не використано. */
    fun issuedTo(peer: String): Int = snapshot().opks.count { it.peer == peer }

    /** Чи є в мене пачка [peer] (і скільки їй мс), щоб слати йому v5. */
    fun theirBundleAgeMs(peer: String): Long? = snapshot().theirs.firstOrNull { it.peer == peer }?.let { clock() - it.receivedMs }

    fun noteOut(peer: String, sealing: PeerMessage.Sealing, why: String = "") { lastOut[peer] = Note(sealing, why, clock()) }
    fun noteIn(peer: String, sealing: PeerMessage.Sealing) { lastIn[peer] = Note(sealing, "", clock()) }
    fun lastOut(peer: String): Note? = lastOut[peer]
    fun lastIn(peer: String): Note? = lastIn[peer]

    // ── їхній бік ───────────────────────────────────────────────────────────

    /** Пачка контакту (підпис уже перевірено [RelayFs.decodeBundle]). */
    fun absorb(peer: String, bundle: RelayFs.Bundle) {
        val now = clock()
        change { s ->
            // Лічильник SPK пішов назад або той самий id з іншим ключем — у контакта
            // новий стан (переустановка, ПК відновлено без файла v5, RELAY_FS.md §11).
            // Його старі OPK стерто, а id видаються наново: черга з нуля.
            val old = s.theirs.firstOrNull { it.peer == peer }
                ?.takeUnless { bundle.spk.id < it.spkId || (bundle.spk.id == it.spkId && bundle.spk.pub != it.spkPub) }
            val queue = ((old?.opks ?: emptyList()) + bundle.opks).distinctBy { it.id }.takeLast(MAX_OPKS_PER_PEER)
            val next = Theirs(peer, bundle.spk.id, bundle.spk.pub, now, queue, bundle.gen)
            s.copy(theirs = s.theirs.filter { it.peer != peer } + next)
        }
    }

    /**
     * Ключі для одного листа до [peer]: OPK, поки є (і він тут же викреслюється —
     * жоден OPK не йде двічі), далі лише SPK. Null — пачки немає або SPK застарів.
     */
    fun nextForSend(peer: String): Choice? {
        val now = clock()
        var choice: Choice? = null
        change { s ->
            val t = s.theirs.firstOrNull { it.peer == peer } ?: return@change s
            if (now - t.receivedMs > SPK_MAX_AGE_MS) return@change s
            val opk = t.opks.firstOrNull()
            choice = Choice(t.spkId, unb64(t.spkPub), opk?.id, opk?.let { unb64(it.pub) })
            if (opk == null) s else s.copy(theirs = s.theirs.map { if (it.peer == peer) it.copy(opks = it.opks.drop(1)) else it })
        }
        return choice
    }

    fun opksLeftFor(peer: String): Int = snapshot().theirs.firstOrNull { it.peer == peer }?.opks?.size ?: 0

    companion object {
        const val SPK_ROTATE_MS = 7L * 24 * 3600 * 1000
        const val SPK_RETENTION_MS = 14L * 24 * 3600 * 1000
        /** Пачка, старша за це, — не для надсилання: її SPK, можливо, уже стерто. */
        const val SPK_MAX_AGE_MS = 10L * 24 * 3600 * 1000
        const val OPK_BATCH = 16
        const val OPK_LOW = 4
        const val MAX_OPKS_PER_PEER = 64
        /** Скільки відчинених листів памʼятати для повторної квитанції. */
        const val MAX_LANDED = 4096
    }
}

/**
 * Лист через сховок: v5, коли можна, і чесний відкат на v3, коли ні.
 * Рішення ухвалює прапорець і наявність пачки контакту — і причина
 * відкату названа, щоб журнал міг показати «листів без прямої секретності».
 */
object RelayFsMail {

    sealed interface Sealed {
        data class V5(val frame: String, val nonceB64: String, val withOpk: Boolean) : Sealed
        data class Fallback(val why: String) : Sealed
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun seal(
        message: PeerMessage,
        me: RelayFs.Identity,
        myDhPrivate: ByteArray,
        them: RelayFs.Identity,
        keys: RelayFsKeys,
        ephemeralPrivate: ByteArray? = null,
    ): Sealed {
        val sealed = sealOnly(message, me, myDhPrivate, them, keys, ephemeralPrivate)
        when (sealed) {
            is Sealed.V5 -> keys.noteOut(message.toId, if (sealed.withOpk) PeerMessage.Sealing.ONE_TIME_KEY else PeerMessage.Sealing.SIGNED_PREKEY)
            is Sealed.Fallback -> keys.noteOut(message.toId, PeerMessage.Sealing.PAIR_KEY, sealed.why)
        }
        return sealed
    }

    private fun sealOnly(
        message: PeerMessage,
        me: RelayFs.Identity,
        myDhPrivate: ByteArray,
        them: RelayFs.Identity,
        keys: RelayFsKeys,
        ephemeralPrivate: ByteArray?,
    ): Sealed {
        if (!RelayFs.Flag.enabled) return Sealed.Fallback("v5 вимкнено")
        val c = keys.nextForSend(message.toId) ?: return Sealed.Fallback("контакт ще не прислав ключів або вони застаріли")
        val plain = json.encodeToString(PeerMessage.serializer(), message.copy(heldNonce = "", heldUntilMs = 0L))
            .toByteArray(Charsets.UTF_8)
        val frame = RelayFs.seal(
            plain, message.fromId, message.toId, me, myDhPrivate, them, c.spkId, c.spkPub, c.opkId, c.opkPub, ephemeralPrivate,
        ) ?: return Sealed.Fallback("лист не запечатався")
        val parsed = RelayFs.parse(frame) ?: return Sealed.Fallback("лист не запечатався")
        if (frame.toByteArray(Charsets.UTF_8).size > PeerWire.MAX_BODY_BYTES) return Sealed.Fallback("лист завеликий")
        return Sealed.V5(frame, parsed.n, c.opkId != null)
    }

    /**
     * Лист v5 від [sender] або null. Ключі стираються лише ПІСЛЯ того, як тег
     * зійшовся і внутрішня заява збіглася із зовнішньою; повтор — null.
     */
    fun open(
        raw: String,
        me: RelayFs.Identity,
        myDhPrivate: ByteArray,
        senderId: String,
        sender: RelayFs.Identity,
        keys: RelayFsKeys,
    ): PeerMessage? {
        val frame = RelayFs.parse(raw) ?: return null
        if (frame.from != senderId) return null
        val opened = RelayFs.open(raw, me, myDhPrivate, sender, keys::spkPrivate) { keys.opkPrivate(senderId, it) } ?: return null
        val message = runCatching {
            json.decodeFromString(PeerMessage.serializer(), String(opened.plaintext, Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (message.fromId != frame.from || message.toId != frame.to || message.id.isEmpty()) return null
        if (!keys.consume(senderId, opened, message.id)) return null
        keys.noteIn(senderId, if (opened.opkId != null) PeerMessage.Sealing.ONE_TIME_KEY else PeerMessage.Sealing.SIGNED_PREKEY)
        return message
    }

    /** Копія, яку сховок віддав удруге: id уже відчиненого листа або null. */
    fun landed(raw: String, senderId: String, keys: RelayFsKeys): String? = keys.landedId(senderId, raw)
}
