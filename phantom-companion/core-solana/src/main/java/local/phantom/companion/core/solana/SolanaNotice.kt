package local.phantom.companion.core.solana

import android.content.Context
import java.text.DateFormat
import java.util.Date

/** Слова замість дроту — для шторки й рядка розмови. */
object SolanaNotice {

    fun isOurs(body: String): Boolean = PaymentFrame.isPayment(body) || WalletAddressFrame.isAddress(body) ||
        CashVoucher.isVoucher(body) || SealFrame.isSeal(body)

    /** Адресу люди обмінюють самі, без участі людини, — у шторку вона не потрапляє. */
    fun isSilent(body: String): Boolean = WalletAddressFrame.isAddress(body)

    fun line(context: Context, body: String): String {
        PaymentFrame.decode(body)?.let { frame ->
            SolanaLayer.watch(frame)
            return context.getString(R.string.sol_notice_payment, frame.amount, frame.coin.symbol)
        }
        CashVoucher.decode(body)?.let { v ->
            SolanaLayer.watch(v.payment, v.tx)
            return context.getString(R.string.sol_notice_cash, v.payment.amount, v.payment.coin.symbol)
        }
        if (SealFrame.isSeal(body)) return context.getString(R.string.sol_notice_seal)
        return context.getString(R.string.sol_notice_address)
    }

    /** Хто на другому кінці переказу в цій розмові: імʼя й напрям. */
    data class Who(val name: String, val outgoing: Boolean)

    /** Ярлик стану для рядка розмови: одне слово, без адреси й без речення. */
    fun statusWord(context: Context, status: PaymentStatus, cash: Boolean = false): String = context.getString(
        when (status) {
            PaymentStatus.PENDING -> if (cash) R.string.sol_chat_cash_pending else R.string.sol_chat_pending
            PaymentStatus.CONFIRMED -> R.string.sol_chat_confirmed
            PaymentStatus.FAILED -> R.string.sol_chat_failed
            PaymentStatus.MISMATCH -> R.string.sol_chat_mismatch
            PaymentStatus.UNKNOWN -> R.string.sol_chat_unknown
        },
    )

    /** «1 USDC для Daniel» / «1 USDC від Daniel»; без імені — `alone`, рід і сума, як у шторці. */
    private fun head(context: Context, subject: String, alone: String, who: Who?): String {
        if (who == null || who.name.isBlank()) return alone
        return context.getString(if (who.outgoing) R.string.sol_chat_to else R.string.sol_chat_from, subject, who.name)
    }

    /** Рядок картки в розмові: сума, з ким і що каже мережа. Адреси гаманця тут немає. */
    fun chatLine(context: Context, body: String, who: Who? = null): String? {
        PaymentFrame.decode(body)?.let { return paymentLine(context, it, SolanaLayer.statusOf(it), who) }
        CashVoucher.decode(body)?.let { return cashLine(context, it, SolanaLayer.cashStatusOf(it), who) }
        SealFrame.decode(body)?.let { f ->
            return context.getString(R.string.sol_notice_seal) + " " + f.sha256Hex.take(12) + "… · " + sealWord(context, SolanaLayer.sealOf(f))
        }
        return null
    }

    internal fun paymentLine(context: Context, f: PaymentFrame, status: PaymentStatus, who: Who?): String =
        head(context, f.amount + " " + f.coin.symbol, context.getString(R.string.sol_notice_payment, f.amount, f.coin.symbol), who) +
            " · " + statusWord(context, status)

    internal fun cashLine(context: Context, v: CashVoucher, status: PaymentStatus, who: Who?): String {
        val cash = context.getString(R.string.sol_notice_cash, v.payment.amount, v.payment.coin.symbol)
        return head(context, cash, cash, who) + " · " + statusWord(context, status, cash = true)
    }

    /** «Seeker перевірено · стейкає ≥10 тис. SKR» — лише те, що каже перевірений знак; нічого — null. */
    fun badgeLine(context: Context, badge: SeekerBadge?): String? = badge?.parts?.map { part ->
        context.getString(
            when (part) {
                SeekerBadge.Part.SEEKER -> R.string.sol_badge_seeker
                SeekerBadge.Part.SKR_STAKER -> R.string.sol_badge_skr_staker
                SeekerBadge.Part.SKR_1K -> R.string.sol_badge_skr_1k
                SeekerBadge.Part.SKR_10K -> R.string.sol_badge_skr_10k
            },
        )
    }?.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    fun sealWord(context: Context, v: SealVerdict): String = when (v) {
        is SealVerdict.Anchored -> v.atSeconds?.let {
            context.getString(R.string.sol_seal_anchored, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000)))
        } ?: context.getString(R.string.sol_status_confirmed)
        SealVerdict.Pending -> context.getString(R.string.sol_status_pending)
        SealVerdict.Mismatch -> context.getString(R.string.sol_seal_mismatch)
        SealVerdict.Failed -> context.getString(R.string.sol_status_failed)
    }
}
