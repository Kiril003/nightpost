package local.phantom.companion.ui.screens

import android.content.Context
import local.phantom.companion.R
import local.phantom.companion.data.PcPairing

internal fun pairFailureText(
    context: Context,
    why: PcPairing.Failure,
    detail: String,
    digits: Boolean,
): String = when (why) {
    PcPairing.Failure.BAD_INPUT -> context.getString(R.string.pair_err_bad_input)
    PcPairing.Failure.EXPIRED -> context.getString(R.string.pair_err_expired)
    PcPairing.Failure.NOT_FOUND -> context.getString(
        if (digits) R.string.pair_err_digits_not_found else R.string.pair_err_not_found,
    )
    PcPairing.Failure.NOT_PROVEN -> context.getString(R.string.pair_err_not_proven)
    PcPairing.Failure.UNREACHABLE -> context.getString(
        R.string.pair_err_unreachable,
        detail.ifBlank { "—" },
    )
}
