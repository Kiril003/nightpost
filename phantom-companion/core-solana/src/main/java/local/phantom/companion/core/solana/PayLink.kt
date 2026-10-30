package local.phantom.companion.core.solana

import java.net.URLDecoder

/** Платіжне посилання `solana:` або гола адреса з QR-коду. */
data class PayLink(
    val to: SolanaKey,
    val coin: Coin?,
    val amount: String?,
    val reference: SolanaKey? = null,
    val memo: String? = null,
) {

    companion object {
        const val SCHEME = "solana:"
        private const val MAX_MEMO_BYTES = 256
        private const val INTERNAL_MEMO_PREFIX = "nightpost-"

        fun parse(text: String, cluster: Cluster): PayLink? {
            val t = text.trim()
            if (t.isEmpty()) return null
            if (!t.startsWith(SCHEME)) {
                return SolanaKey.parse(t)?.let { PayLink(it, null, null) }
            }
            val body = t.removePrefix(SCHEME)
            val to = SolanaKey.parse(body.substringBefore('?')) ?: return null
            val query = body.substringAfter('?', "")
            val pairs = if (query.isEmpty()) {
                emptyList()
            } else {
                query.split('&').filter { it.contains('=') }.map {
                    val k = it.substringBefore('=')
                    val v = it.substringAfter('=')
                    k to runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                }
            }
            val refs = pairs.filter { it.first == "reference" }
            if (refs.size > 1) return null
            val reference = refs.singleOrNull()?.let { SolanaKey.parse(it.second.trim()) ?: return null }
            val q = pairs.toMap()
            val memo = q["memo"]?.takeIf { it.isNotBlank() }?.also {
                if (it.toByteArray(Charsets.UTF_8).size > MAX_MEMO_BYTES) return null
                if (it.startsWith(INTERNAL_MEMO_PREFIX)) return null
            }
            val tokenCoin = q["spl-token"]?.let { mint ->
                Coin.entries.firstOrNull { it.mint(cluster)?.base58 == mint } ?: return null
            }
            val rawAmount = q["amount"]?.trim()?.takeIf { it.isNotEmpty() }
            val validAmount = rawAmount?.takeIf { Amounts.toUnits(it, tokenCoin ?: Coin.SOL) != null }
            val coin = tokenCoin ?: if (validAmount != null) Coin.SOL else null
            return PayLink(to, coin, validAmount, reference, memo)
        }
    }
}
