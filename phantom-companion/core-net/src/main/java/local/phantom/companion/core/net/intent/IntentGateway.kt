package local.phantom.companion.core.net.intent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.peer.PeerIntent

/**
 * «Домашній шлюз» (шлюз А, розд. 07 §3.3.1): старий телефон удома приймає
 * SMS-Наміри від МОЇХ телефонів, передає кадр моєму ПК і повертає щабель 1
 * відповіді SMS.
 *
 * Шлюз СЛІПИЙ: кадр запечатано ключем пари «мій телефон ↔ мій ПК», ключа
 * шлюз не має й не потребує — він переносить байти. Тому довіра тут — лише
 * про те, ЧИЇ SMS брати й СКІЛЬКИ SMS витрачати:
 *  * лише номери зі списку на шлюзі (мої телефони); чужий номер — тиша;
 *  * темп на відправника і денна стеля SMS — з чесним лічильником, бо SMS
 *    коштує грошей, а оператор блокує апарат за сотню SMS на годину;
 *  * назад SMS їде лише щабель 1 (тип 0x02) і не довший за 3 SMS.
 *
 * Дорога до ПК: спершу LAN (`POST /api/v1/intents`, lane=sms) — швидко й
 * без сховку; не вийшло — лист сховком `PH-ING:`, і ПК відповідає листом
 * самому шлюзу (сліпі байти), а шлюз шле їх SMS тому, хто питав.
 */
class IntentGateway(
    private val allowed: () -> Set<String>,
    private val lan: LanPort?,
    private val relay: RelayPort?,
    private val sms: SmsOut,
    private val ledger: LedgerSlot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dailyCap: Int = DAILY_CAP,
    private val perMinute: Int = PER_SENDER_PER_MINUTE,
    /** Зсув місцевого часу від UTC — щоб «сьогодні» було сьогодні для людини. */
    private val zoneOffsetMs: () -> Long = { 0L },
) {

    fun interface LanPort {
        /** Відповідь ПК (кадр) або null — ПК не відповів по LAN чи відповіді цією смугою немає. */
        suspend fun post(frame: ByteArray): Lan
    }

    sealed interface Lan {
        data class Answer(val frame: ByteArray) : Lan
        /** ПК відповів 204: відповіді SMS не буде (маска, вже доставлено, чужий кадр). */
        data object NoAnswer : Lan
        data object Unreachable : Lan
    }

    fun interface RelayPort {
        /** Лист `PH-ING:` моєму ПК; id листа або null. */
        fun send(body: String): String?
    }

    fun interface SmsOut {
        fun send(number: String, text: String): Boolean
    }

    interface LedgerSlot {
        fun read(): String?
        fun write(value: String)
    }

    enum class Outcome { NOT_OURS, STRANGER, RATE_LIMITED, CAPPED, ANSWERED_LAN, NO_ANSWER, VIA_RELAY, FAILED }

    /** Чесний лічильник дня (місцевого). Те, що показує екран шлюзу. */
    @Serializable
    data class Ledger(
        val day: Long = 0,
        val received: Int = 0,
        val forwarded: Int = 0,
        val smsSent: Int = 0,
        val refused: Int = 0,
        /** Відповіді сховком, що чекають SMS: ключ — підказка пари + лічильник, значення — номер. */
        val waiting: Map<String, String> = emptyMap(),
    )

    private val lock = Any()
    private val recent = HashMap<String, ArrayDeque<Long>>()
    private var state: Ledger = ledger.read()?.let { runCatching { JSON.decodeFromString<Ledger>(it) }.getOrNull() } ?: Ledger()

    fun ledger(): Ledger = synchronized(lock) { rolled() }

    val cap: Int get() = dailyCap

    /** SMS прийшла на шлюз. Чужа SMS (не Намір) — [Outcome.NOT_OURS], її не чіпаємо. */
    suspend fun onSms(from: String, text: String): Outcome {
        val frame = IntentSms.decode(text) ?: return Outcome.NOT_OURS
        if ((frame[0].toInt() and 0xFF) != PeerIntent.INTENT) return Outcome.NOT_OURS
        val sender = allowedSender(from) ?: return refuse(Outcome.STRANGER)
        if (!takeSlot(sender)) return refuse(Outcome.RATE_LIMITED)
        if (ledger().smsSent >= dailyCap) return refuse(Outcome.CAPPED)
        change { it.copy(received = it.received + 1) }

        when (val got = lan?.post(frame) ?: Lan.Unreachable) {
            is Lan.Answer -> {
                change { it.copy(forwarded = it.forwarded + 1) }
                return if (smsBack(sender, got.frame)) Outcome.ANSWERED_LAN else Outcome.FAILED
            }
            Lan.NoAnswer -> {
                change { it.copy(forwarded = it.forwarded + 1) }
                return Outcome.NO_ANSWER
            }
            Lan.Unreachable -> Unit
        }
        val port = relay ?: return Outcome.FAILED
        port.send(PeerIntent.GATEWAY_TAG + IntentSms.encode(frame)) ?: return Outcome.FAILED
        change {
            it.copy(forwarded = it.forwarded + 1, waiting = (it.waiting + (keyOf(frame) to sender)).entries.toList().takeLast(MAX_WAITING).associate { e -> e.key to e.value })
        }
        return Outcome.VIA_RELAY
    }

    /** Лист від мого ПК: відповідь на Намір, що пішов сховком через шлюз. */
    fun onLetter(body: String): Boolean {
        val frame = PeerIntent.answerFrame(body) ?: return false
        val key = keyOf(frame)
        val number = ledger().waiting[key] ?: return false
        change { it.copy(waiting = it.waiting - key) }
        if (ledger().smsSent >= dailyCap) {
            change { it.copy(refused = it.refused + 1) }
            return false
        }
        return smsBack(number, frame)
    }

    // ── нутрощі ──────────────────────────────────────────────────────────────

    private fun smsBack(number: String, frame: ByteArray): Boolean {
        if (frame.size < PeerIntent.SEAL_OVERHEAD || (frame[0].toInt() and 0xFF) != PeerIntent.ANSWER) return false
        if (IntentSms.parts(frame) > MAX_PARTS) return false
        val parts = IntentSms.parts(frame)
        if (ledger().smsSent + parts > dailyCap) {
            change { it.copy(refused = it.refused + 1) }
            return false
        }
        if (!sms.send(number, IntentSms.encode(frame))) return false
        change { it.copy(smsSent = it.smsSent + parts) }
        return true
    }

    private fun allowedSender(from: String): String? {
        val theirs = digits(from)
        if (theirs.length < MIN_DIGITS) return null
        return allowed().firstOrNull { mine ->
            val d = digits(mine)
            d.length >= MIN_DIGITS && d.takeLast(MIN_DIGITS) == theirs.takeLast(MIN_DIGITS)
        }
    }

    private fun takeSlot(sender: String): Boolean = synchronized(lock) {
        val now = clock()
        val window = recent.getOrPut(digits(sender).takeLast(MIN_DIGITS)) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= 60_000L) window.removeFirst()
        if (window.size >= perMinute) return false
        window.addLast(now)
        true
    }

    private fun refuse(why: Outcome): Outcome {
        change { it.copy(refused = it.refused + 1) }
        return why
    }

    private fun today(): Long = (clock() + zoneOffsetMs()) / DAY_MS

    /** Новий день — нові лічильники; відповіді, що чекають, лишаються. */
    private fun rolled(): Ledger {
        val day = today()
        if (state.day != day) state = Ledger(day = day, waiting = state.waiting)
        return state
    }

    private fun change(transform: (Ledger) -> Ledger) = synchronized(lock) {
        state = transform(rolled())
        ledger.write(JSON.encodeToString(state))
    }

    private fun keyOf(frame: ByteArray): String =
        frame.copyOfRange(1, 8).joinToString("") { "%02x".format(it) }

    companion object {
        /** Денна стеля SMS шлюзу. Android сам питає людину після 30 SMS за 30 хв. */
        const val DAILY_CAP = 40
        const val PER_SENDER_PER_MINUTE = 4
        /** Щабель 1 найдовшої відповіді — 3 SMS (розд. 07 §3.3.2). */
        const val MAX_PARTS = 3
        private const val MAX_WAITING = 32
        private const val MIN_DIGITS = 9
        private const val DAY_MS = 86_400_000L
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun digits(s: String) = s.filter { it.isDigit() }
    }
}
