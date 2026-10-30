package local.phantom.companion.core.solana

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.net.URLEncoder
import java.security.SecureRandom

/** Запит на переказ у форматі Solana Pay — той самий, що читає будь-який гаманець із QR. */
data class TransferRequest(
    val recipient: SolanaKey,
    val coin: Coin,
    val cluster: Cluster,
    val units: Long,
    val reference: SolanaKey,
    val label: String,
    val message: String,
) {
    fun url(): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val q = buildList {
            add("amount=" + Amounts.format(units, coin))
            coin.mint(cluster)?.let { add("spl-token=" + it.base58) }
            add("reference=" + reference.base58)
            add("label=" + enc(label))
            add("message=" + enc(message))
        }
        return "solana:" + recipient.base58 + "?" + q.joinToString("&")
    }

    companion object {
        fun parse(url: String, cluster: Cluster): TransferRequest? {
            if (!url.startsWith("solana:")) return null
            val recipient = SolanaKey.parse(url.removePrefix("solana:").substringBefore('?')) ?: return null
            val q = url.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val coin = q["spl-token"]?.let { mint -> Coin.entries.firstOrNull { it.mint(cluster)?.base58 == mint } ?: return null } ?: Coin.SOL
            val units = q["amount"]?.let { Amounts.toUnits(it, coin) } ?: return null
            val reference = q["reference"]?.let(SolanaKey::parse) ?: return null
            return TransferRequest(recipient, coin, cluster, units, reference, q["label"].orEmpty(), q["message"].orEmpty())
        }
    }
}

/**
 * Одноразова позначка покупки. Публічна половина їде в транзакцію (так її знаходить сервер),
 * секретна лишається на телефоні й підписує прохання видати ключ: підглянувши позначку
 * в мережі, чужий ключ не забрати.
 */
class PurchaseReference private constructor(private val seed: ByteArray) {
    private val priv = Ed25519PrivateKeyParameters(seed, 0)

    val key: SolanaKey = SolanaKey.of(priv.generatePublicKey().encoded)!!
    val seedBase58: String get() = Base58.encode(seed)

    fun prove(signature: String): String {
        val msg = claimMessage(signature)
        val signer = Ed25519Signer().apply { init(true, priv); update(msg, 0, msg.size) }
        return Base58.encode(signer.generateSignature())
    }

    companion object {
        fun fresh(random: SecureRandom = SecureRandom()): PurchaseReference =
            PurchaseReference(ByteArray(32).also(random::nextBytes))

        fun restore(seedBase58: String): PurchaseReference? =
            Base58.decode(seedBase58)?.takeIf { it.size == 32 }?.let(::PurchaseReference)

        fun claimMessage(signature: String): ByteArray = ("phantom-licence-claim:" + signature).toByteArray()
    }
}
