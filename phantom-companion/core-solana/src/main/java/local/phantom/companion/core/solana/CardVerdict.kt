package local.phantom.companion.core.solana

/**
 * Один підпис — одна картка. Хто першим показав підпис, тому він і належить;
 * той самий підпис в іншому повідомленні — повтор, а не ще один переказ.
 */
class SignatureClaims(saved: Map<String, String>, private val limit: Int = 2000, private val save: (Map<String, String>) -> Unit) {
    private val owners = LinkedHashMap(saved)

    @Synchronized
    fun claim(signature: String, card: String): Boolean {
        val owner = owners[signature]
        if (owner != null) return owner == card
        owners[signature] = card
        while (owners.size > limit) owners.remove(owners.keys.first())
        save(owners.toMap())
        return true
    }
}

/** Стан картки в розмові і, коли «не збігається», — чому. */
data class CardVerdict(val status: PaymentStatus, val reason: String? = null) {
    companion object {
        const val REPLAY = "replay"
        const val PAYER_UNKNOWN = "payer_unknown"
        const val OTHER_PAYER = "other_payer"

        /**
         * Що можна сказати про картку ще до мережі. [payer] — гаманець (або кишеня для купюри),
         * який відправник назвав у цій розмові; [signer] — хто підписав купюру, якщо це купюра.
         */
        fun before(claimed: Boolean, mine: Boolean, payer: SolanaKey?, signer: SolanaKey? = null): CardVerdict? = when {
            !claimed -> CardVerdict(PaymentStatus.MISMATCH, REPLAY)
            mine -> null
            payer == null -> CardVerdict(PaymentStatus.MISMATCH, PAYER_UNKNOWN)
            signer != null && signer != payer -> CardVerdict(PaymentStatus.MISMATCH, OTHER_PAYER)
            else -> null
        }
    }
}
