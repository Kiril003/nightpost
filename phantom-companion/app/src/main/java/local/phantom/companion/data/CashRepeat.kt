package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.CashVoucher

/**
 * Та сама купюра двома дорогами (поруч і сховком, або повтор із новим id) —
 * одна картка. Купюру впізнає її підпис, а не id листа.
 */
object CashRepeat {

    fun seen(log: List<PeerMessage>, arrived: PeerMessage): Boolean {
        if (!CashVoucher.isVoucher(arrived.body)) return false
        val sig = CashVoucher.decode(arrived.body)?.payment?.signature ?: return false
        return log.any {
            it.id != arrived.id && CashVoucher.isVoucher(it.body) &&
                CashVoucher.decode(it.body)?.payment?.signature == sig
        }
    }
}
