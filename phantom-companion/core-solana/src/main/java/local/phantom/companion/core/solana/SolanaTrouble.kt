package local.phantom.companion.core.solana

import android.content.Context
import android.util.Log

/** Людині — що сталося й що робити; сирий текст вузла — лише в журнал. */
object SolanaTrouble {

    enum class Kind { NO_FEE, NO_FUNDS, OFFLINE, EXPIRED, OTHER }

    fun classify(raw: String): Kind {
        val t = raw.lowercase()
        return when {
            // Порядок важливий: «insufficient funds for fee» теж містить «insufficient funds».
            "no record of a prior credit" in t || "for fee" in t || "for rent" in t -> Kind.NO_FEE
            "insufficient" in t || "custom program error: 0x1" in t -> Kind.NO_FUNDS
            "blockhash not found" in t || "block height exceeded" in t -> Kind.EXPIRED
            listOf("unable to resolve", "timeout", "timed out", "failed to connect", "connection", "no rpc", "http 5", "http 429", "offline")
                .any { it in t } -> Kind.OFFLINE
            else -> Kind.OTHER
        }
    }

    fun words(context: Context, raw: String): String {
        Log.w("PhantomSolana", "rpc: $raw")
        return context.getString(
            when (classify(raw)) {
                Kind.NO_FEE -> R.string.sol_err_no_fee
                Kind.NO_FUNDS -> R.string.sol_err_no_funds
                Kind.OFFLINE -> R.string.sol_offline
                Kind.EXPIRED -> R.string.sol_err_expired
                Kind.OTHER -> R.string.sol_err_other
            },
        )
    }
}
