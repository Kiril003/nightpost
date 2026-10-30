package local.phantom.companion.data

import android.content.Context
import local.phantom.companion.R
import local.phantom.companion.core.ai.tool.MoneyAsk
import local.phantom.companion.core.ai.tool.MoneyTool
import local.phantom.companion.core.ai.tool.Pick
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.solana.AddressSource
import local.phantom.companion.core.solana.Amounts
import local.phantom.companion.core.solana.CashGap
import local.phantom.companion.core.solana.CashOnHand
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.DraftOutcome
import local.phantom.companion.core.solana.DraftRoute
import local.phantom.companion.core.solana.DraftRoutes
import local.phantom.companion.core.solana.GuardNote
import local.phantom.companion.core.solana.PaymentDraft
import local.phantom.companion.core.solana.PaymentDrafts
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SentBefore
import local.phantom.companion.core.solana.SolanaContact
import local.phantom.companion.core.solana.SolanaKey
import local.phantom.companion.core.solana.SolanaLayer
import local.phantom.companion.core.solana.WalletActivity
import local.phantom.companion.core.solana.sentence

/**
 * «Скинь Олені 5 USDC» → чернетка на екрані переказу. Асистент лише читає і
 * відкриває: у [Hands] немає жодного способу підписати чи надіслати.
 * [hear] лише вирішує; відкриває [act] — після того, як відповідь лягла в розмову.
 */
internal class MoneyAssistant(private val hands: Hands) {

    interface Hands {
        fun people(): List<SolanaContact>
        fun sent(): List<Pair<String, PaymentFrame>>
        fun cluster(): Cluster
        fun walletReady(): Boolean
        fun online(): Boolean
        fun cashOnHand(): CashOnHand
        fun openDraft(draft: PaymentDraft, cash: Boolean)
        fun openSealPicker()
    }

    sealed interface Reply {
        data class Drafted(val draft: PaymentDraft, val notes: List<GuardNote>, val cash: Boolean = false) : Reply
        data class OfflineNoCash(val gap: CashGap) : Reply
        data class WhichOne(val names: List<String>) : Reply
        data class NoWallet(val names: List<String>) : Reply
        data class Nobody(val said: String) : Reply
        data object WhoMissing : Reply
        data class BadAmount(val amount: String, val coin: Coin, val smallest: String) : Reply
        data class CoinNotHere(val coin: Coin, val cluster: Cluster) : Reply
        data object NoOwnWallet : Reply
        data object SealPicker : Reply
    }

    fun hear(text: String): Reply? {
        val ask = MoneyTool.read(text) ?: return null
        if (!hands.walletReady()) return Reply.NoOwnWallet
        if (ask is MoneyAsk.Seal) return Reply.SealPicker
        ask as MoneyAsk.Pay
        val people = hands.people()
        val typed = ask.address?.let(SolanaKey::parse)
        val (who, source) = if (typed != null) {
            val known = people.firstOrNull { it.address == typed }
            (known ?: SolanaContact("", typed.short(), typed)) to if (known != null) AddressSource.CONVERSATION else AddressSource.TYPED
        } else {
            when (val p = MoneyTool.pick(ask.who, people, SolanaContact::name) { it.address != null }) {
                is Pick.One -> p.who to AddressSource.CONVERSATION
                is Pick.Several -> return Reply.WhichOne(p.who.map { it.name })
                is Pick.NoWallet -> return Reply.NoWallet(p.who.map { it.name })
                Pick.Nobody -> return if (ask.who.isEmpty()) Reply.WhoMissing else Reply.Nobody(ask.who.joinToString(" "))
            }
        }
        val to = who.address!!
        val peerId = who.id.ifBlank { null }
        val history = hands.sent()
            .filter { (id, f) -> if (peerId != null) id == peerId else f.to == to }
            .map { SentBefore.of(it.second) }
        return when (val o = PaymentDrafts.compose(peerId, who.name, to, source, ask.coin, ask.amount, hands.cluster(), history)) {
            is DraftOutcome.Ready -> when (val route = DraftRoutes.choose(o.draft, hands.online(), hands.cashOnHand())) {
                DraftRoute.Online -> Reply.Drafted(o.draft, o.notes)
                DraftRoute.Cash -> Reply.Drafted(o.draft, o.notes, cash = true)
                is DraftRoute.NoCash -> Reply.OfflineNoCash(route.gap)
            }
            is DraftOutcome.BadAmount -> Reply.BadAmount(o.amount, o.coin, o.smallest)
            is DraftOutcome.CoinNotHere -> Reply.CoinNotHere(o.coin, o.cluster)
        }
    }

    fun act(reply: Reply) {
        when (reply) {
            is Reply.Drafted -> hands.openDraft(reply.draft, reply.cash)
            Reply.SealPicker -> hands.openSealPicker()
            else -> Unit
        }
    }

    companion object {
        /** Стільки бульбашка відповіді видна, перш ніж її накриє екран переказу. */
        const val READ_MS = 900L
    }
}

internal fun MoneyAssistant.Reply.words(ctx: Context): String = when (this) {
    is MoneyAssistant.Reply.Drafted -> buildList {
        add(
            when {
                draft.units == null -> ctx.getString(R.string.money_drafted_no_amount, draft.name, draft.coin.symbol)
                cash -> ctx.getString(R.string.money_drafted_cash, draft.name, draft.amount!!, draft.coin.symbol)
                else -> ctx.getString(R.string.money_drafted, draft.name, draft.amount!!, draft.coin.symbol)
            },
        )
        if (!draft.coinSaid) add(ctx.getString(R.string.money_coin_guessed, draft.coin.symbol))
        if (notes.isNotEmpty()) add(ctx.getString(R.string.money_watch_out) + " " + notes.joinToString(" ") { it.sentence(ctx) })
    }.joinToString("\n\n")
    is MoneyAssistant.Reply.WhichOne -> ctx.getString(R.string.money_which_one, names.joinToString(", "))
    is MoneyAssistant.Reply.NoWallet -> ctx.getString(R.string.money_no_wallet, names.joinToString(", "))
    is MoneyAssistant.Reply.Nobody -> ctx.getString(R.string.money_nobody, said)
    MoneyAssistant.Reply.WhoMissing -> ctx.getString(R.string.money_who_missing)
    is MoneyAssistant.Reply.BadAmount -> ctx.getString(R.string.money_bad_amount, amount, coin.symbol, smallest)
    is MoneyAssistant.Reply.CoinNotHere -> ctx.getString(R.string.money_coin_not_here, coin.symbol, cluster.wire)
    MoneyAssistant.Reply.NoOwnWallet -> ctx.getString(R.string.money_no_own_wallet)
    MoneyAssistant.Reply.SealPicker -> ctx.getString(R.string.money_seal_pick)
    is MoneyAssistant.Reply.OfflineNoCash -> ctx.getString(
        R.string.money_offline_only_cash,
        when (val g = gap) {
            CashGap.External -> ctx.getString(R.string.money_offline_external)
            CashGap.NotAPerson -> ctx.getString(R.string.money_offline_not_person)
            CashGap.Empty -> ctx.getString(R.string.money_offline_empty)
            CashGap.NoSol -> ctx.getString(R.string.money_offline_no_sol)
            is CashGap.OtherCoin -> ctx.getString(R.string.money_offline_other_coin, g.have.symbol, g.want.symbol)
            is CashGap.Short -> ctx.getString(R.string.money_offline_short, Amounts.format(g.free, g.coin), g.coin.symbol)
        },
    )
}

/** Мої перекази з журналу розмов — і для сторожа, і для монети «як зазвичай». */
internal fun AppContainer.sentPayments(): List<Pair<String, PaymentFrame>> {
    val me = mesh.selfIdFlow.value.ifBlank { return emptyList() }
    val people = peers.peers.value.filter { it.kind == PeerIdentity.Kind.PERSON }.map { it.id }
    return sentPaymentsOf(mesh.outbox.value, me, people, ::sharedWalletOf)
}

/**
 * (людина, кадр) моїх переказів: з розмов 1:1 і з груп (частка рахунку, збірка 18).
 * Груповий лист лежить у журналі копією на кожного учасника — переказ з нього один.
 * Кому платили в групі, кадр каже адресою ([PaymentFrame.to]): людина — та, чий
 * гаманець це; не впізнали (гаманець закладу) — "" і звірка лише за адресою.
 */
internal fun sentPaymentsOf(
    log: List<PeerMessage>,
    me: String,
    people: List<String>,
    walletOf: (String) -> SolanaKey?,
): List<Pair<String, PaymentFrame>> {
    if (me.isBlank()) return emptyList()
    fun frameOf(body: String) = PaymentFrame.decode(body) ?: CashVoucher.decode(body)?.payment
    val direct = people.flatMap { id ->
        PeerOutbox.conversation(log, id).filter { it.fromId == me }.mapNotNull { m -> frameOf(m.body)?.let { id to it } }
    }
    val inGroups = log
        .filter { it.fromId == me && PeerGroup.isGroup(it.body) }
        .distinctBy { it.sentAtMs to it.body }
        .mapNotNull { m -> PeerGroup.decode(m.body)?.text?.let(::frameOf) }
    if (inGroups.isEmpty()) return direct
    val whose = people.mapNotNull { id -> walletOf(id)?.let { it to id } }.toMap()
    return direct + inGroups.map { f -> whose[f.to].orEmpty() to f }
}

internal fun AppContainer.moneyHands(): MoneyAssistant.Hands = object : MoneyAssistant.Hands {
    override fun people() = SolanaLayer.config?.contacts?.invoke().orEmpty()
    override fun sent() = sentPayments()
    override fun cluster() = SolanaLayer.state.value.cluster
    override fun walletReady() = SolanaLayer.installed && SolanaLayer.state.value.address != null
    override fun online() = networkProbe.online.value
    override fun cashOnHand() = SolanaLayer.cashOnHand()
    override fun openDraft(draft: PaymentDraft, cash: Boolean) {
        applicationContext.startActivity(WalletActivity.draftIntent(applicationContext, draft, cash))
    }
    override fun openSealPicker() {
        applicationContext.startActivity(WalletActivity.sealPhotoIntent(applicationContext))
    }
}
