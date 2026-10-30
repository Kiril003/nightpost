package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerWhere
import local.phantom.companion.core.net.peer.PeerWhereGrid
import local.phantom.companion.data.PeerWhereView

/**
 * «Де я» в розмові — форма рядка для веба.
 *
 * Картка в розмові — не лист, а відображення стану [PeerWhereView]: у дріт іде
 * лише той самий кадр PH-WHR сталої довжини, що й для мапи. Лист-картка
 * «почав показувати о 18:02, район» лежав би в журналі й у сховку як історія,
 * а довжина тексту видавала б точність. Координат у вебі немає зовсім.
 */
object WebMessengerWhere {

    const val SEGMENT = "where"

    /** Хвилини строку, які приймає міст; `null` у тілі — до вимкнення. */
    val SPANS_MIN = listOf(15, 60)

    fun word(p: PeerWhere.Precision): String = p.name.lowercase()

    fun precisionOf(word: String?): PeerWhere.Precision? =
        PeerWhere.Precision.entries.firstOrNull { it.name.equals(word?.trim(), ignoreCase = true) }

    /** Нуль — до вимкнення; null — такого строку меню не пропонує. */
    fun untilFor(minutes: Int?, nowMs: Long): Long? = when (minutes) {
        null -> 0L
        in SPANS_MIN -> nowMs + minutes * 60_000L
        else -> null
    }

    /** «До зустрічі» — лише точно, строк із плану, але не далі [MEET_MAX_MS]; null — так не буває. */
    fun meetUntil(precision: PeerWhere.Precision, untilMs: Long, nowMs: Long): Long? =
        untilMs.takeIf { precision == PeerWhere.Precision.EXACT && it > nowMs && it <= nowMs + MEET_MAX_MS }

    const val MEET_MAX_MS = 6 * 60 * 60_000L

    fun nearWord(n: PeerWhereGrid.Nearness): String = n.name.lowercase()

    fun fields(v: PeerWhereView, nowMs: Long): Map<String, Any?> = mapOf(
        "now_ms" to nowMs,
        "eligible" to (v.whyNot == null),
        "why_not" to v.whyNot?.name?.lowercase(),
        "mine" to v.mine?.let {
            mapOf(
                "precision" to word(it.precision),
                "until_ms" to it.untilMs.takeIf { t -> t > 0L },
                "stand" to it.stand,
                "meet" to it.meet,
            )
        },
        "met_at_ms" to v.metAtMs,
        "theirs" to v.theirs?.let {
            mapOf(
                "precision" to word(it.precision),
                "near" to it.near?.let(::nearWord),
                "at_ms" to it.atMs,
                "expires_at_ms" to it.expiresAtMs,
                "stand" to it.stand,
            )
        },
    )
}
