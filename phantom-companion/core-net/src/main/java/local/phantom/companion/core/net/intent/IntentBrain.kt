package local.phantom.companion.core.net.intent

import local.phantom.companion.core.net.peer.PeerIntent

/**
 * Мозок смуг v0 — правила, не оптимізатор (розд. 07 §12.1 п. 5, §9.2, §9.3).
 *
 *  * Сховок — першим, завжди: він нічого не коштує і нічого не розкриває
 *    оператору. Лист лягає в чергу сітки й поїде, щойно буде звʼязок.
 *  * SMS — запасна смуга і лише у збірці `field`: одразу, якщо сховку зараз
 *    не дістати; інакше — коли до T_esc = min(30 с, дедлайн/2) ПК не дав
 *    квитанції на лист сховком.
 *  * Щойно ПК підтвердив лист сховком — SMS не йде: виконавець уже має
 *    прохання, а відповідь він сам покладе в сховок.
 *  * SMS розкриває мій номер і номер мого шлюзу оператору (§9.3, правило 2):
 *    для особистого (P2) — лише якщо людина сама дозволила, а в режимі
 *    «Тиха людина» — ніколи, крім SOS (тут SOS немає).
 */
object IntentBrain {

    /** §12.1: «якщо 30 с без квитанції — SMS». */
    const val ESCALATE_MAX_MS = 30_000L

    /** Пріоритет op: публічне (тривоги, маяк тривог) — P3, решта — особисте P2. */
    fun prioOf(op: Int): Int = if (op == PeerIntent.OP_ALERTS || op == PeerIntent.OP_SIREN) 3 else 2

    /** Скільки SMS просити на відповідь (§3.3.4: тривоги й «хто на звʼязку» — 1, ШІ — 2, сторінка — 3). */
    fun smsPartsOf(op: Int): Int = when (op) {
        PeerIntent.OP_ASK -> 2
        PeerIntent.OP_PAGE_GIST -> 3
        else -> 1
    }

    data class SmsPolicy(
        /** Збірка вміє SMS (лише `field`) і людина вписала номер свого шлюзу. */
        val available: Boolean,
        /** «Дозволити SMS для моїх прохань» — для особистого (P2). */
        val personalAllowed: Boolean = false,
        /** «Тиха людина»: жодних SMS. */
        val quiet: Boolean = false,
    ) {
        fun allows(prio: Int): Boolean = available && !quiet && (prio >= 3 || personalAllowed)

        companion object {
            val NONE = SmsPolicy(available = false)
        }
    }

    /** reply_to для нового Наміру: сховок завжди, SMS — якщо політика дозволяє. */
    fun replyTo(op: Int, sms: SmsPolicy): Int =
        PeerIntent.Lane.replyTo(
            smsParts = if (sms.allows(prioOf(op))) smsPartsOf(op) else 0,
            relay = true,
        )

    sealed interface Action {
        data object SendRelay : Action
        data object SendSms : Action
        data object Expire : Action
    }

    data class Env(
        val nowMs: Long,
        /** Сховок досяжний зараз (є мережа і сервер відповідав). */
        val relayReady: Boolean,
        /** Лист сховком цього Наміру отримав квитанцію ПК. */
        val relayDelivered: Boolean,
        val sms: SmsPolicy,
    )

    fun escalateAtMs(record: IntentRecord): Long =
        record.createdMs + minOf(ESCALATE_MAX_MS, (record.deadlineMs - record.createdMs) / 2)

    fun next(record: IntentRecord, env: Env): List<Action> {
        if (!record.pending) return emptyList()
        if (env.nowMs > record.deadlineMs) return listOf(Action.Expire)
        val out = mutableListOf<Action>()
        if (!record.tried("relay")) out += Action.SendRelay
        val smsInReply = record.replyTo and PeerIntent.Lane.SMS != 0
        val smsWanted = smsInReply &&
            !record.tried("sms") &&
            record.answer == null &&
            !env.relayDelivered &&
            record.phase == Phase.SENT &&
            env.sms.allows(record.prio) &&
            (!env.relayReady || env.nowMs >= escalateAtMs(record))
        if (smsWanted) out += Action.SendSms
        return out
    }
}
