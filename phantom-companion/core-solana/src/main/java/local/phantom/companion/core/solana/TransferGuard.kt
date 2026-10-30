package local.phantom.companion.core.solana

import java.math.BigDecimal

/**
 * Звідки адреса: з розмови з людиною, вписана руками, чи [VENUE] — гаманець закладу,
 * який організатор обрав у рахунку зустрічі (збірка 18). Заклад — не «вручну»: людина
 * нічого не вписувала, але й не з розмови з ним.
 */
enum class AddressSource { CONVERSATION, TYPED, VENUE, SCANNED }

/** Переказ, який ця людина вже робила раніше: з журналу розмов, не з памʼяті моделі. */
data class SentBefore(val coin: Coin, val cluster: Cluster, val units: Long, val to: SolanaKey) {
    companion object {
        fun of(frame: PaymentFrame) = SentBefore(frame.coin, frame.cluster, frame.units, frame.to)
    }
}

sealed interface GuardNote {
    data object Typed : GuardNote
    data object Venue : GuardNote
    data object Scanned : GuardNote
    data object NewAddress : GuardNote
    data object FirstTime : GuardNote
    data class OtherNetwork(val usual: Cluster, val now: Cluster) : GuardNote
    data class OtherCoin(val usual: Coin, val now: Coin) : GuardNote
    data class Unusual(val most: String, val coin: Coin) : GuardNote
}

/**
 * Сторож перед підтвердженням. Лише правила за фактами з журналу; кожне
 * попередження каже, чому воно зʼявилось. Немає історії — немає й порівнянь.
 */
object TransferGuard {

    const val UNUSUAL_FACTOR = 5L

    /** Що показати на аркуші: [fromChat] і [paidBefore] — з журналу, як і попередження. */
    data class View(val fromChat: Boolean, val paidBefore: Int, val notes: List<GuardNote>)

    /** Журнал ще не прочитано ([history] null) — мовчимо: порожній список означав би «вперше». */
    fun view(to: SolanaKey, source: AddressSource, coin: Coin, cluster: Cluster, units: Long?, history: List<SentBefore>?): View? =
        history?.let { View(source == AddressSource.CONVERSATION, it.size, check(to, source, coin, cluster, units, it)) }

    fun check(
        to: SolanaKey,
        source: AddressSource,
        coin: Coin,
        cluster: Cluster,
        units: Long?,
        history: List<SentBefore>,
    ): List<GuardNote> = buildList {
        if (source == AddressSource.TYPED) add(GuardNote.Typed)
        if (source == AddressSource.VENUE) add(GuardNote.Venue)
        if (source == AddressSource.SCANNED) add(GuardNote.Scanned)
        if (history.isEmpty()) {
            add(GuardNote.FirstTime)
            return@buildList
        }
        if (history.none { it.to == to }) add(GuardNote.NewAddress)

        val clusters = history.map { it.cluster }.toSet()
        if (cluster !in clusters) add(GuardNote.OtherNetwork(clusters.first(), cluster))

        val here = history.filter { it.cluster == cluster }
        if (here.size >= 2 && here.none { it.coin == coin }) {
            val usual = here.groupingBy { it.coin }.eachCount().maxBy { it.value }.key
            add(GuardNote.OtherCoin(usual, coin))
        }

        val same = here.filter { it.coin == coin }.map { it.units }.sorted()
        if (units != null && same.size >= 2) {
            val median = if (same.size % 2 == 1) BigDecimal(same[same.size / 2])
            else (BigDecimal(same[same.size / 2 - 1]) + BigDecimal(same[same.size / 2])).divide(BigDecimal(2))
            if (units > same.last() && BigDecimal(units) > median * BigDecimal(UNUSUAL_FACTOR)) {
                add(GuardNote.Unusual(Amounts.format(same.last(), coin), coin))
            }
        }
    }
}
