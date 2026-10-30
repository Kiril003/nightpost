package local.phantom.companion.core.voice

/**
 * Одна репліка, зібрана з уламків, які віддає потоковий розпізнавач.
 *
 * Vosk закриває сегмент на кожній внутрішній тиші — у чинній моделі це
 * `--endpoint.rule2.min-trailing-silence=0.5`, тобто півсекунди. Після
 * закриття його `partialResult` починається з порожнього, і все, що людина
 * сказала до паузи, зникає з того, що бачить і чує решта застосунку:
 *
 *  - на екрані рядок «перевір маршрут до» раптом стає «харкова» — виглядає
 *    так, ніби застосунок забув половину фрази;
 *  - [local.phantom.companion.core.voice.turn.TurnTaker] класифікує хвіст
 *    уламка, а не хвіст репліки;
 *  - здогад (TurnHub.speculate) відправляє моделі уламок, і остаточний
 *    транскрипт із ним не збігається — хід скасовується й починається
 *    заново.
 *
 * Тому накопичення живе окремо від Vosk: сюди складаються закриті сегменти,
 * а назовні йде завжди ЦІЛА репліка — і як партіал, і як фінал. Чистий
 * Kotlin, без Android і без нативної частини, тож поведінку видно на JVM.
 */
class UtteranceText {

    private val committed = StringBuilder()

    /** Сегмент, який розпізнавач щойно закрив усередині репліки. */
    fun commit(segment: String) {
        val text = segment.trim()
        if (text.isEmpty()) return
        if (committed.isNotEmpty()) committed.append(' ')
        committed.append(text)
    }

    /**
     * Репліка станом на зараз: закрите плюс те, що чується просто зараз.
     * Порожній [current] — це не «нічого не сказано», а «сегмент щойно
     * закрився»; віддається закрите.
     */
    fun withPending(current: String): String =
        listOf(committed.toString(), current.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** Закрити репліку: усе разом із незакритим хвостом, і почати спочатку. */
    fun drain(tail: String): String {
        val whole = withPending(tail)
        reset()
        return whole
    }

    fun reset() = committed.setLength(0)
}
