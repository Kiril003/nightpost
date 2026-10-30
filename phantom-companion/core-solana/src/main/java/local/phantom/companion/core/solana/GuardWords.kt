package local.phantom.companion.core.solana

import android.content.Context

fun GuardNote.sentence(ctx: Context): String = when (this) {
    GuardNote.Typed -> ctx.getString(R.string.sol_guard_typed)
    GuardNote.Venue -> ctx.getString(R.string.sol_guard_venue)
    GuardNote.Scanned -> ctx.getString(R.string.sol_guard_scanned)
    GuardNote.NewAddress -> ctx.getString(R.string.sol_guard_new_address)
    GuardNote.FirstTime -> ctx.getString(R.string.sol_guard_first_time)
    is GuardNote.OtherNetwork -> ctx.getString(R.string.sol_guard_other_network, usual.wire, now.wire)
    is GuardNote.OtherCoin -> ctx.getString(R.string.sol_guard_other_coin, usual.symbol, now.symbol)
    is GuardNote.Unusual -> ctx.getString(R.string.sol_guard_unusual, most, coin.symbol)
}
