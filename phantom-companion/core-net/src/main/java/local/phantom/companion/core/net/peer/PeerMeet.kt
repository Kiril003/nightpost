package local.phantom.companion.core.net.peer

/**
 * Чи має цей лист право поїхати під довічним ключем пари.
 *
 * Знижку купує лише ВІДПОВІДЬ. Стара збірка на HLO відповідає рядком помилки —
 * тиша це та єдина відповідь, якої вона дати не може, бо MSG і HLO слухає той
 * самий сокет. Тож мовчазний співрозмовник або недосяжний зовсім, і тоді лист
 * однаково нікуди не поїде, або хтось ріже саме домовляння ключів. Жоден з двох
 * не вартий слабшого шифру, тому мовчання не купує нічого й нікому.
 *
 * Чисто й без сокетів навмисно: сила шифру мусить доводитись без радіо і
 * читатись в одному місці.
 */
object PeerMeet {

    /** Скільки не чіпати того, хто щойно сказав «не знаю такого слова». */
    const val RETRY_REFUSAL_MS = 15 * 60_000L

    /** Одна тиша — загублений пакет. Стільки поспіль — уже не погода. */
    const val SILENCE_TO_SHOW = 3

    enum class Answer { MET, REFUSED_UNKNOWN_VERB, REFUSED_OTHER, SILENT }

    enum class Act { MEET, HOLD, PAIR_KEY }

    enum class Why { MET, OLD_BUILD, SILENT, REFUSED_AFTER_MET }

    /**
     * [metAtMs] — єдине засвідчене свідчення в усьому протоколі: підпис під
     * стенограмою зустрічі. Живе в реєстрі, тобто переживає перезапуск; інакше
     * досить було б дочекатись, поки застосунок перезапустять.
     */
    data class Known(
        val metAtMs: Long = 0L,
        val refusedAtMs: Long = 0L,
        val silentInARow: Int = 0,
    ) {
        val everMet: Boolean get() = metAtMs > 0L
    }

    data class Decision(val act: Act, val why: Why)

    fun shouldOffer(known: Known, nowMs: Long): Boolean =
        known.everMet || known.refusedAtMs <= 0L || nowMs - known.refusedAtMs >= RETRY_REFUSAL_MS

    fun after(known: Known, answer: Answer, nowMs: Long): Decision = when {
        answer == Answer.MET -> Decision(Act.MEET, Why.MET)
        known.everMet && answer == Answer.REFUSED_UNKNOWN_VERB ->
            Decision(Act.HOLD, Why.REFUSED_AFTER_MET)
        known.everMet -> Decision(Act.HOLD, Why.SILENT)
        answer == Answer.REFUSED_UNKNOWN_VERB -> Decision(Act.PAIR_KEY, Why.OLD_BUILD)
        else -> Decision(Act.HOLD, Why.SILENT)
    }

    fun noted(known: Known, answer: Answer, nowMs: Long): Known = when (answer) {
        Answer.MET -> known.copy(metAtMs = nowMs, refusedAtMs = 0L, silentInARow = 0)
        Answer.REFUSED_UNKNOWN_VERB -> known.copy(refusedAtMs = nowMs, silentInARow = 0)
        else -> known.copy(silentInARow = known.silentInARow + 1)
    }

    fun worthShowing(known: Known): Boolean = known.silentInARow >= SILENCE_TO_SHOW
}
