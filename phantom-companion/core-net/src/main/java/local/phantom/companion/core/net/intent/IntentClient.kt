package local.phantom.companion.core.net.intent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerIntent
import java.security.SecureRandom
import java.util.Base64

/**
 * Клієнт Намірів на телефоні: склати прохання до мого ПК, повезти його
 * смугами за [IntentBrain] і прийняти відповідь — сховком чи SMS.
 *
 * Світ приходить портами, тож тут немає ні андроїда, ні сітки: [PcLink] дає
 * ключ пари з моїм ПК, [RelayPort] — лист сховком і квитанцію на нього,
 * [SmsPort] — SMS на мій шлюз (лише збірка `field`), [JournalSlot] — диск.
 */
class IntentClient(
    private val pc: PcLink,
    private val relay: RelayPort,
    private val sms: SmsPort?,
    private val slot: JournalSlot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: (Int) -> ByteArray = { n -> ByteArray(n).also { RNG.nextBytes(it) } },
) {

    /** Мій ПК, спарений як «мій пристрій»: ключ пари і два імені. */
    data class Pc(val phoneId: String, val pcId: String, val pairKey: ByteArray)

    fun interface PcLink {
        fun ownPc(): Pc?
    }

    interface RelayPort {
        /** Лист моєму ПК; id листа, або null — не вдалося навіть покласти в чергу. */
        fun send(pcId: String, body: String): String?

        /** ПК дав квитанцію на цей лист. */
        fun delivered(letterId: String): Boolean

        /** Сховок досяжний зараз. */
        fun ready(): Boolean
    }

    interface SmsPort {
        fun policy(): IntentBrain.SmsPolicy

        /** Текст на номер мого шлюзу. True — система взяла SMS до відправки. */
        fun send(text: String): Boolean
    }

    interface JournalSlot {
        fun read(): String?
        fun write(value: String)
    }

    @Serializable
    private data class Saved(val records: List<IntentRecord> = emptyList(), val lastCounter: Long = 0L)

    private val lock = Any()
    private var lastCounter: Long
    private val _journal: MutableStateFlow<List<IntentRecord>>

    init {
        val saved = slot.read()?.let { runCatching { JSON.decodeFromString<Saved>(it) }.getOrNull() } ?: Saved()
        lastCounter = saved.lastCounter
        _journal = MutableStateFlow(saved.records)
    }

    val journal: StateFlow<List<IntentRecord>> = _journal.asStateFlow()

    private fun smsPolicy(): IntentBrain.SmsPolicy = sms?.policy() ?: IntentBrain.SmsPolicy.NONE

    // ── прохання ─────────────────────────────────────────────────────────────

    fun alerts(oblastId: String): IntentRecord? {
        val index = PeerIntent.OBLAST_ORDER.indexOf(oblastId)
        if (index < 0) return null
        return ask(PeerIntent.OP_ALERTS, byteArrayOf(index.toByte(), 0, 0, 0), "Тривоги: $oblastId", 2)
    }

    /** «Сирена-маяк»: підписаний знімок тривог через мій ПК — 92 Б, одна SMS. */
    fun siren(): IntentRecord? = ask(PeerIntent.OP_SIREN, ByteArray(0), "Маяк тривог", 2)

    fun whoOnline(peerNodeId: String, name: String): IntentRecord? {
        val link = pc.ownPc() ?: return null
        return ask(PeerIntent.OP_WHO_ONLINE, PeerIntent.contactTag(link.pairKey, peerNodeId), "Чи на звʼязку $name", 2)
    }

    /** [anchor] — id листа людини в розмові з PHANTOM, під яким веб покаже смугу й відповідь. */
    fun question(text: String, anchor: String = ""): IntentRecord? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        return ask(PeerIntent.OP_ASK, clip(clean, MAX_ARGS_BYTES), clean, 5, anchor)
    }

    /** Прохання, що виросло з листа [anchor], або null. */
    fun forAnchor(anchor: String): IntentRecord? =
        if (anchor.isEmpty()) null else journal.value.firstOrNull { it.anchor == anchor }

    fun pageGist(url: String): IntentRecord? {
        val clean = url.trim()
        if (clean.isEmpty() || clean.toByteArray(Charsets.UTF_8).size > MAX_ARGS_BYTES) return null
        return ask(PeerIntent.OP_PAGE_GIST, clean.toByteArray(Charsets.UTF_8), clean, 30)
    }

    /** Новий Намір: запечатати, записати, повезти. Null — ПК не спарено. */
    fun ask(op: Int, args: ByteArray, title: String, deadlineMin: Int, anchor: String = ""): IntentRecord? {
        val link = pc.ownPc() ?: return null
        val now = clock()
        val replyTo = IntentBrain.replyTo(op, smsPolicy())
        val prio = IntentBrain.prioOf(op)
        val record = synchronized(lock) {
            val counter = nextCounter(now)
            val intent = PeerIntent.Intent(
                id = random(PeerIntent.ID_BYTES),
                op = op,
                createdMin = PeerIntent.minutesOf(now),
                deadlineMin = deadlineMin,
                flags = (prio and 0x3) shl 6,
                replyTo = replyTo,
                args = args,
            )
            val frame = PeerIntent.sealIntent(intent, link.pairKey, link.phoneId, link.pcId, counter)
            IntentRecord(
                idHex = hex(intent.id),
                op = op,
                title = title.take(TITLE_MAX),
                createdMs = now,
                deadlineMs = now + deadlineMin * 60_000L,
                counter = counter,
                prio = prio,
                replyTo = replyTo,
                frameB64 = B64.encodeToString(frame),
                anchor = anchor,
            ).also { change { j -> IntentJournal.add(j, it) } }
        }
        step()
        return journal.value.firstOrNull { it.idHex == record.idHex }
    }

    /**
     * Один хід мозку над усіма відкритими проханнями. Кличе його таймер
     * (раз на кілька секунд), зміна мережі й кожна квитанція.
     */
    fun step() {
        val now = clock()
        val policy = smsPolicy()
        val ready = relay.ready()
        val link = pc.ownPc()
        for (record in journal.value.filter { it.pending }) {
            val letter = record.tries.firstOrNull { it.lane == "relay" }?.letterId.orEmpty()
            val delivered = letter.isNotEmpty() && relay.delivered(letter)
            if (delivered) change { IntentJournal.atPc(it, record.idHex) }
            val env = IntentBrain.Env(now, ready, delivered, policy)
            for (action in IntentBrain.next(record, env)) {
                when (action) {
                    IntentBrain.Action.Expire -> change { IntentJournal.expired(it, record.idHex) }
                    IntentBrain.Action.SendRelay -> {
                        if (link == null) continue
                        val id = relay.send(link.pcId, PeerIntent.TAG + B64.encodeToString(frame(record))) ?: continue
                        change { IntentJournal.tried(it, record.idHex, LaneTry("relay", now, id)) }
                    }
                    IntentBrain.Action.SendSms -> {
                        val port = sms ?: continue
                        if (port.send(IntentSms.encode(frame(record)))) {
                            change { IntentJournal.tried(it, record.idHex, LaneTry("sms", now)) }
                        }
                    }
                }
            }
        }
    }

    // ── відповіді ────────────────────────────────────────────────────────────

    /** Лист від мого ПК сховком. True — це була відповідь на моє прохання. */
    fun onLetter(fromId: String, body: String): Boolean {
        val link = pc.ownPc() ?: return false
        if (fromId != link.pcId) return false
        val frame = PeerIntent.answerFrame(body) ?: return false
        return take(frame, "relay", link)
    }

    /** SMS від мого шлюзу. True — це була відповідь на моє прохання. */
    fun onSms(text: String): Boolean {
        val link = pc.ownPc() ?: return false
        val frame = IntentSms.decode(text) ?: return false
        return take(frame, "sms", link)
    }

    private fun take(frame: ByteArray, lane: String, link: Pc): Boolean {
        if (frame.size < PeerIntent.SEAL_OVERHEAD) return false
        val counter = PeerIntent.counterOf(frame)
        val record = journal.value.firstOrNull { it.counter == counter } ?: return false
        val opened = PeerIntent.openAnswer(frame, link.pairKey, link.phoneId, link.pcId, unhex(record.idHex))
            ?: return false
        val answer = AnswerRecord(opened.rung, opened.status, B64.encodeToString(opened.body), lane, clock())
        change { IntentJournal.answered(it, record.idHex, answer) }
        return true
    }

    // ── нутрощі ──────────────────────────────────────────────────────────────

    /**
     * Лічильник ніколи не повторюється в межах пари: він — частина nonce.
     * Не менший за секунди від епохи Намірів, тож навіть стертий журнал не
     * поверне його назад.
     */
    private fun nextCounter(now: Long): Long {
        val floor = ((now - PeerIntent.EPOCH_MS) / 1000L).coerceAtLeast(0L)
        lastCounter = maxOf(lastCounter + 1, floor) and 0xFFFF_FFFFL
        return lastCounter
    }

    private fun frame(record: IntentRecord): ByteArray = Base64.getDecoder().decode(record.frameB64)

    private var savedCounter = lastCounter

    /** Кожна зміна лягає на диск до повернення: прохання, що зникло після перезапуску, — неправда. */
    private fun change(transform: (List<IntentRecord>) -> List<IntentRecord>) = synchronized(lock) {
        val next = transform(_journal.value)
        if (next == _journal.value && savedCounter == lastCounter) return@synchronized
        _journal.value = next
        slot.write(JSON.encodeToString(Saved(next, lastCounter)))
        savedCounter = lastCounter
    }

    companion object {
        /** Аргументи Наміру: запит мусить влізти в 3 SMS (360 Б кадру мінус заголовки). */
        const val MAX_ARGS_BYTES = 280
        private const val TITLE_MAX = 200
        private val RNG = SecureRandom()
        private val B64 = Base64.getEncoder().withoutPadding()
        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        /** UTF-8 не довше [max] байтів, не розриваючи літеру. */
        internal fun clip(text: String, max: Int): ByteArray {
            val raw = text.toByteArray(Charsets.UTF_8)
            if (raw.size <= max) return raw
            var end = max
            while (end > 0 && (raw[end].toInt() and 0xC0) == 0x80) end--
            return raw.copyOfRange(0, end)
        }
    }
}
