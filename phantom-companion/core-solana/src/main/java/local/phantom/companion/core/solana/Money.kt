package local.phantom.companion.core.solana

import java.math.BigDecimal

enum class Cluster(val wire: String, val publicRpc: String) {
    DEVNET("devnet", "https://api.devnet.solana.com"),
    MAINNET("mainnet", "https://api.mainnet-beta.solana.com"),
    ;

    companion object {
        fun of(wire: String?): Cluster? = entries.firstOrNull { it.wire == wire }
    }
}

// Порядок оголошення — порядок у виборі монети й у балансах.
enum class Coin(val wire: String, val symbol: String, val decimals: Int, private val mints: Map<Cluster, String>) {
    USDC(
        "usdc", "USDC", 6,
        mapOf(
            Cluster.MAINNET to "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            Cluster.DEVNET to "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU",
        ),
    ),
    // Circle тримає EURC на одній адресі в обох мережах; класичний SPL Token, не Token-2022.
    EURC(
        "eurc", "EURC", 6,
        mapOf(
            Cluster.MAINNET to "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr",
            Cluster.DEVNET to "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr",
        ),
    ),
    SOL("sol", "SOL", 9, emptyMap()),
    // У devnet SKR не існує — тому й не пропонується там.
    SKR("skr", "SKR", 6, mapOf(Cluster.MAINNET to "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3")),
    ;

    val native: Boolean get() = this == SOL

    fun mint(cluster: Cluster): SolanaKey? = mints[cluster]?.let(SolanaKey::of)

    fun availableOn(cluster: Cluster): Boolean = native || mints.containsKey(cluster)

    companion object {
        fun of(wire: String?): Coin? = entries.firstOrNull { it.wire == wire }
        fun on(cluster: Cluster): List<Coin> = entries.filter { it.availableOn(cluster) }
    }
}

object Amounts {
    /** «1,5» і «1.5» — те саме; більше знаків, ніж має монета, — відмова, а не округлення. */
    fun toUnits(text: String, coin: Coin): Long? {
        val clean = text.trim().replace(',', '.').replace(" ", "")
        if (clean.isEmpty() || clean.startsWith("-") || clean.startsWith("+")) return null
        val value = clean.toBigDecimalOrNull() ?: return null
        if (value.signum() <= 0 || value.stripTrailingZeros().scale() > coin.decimals) return null
        return runCatching { value.movePointRight(coin.decimals).longValueExact() }.getOrNull()
    }

    fun format(units: Long, coin: Coin): String =
        BigDecimal.valueOf(units).movePointLeft(coin.decimals).stripTrailingZeros().toPlainString()
}

object Transfers {
    /**
     * Переказ лише СВОЇХ коштів: підписант — завжди гаманець власника. Платник комісії й ренти
     * ATA — він же, або спонсор ([feePayer]), який підпише свій слот на сервері.
     */
    fun build(
        from: SolanaKey,
        to: SolanaKey,
        coin: Coin,
        cluster: Cluster,
        units: Long,
        recentBlockhash: ByteArray,
        reference: SolanaKey? = null,
        memo: String? = null,
        nonce: SolanaKey? = null,
        feePayer: SolanaKey = from,
        mint: SolanaKey? = coin.mint(cluster),
    ): ByteArray {
        require(nonce == null || feePayer == from) { "durable nonce is never sponsored" }
        require(units > 0)
        val ixs = buildList {
            nonce?.let { add(Programs.advanceNonce(it, from)) }
            if (coin.native) {
                add(Programs.systemTransfer(from, to, units, reference))
            } else {
                requireNotNull(mint) { "${coin.symbol} not on ${cluster.wire}" }
                add(Programs.createAssociatedIdempotent(feePayer, to, mint))
                add(Programs.tokenTransferChecked(from, to, mint, units, coin.decimals, reference))
            }
            memo?.takeIf { it.isNotBlank() }?.let { add(Programs.memo(it)) }
        }
        return SolanaTx.message(feePayer, recentBlockhash, ixs)
    }
}
