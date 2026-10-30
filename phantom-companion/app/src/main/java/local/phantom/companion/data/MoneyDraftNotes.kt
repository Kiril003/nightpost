package local.phantom.companion.data

import android.content.Context
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerOutbox
import local.phantom.companion.core.solana.CashVoucher
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.PaymentDraft
import local.phantom.companion.core.solana.PaymentFrame

/**
 * Чернетка, яку помічник відкрив із розмови. Екран переказу накриває бульбашку,
 * а коли людина з нього повертається, бульбашка мусить мати дорогу далі:
 * «Скласти знову» відкриває той самий екран з тією самою людиною й сумою.
 */
internal data class DraftNote(val peerId: String, val coin: Coin, val amount: String?, val atMs: Long) {

    fun encode(): String = listOf(peerId, coin.wire, amount.orEmpty(), atMs.toString()).joinToString("|")

    /** Чи пішов після чернетки мій переказ цій людині цією монетою (і цією сумою, якщо вона була). */
    fun sentIn(log: List<PeerMessage>, me: String): Boolean =
        me.isNotBlank() && PeerOutbox.conversation(log, peerId).any { m ->
            m.fromId == me && m.sentAtMs >= atMs &&
                (PaymentFrame.decode(m.body) ?: CashVoucher.decode(m.body)?.payment)
                    ?.let { f -> f.coin == coin && (amount == null || f.amount == amount) } == true
        }

    companion object {
        fun of(draft: PaymentDraft, atMs: Long): DraftNote? =
            draft.peerId?.takeIf { it.isNotBlank() }?.let { DraftNote(it, draft.coin, draft.amount, atMs) }

        fun decode(s: String?): DraftNote? {
            val f = s?.split('|')?.takeIf { it.size == 4 } ?: return null
            val coin = Coin.of(f[1]) ?: return null
            val at = f[3].toLongOrNull() ?: return null
            return DraftNote(f[0].ifBlank { return null }, coin, f[2].ifBlank { null }, at)
        }
    }
}

internal object MoneyDraftNotes {
    private const val FILE = "phantom_money_drafts_v1"
    private const val KEEP = 40

    fun put(ctx: Context, messageId: String, note: DraftNote) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val old = prefs.all.entries
            .mapNotNull { (k, v) -> DraftNote.decode(v as? String)?.let { k to it.atMs } }
            .sortedByDescending { it.second }
            .drop(KEEP - 1)
            .map { it.first }
        prefs.edit().apply { old.forEach(::remove) }.putString(messageId, note.encode()).apply()
    }

    fun get(ctx: Context, messageId: String): DraftNote? =
        DraftNote.decode(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(messageId, null))
}
