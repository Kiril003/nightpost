package local.phantom.companion.core.solana

import java.math.BigInteger
import java.security.MessageDigest

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEX = IntArray(128) { -1 }.also { idx -> ALPHABET.forEachIndexed { i, c -> idx[c.code] = i } }
    private val BASE = BigInteger.valueOf(58)

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val zeros = bytes.takeWhile { it == 0.toByte() }.size
        var n = BigInteger(1, bytes)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(BASE)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        repeat(zeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    fun decode(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        var n = BigInteger.ZERO
        for (c in text) {
            val d = if (c.code < 128) INDEX[c.code] else -1
            if (d < 0) return null
            n = n.multiply(BASE).add(BigInteger.valueOf(d.toLong()))
        }
        val zeros = text.takeWhile { it == '1' }.length
        val body = n.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val core = if (n.signum() == 0) ByteArray(0) else body
        return ByteArray(zeros) + core
    }
}

class SolanaKey private constructor(private val raw: ByteArray) {

    val bytes: ByteArray get() = raw.copyOf()
    val base58: String by lazy { Base58.encode(raw) }

    override fun equals(other: Any?): Boolean = other is SolanaKey && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
    override fun toString(): String = base58

    fun short(): String = base58.take(4) + "…" + base58.takeLast(4)

    companion object {
        fun of(bytes: ByteArray): SolanaKey? = if (bytes.size == 32) SolanaKey(bytes.copyOf()) else null

        fun parse(text: String): SolanaKey? = Base58.decode(text.trim())?.let(::of)

        fun of(text: String): SolanaKey = requireNotNull(parse(text)) { "not a solana key: $text" }

        fun createProgramAddress(seeds: List<ByteArray>, program: SolanaKey): SolanaKey? {
            val sha = MessageDigest.getInstance("SHA-256")
            seeds.forEach { require(it.size <= 32); sha.update(it) }
            sha.update(program.raw)
            sha.update("ProgramDerivedAddress".toByteArray())
            val hash = sha.digest()
            return if (Ed25519Curve.isOnCurve(hash)) null else SolanaKey(hash)
        }

        fun findProgramAddress(seeds: List<ByteArray>, program: SolanaKey): Pair<SolanaKey, Int> {
            for (bump in 255 downTo 0) {
                createProgramAddress(seeds + byteArrayOf(bump.toByte()), program)?.let { return it to bump }
            }
            error("no program address")
        }
    }
}

internal object Ed25519Curve {
    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val HALF = P.subtract(BigInteger.ONE).shiftRight(1)

    // Та сама відповідь, що й curve25519-dalek decompress(): y береться за модулем, знак x не перевіряється.
    fun isOnCurve(compressed: ByteArray): Boolean {
        require(compressed.size == 32)
        val le = compressed.copyOf()
        le[31] = (le[31].toInt() and 0x7f).toByte()
        val y = BigInteger(1, le.reversedArray()).mod(P)
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        if (x2.signum() == 0) return true
        return x2.modPow(HALF, P) == BigInteger.ONE
    }
}
