package local.phantom.companion.core.net.intent

import kotlinx.serialization.Serializable
import local.phantom.companion.core.net.peer.PeerIntent

/**
 * «Мої прохання» — журнал Намірів телефона і їхні стани (розд. 07 §2.3).
 *
 *     СТВОРЕНО → ВИЙШОВ(смуга…) → У ВИКОНАВЦЯ → ВІДПОВІДЬ(щабель, смуга)
 *                             ↘ ПРОСТРОЧЕНО (дедлайн минув; запис лишається видимим)
 *
 * Журнал показує лише правду: якою смугою прохання пішло й коли, чи ПК
 * підтвердив, що отримав, і якою смугою прийшла відповідь. «Надіслано» без
 * квитанції тут не буває — є «пішло сховком» і окремо «ПК отримав».
 */
@Serializable
enum class Phase { SENT, AT_PC, ANSWERED, EXPIRED }

@Serializable
data class LaneTry(
    /** `relay` або `sms`. */
    val lane: String,
    val atMs: Long,
    /** Лист сховком — його id у журналі сітки; квитанція на нього = «ПК отримав». */
    val letterId: String = "",
)

@Serializable
data class AnswerRecord(
    val rung: Int,
    val status: Int,
    /** Тіло відповіді, base64 (без печатки — вона вже знята). */
    val bodyB64: String,
    val lane: String,
    val atMs: Long,
)

@Serializable
data class IntentRecord(
    val idHex: String,
    val op: Int,
    /** Що людина просила — для рядка в журналі. Лише на цьому телефоні. */
    val title: String,
    val createdMs: Long,
    val deadlineMs: Long,
    val counter: Long,
    val prio: Int,
    val replyTo: Int,
    /** Запечатаний кадр — той самий байт у байт на кожній смузі (відсів за id на ПК). */
    val frameB64: String,
    val tries: List<LaneTry> = emptyList(),
    val phase: Phase = Phase.SENT,
    val answer: AnswerRecord? = null,
    /** Лист людини в розмові з PHANTOM, під яким малювати це прохання; порожньо — прохання поза розмовою. */
    val anchor: String = "",
) {
    fun tried(lane: String): Boolean = tries.any { it.lane == lane }

    val pending: Boolean get() = phase == Phase.SENT || phase == Phase.AT_PC

    /** Відповідь уже повна: щабель 2, або щабель 1, коли ширшої смуги немає. */
    val complete: Boolean
        get() = answer != null && (answer.rung >= 2 || replyTo and PeerIntent.Lane.RELAY == 0)
}

/** Чисті переходи журналу. Жоден не губить уже отриманої правди. */
object IntentJournal {

    /** Скільки записів тримати. Старіші завершені йдуть першими. */
    const val MAX = 64

    fun add(journal: List<IntentRecord>, record: IntentRecord): List<IntentRecord> {
        if (journal.any { it.idHex == record.idHex }) return journal
        val next = journal + record
        if (next.size <= MAX) return next
        val drop = next.firstOrNull { !it.pending } ?: next.first()
        return next - drop
    }

    fun tried(journal: List<IntentRecord>, idHex: String, attempt: LaneTry): List<IntentRecord> =
        journal.map { if (it.idHex == idHex && !it.tried(attempt.lane)) it.copy(tries = it.tries + attempt) else it }

    /** Квитанція ПК на лист сховком: «у виконавця». Лише вперед, ніколи назад. */
    fun atPc(journal: List<IntentRecord>, idHex: String): List<IntentRecord> =
        journal.map { if (it.idHex == idHex && it.phase == Phase.SENT) it.copy(phase = Phase.AT_PC) else it }

    /**
     * Відповідь. Щабель N заступає N−1 (§9.2), але не навпаки: SMS із
     * коротким текстом, що спізнилась за повною відповіддю сховком, нічого
     * не затирає.
     */
    fun answered(journal: List<IntentRecord>, idHex: String, answer: AnswerRecord): List<IntentRecord> =
        journal.map {
            if (it.idHex != idHex) return@map it
            val old = it.answer
            if (old != null && old.rung >= answer.rung) return@map it
            it.copy(phase = Phase.ANSWERED, answer = answer)
        }

    fun expired(journal: List<IntentRecord>, idHex: String): List<IntentRecord> =
        journal.map { if (it.idHex == idHex && it.pending) it.copy(phase = Phase.EXPIRED) else it }
}
