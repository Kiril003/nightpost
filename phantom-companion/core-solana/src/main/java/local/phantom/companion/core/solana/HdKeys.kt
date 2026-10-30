package local.phantom.companion.core.solana

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** BIP39 англійською — щоб ті самі слова відкрили гаманець і в Phantom, і в Solflare. */
class Bip39(private val words: List<String>) {
    init {
        require(words.size == 2048)
    }

    private val index = words.withIndex().associate { (i, w) -> w to i }

    fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size in setOf(16, 32))
        val checksumBits = entropy.size * 8 / 32
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val bits = BooleanArray(entropy.size * 8 + checksumBits) { i ->
            val b = if (i < entropy.size * 8) entropy[i / 8] else hash[(i - entropy.size * 8) / 8]
            val bit = if (i < entropy.size * 8) i % 8 else (i - entropy.size * 8) % 8
            (b.toInt() shr (7 - bit)) and 1 == 1
        }
        return (0 until bits.size / 11).map { w ->
            var v = 0
            for (k in 0 until 11) v = (v shl 1) or (if (bits[w * 11 + k]) 1 else 0)
            words[v]
        }
    }

    fun generate(random: SecureRandom = SecureRandom()): List<String> = fromEntropy(ByteArray(16).also(random::nextBytes))

    fun isValid(mnemonic: List<String>): Boolean {
        if (mnemonic.size !in setOf(12, 24)) return false
        val idx = mnemonic.map { index[it] ?: return false }
        val bits = idx.flatMap { v -> (10 downTo 0).map { (v shr it) and 1 == 1 } }
        val entBits = bits.size * 32 / 33
        val entropy = ByteArray(entBits / 8) { i -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or (if (bits[i * 8 + k]) 1 else 0) }.toByte() }
        return fromEntropy(entropy) == mnemonic
    }

    companion object {
        fun seed(mnemonic: List<String>, passphrase: String = ""): ByteArray {
            val spec = PBEKeySpec(mnemonic.joinToString(" ").toCharArray(), ("mnemonic$passphrase").toByteArray(), 2048, 512)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        }
    }
}

/** SLIP-0010 для ed25519: лише загартовані кроки, як у всіх гаманців Solana. */
object Slip10 {
    const val SOLANA_MAIN = "m/44'/501'/0'/0'"
    const val SOLANA_CASH = "m/44'/501'/1'/0'"

    fun derive(seed: ByteArray, path: String): ByteArray {
        var (key, chain) = hmac("ed25519 seed".toByteArray(), seed).let { it.copyOfRange(0, 32) to it.copyOfRange(32, 64) }
        path.removePrefix("m/").split('/').filter { it.isNotEmpty() }.forEach { part ->
            require(part.endsWith("'")) { "ed25519 needs hardened path" }
            val i = part.dropLast(1).toLong() + 0x80000000L
            val data = byteArrayOf(0) + key + byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())
            val out = hmac(chain, data)
            key = out.copyOfRange(0, 32)
            chain = out.copyOfRange(32, 64)
        }
        return key
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(key, "HmacSHA512")) }.doFinal(data)
}

class Ed25519Keypair(privateSeed: ByteArray) {
    private val priv = Ed25519PrivateKeyParameters(privateSeed, 0)
    val publicKey: SolanaKey = SolanaKey.of(priv.generatePublicKey().encoded)!!

    fun sign(message: ByteArray): ByteArray =
        Ed25519Signer().apply { init(true, priv); update(message, 0, message.size) }.generateSignature()
}
