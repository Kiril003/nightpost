package local.phantom.companion.core.solana

import java.math.BigDecimal

/**
 * Чернетка переказу. Лише заповнені поля для екрана переказу: підпису тут
 * немає й бути не може — підписує гаманець після відбитка людини.
 */
data class PaymentDraft(
    val peerId: String?,
    val name: String,
    val to: SolanaKey,
    val source: AddressSource,
    val coin: Coin,
    val coinSaid: Boolean,
    val units: Long?,
    val cluster: Cluster,
) {
    val amount: String? get() = units?.let { Amounts.format(it, coin) }
}

sealed interface DraftOutcome {
    data class Ready(val draft: PaymentDraft, val notes: List<GuardNote>) : DraftOutcome
    data class BadAmount(val amount: String, val coin: Coin) : DraftOutcome {
        val smallest: String get() = Amounts.format(1, coin)
    }
    data class CoinNotHere(val coin: Coin, val cluster: Cluster) : DraftOutcome
}

object PaymentDrafts {

    fun compose(
        peerId: String?,
        name: String,
        to: SolanaKey,
        source: AddressSource,
        coinWire: String?,
        amount: BigDecimal?,
        cluster: Cluster,
        history: List<SentBefore>,
    ): DraftOutcome {
        val said = coinWire?.let(Coin::of)
        val coin = said ?: usualCoin(history, cluster) ?: if (Coin.USDC.availableOn(cluster)) Coin.USDC else Coin.SOL
        if (!coin.availableOn(cluster)) return DraftOutcome.CoinNotHere(coin, cluster)
        val units = amount?.let { a -> Amounts.toUnits(a.toPlainString(), coin) ?: return DraftOutcome.BadAmount(a.stripTrailingZeros().toPlainString(), coin) }
        val draft = PaymentDraft(peerId, name, to, source, coin, said != null, units, cluster)
        return DraftOutcome.Ready(draft, TransferGuard.check(to, source, coin, cluster, units, history))
    }

    private fun usualCoin(history: List<SentBefore>, cluster: Cluster): Coin? =
        history.filter { it.cluster == cluster }.groupingBy { it.coin }.eachCount().maxByOrNull { it.value }?.key
}

/** Що лежить у кишені для купюри саме зараз. */
data class CashOnHand(val coin: Coin?, val freeUnits: Long, val builtIn: Boolean, val solCovers: Boolean = true)

sealed interface DraftRoute {
    data object Online : DraftRoute
    data object Cash : DraftRoute
    data class NoCash(val gap: CashGap) : DraftRoute
}

sealed interface CashGap {
    data object External : CashGap
    data object NotAPerson : CashGap
    data object Empty : CashGap
    data object NoSol : CashGap
    data class OtherCoin(val have: Coin, val want: Coin) : CashGap
    data class Short(val free: Long, val coin: Coin) : CashGap
}

object DraftRoutes {
    fun choose(draft: PaymentDraft, online: Boolean, hand: CashOnHand): DraftRoute {
        if (online) return DraftRoute.Online
        if (!hand.builtIn) return DraftRoute.NoCash(CashGap.External)
        // Купюра їде сіткою до людини з розмови; на набрану адресу її нема кому передати.
        if (draft.peerId == null) return DraftRoute.NoCash(CashGap.NotAPerson)
        val coin = hand.coin?.takeIf { hand.freeUnits > 0 } ?: return DraftRoute.NoCash(CashGap.Empty)
        if (coin != draft.coin) return DraftRoute.NoCash(CashGap.OtherCoin(coin, draft.coin))
        if (draft.units != null && draft.units > hand.freeUnits) return DraftRoute.NoCash(CashGap.Short(hand.freeUnits, coin))
        if (!hand.solCovers) return DraftRoute.NoCash(CashGap.NoSol)
        return DraftRoute.Cash
    }
}
