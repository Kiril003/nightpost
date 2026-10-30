package local.phantom.companion.core.solana

// The words leave the vault only here, right after the person unlocked it; the app decides where they may go.
object WalletEscrow {
    @Volatile var keep: ((List<String>) -> Unit)? = null
    @Volatile var waiting: (() -> List<String>?)? = null
    @Volatile var settle: (() -> Unit)? = null

    internal fun saw(words: List<String>) {
        runCatching { keep?.invoke(words) }
    }

    internal fun held(): List<String>? = runCatching { waiting?.invoke() }.getOrNull()?.takeIf { it.isNotEmpty() }

    internal fun settled() {
        runCatching { settle?.invoke() }
    }
}
