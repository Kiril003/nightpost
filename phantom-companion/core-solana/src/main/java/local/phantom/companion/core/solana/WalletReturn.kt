package local.phantom.companion.core.solana

import android.content.Context

object WalletReturn {
    private const val PREFS = "phantom_wallet_return"
    private const val WAS = "was"

    fun expect(context: Context, address: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(WAS, address).commit()
    }

    fun expected(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(WAS, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(WAS).apply()
    }

    enum class State { NONE, ABSENT, REPLACED }

    fun state(expected: String?, builtIn: String?): State = when {
        expected == null || expected == builtIn -> State.NONE
        builtIn == null -> State.ABSENT
        else -> State.REPLACED
    }

    fun missing(expected: String?, builtIn: String?): Boolean = state(expected, builtIn) != State.NONE
}
