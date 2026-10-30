package local.phantom.companion.core.solana

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

enum class BillMode { SPLIT, HOLD }

enum class BillStatus { OPEN, SETTLED, CANCELLED, HELD, RELEASED, REFUNDED, FORFEITED }

enum class BillRefusal {
    DEADLINE_PAST, DEADLINE_TOO_FAR, SHARE_COUNT, ZERO_AMOUNT, DUPLICATE_PAYER, CUTOFF, FORFEIT_IN_SPLIT
}

class BillRefused(val reason: BillRefusal) : IllegalArgumentException(reason.name)

data class BillShareInput(val payer: SolanaKey, val amount: Long)

data class BillShare(val payer: SolanaKey, val amount: Long, val paid: Boolean, val refunded: Boolean)

data class BillAccount(
    val payee: SolanaKey,
    val mint: SolanaKey,
    val billId: Long,
    val deadline: Long,
    val cutoff: Long,
    val forfeitAllowed: Boolean,
    val mode: BillMode,
    val status: BillStatus,
    val bump: Int,
    val shares: List<BillShare>,
)

object BillProgram {
    val DEVNET_ID = SolanaKey.of("BiLL111111111111111111111111111111111111111")

    const val MAX_SHARES = 16
    const val SPLIT_WINDOW_SECS = 30L * 86_400
    const val HOLD_WINDOW_SECS = 90L * 86_400

    private fun sha8(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).copyOf(8)

    internal fun ixDiscriminator(name: String): ByteArray = sha8("global:$name")

    internal val ACCOUNT_DISCRIMINATOR: ByteArray = sha8("account:Bill")

    fun billAddress(programId: SolanaKey, payee: SolanaKey, billId: Long): SolanaKey =
        SolanaKey.findProgramAddress(listOf("bill".toByteArray(), payee.bytes, SolanaTx.u64(billId)), programId).first

    fun vaultAddress(bill: SolanaKey, mint: SolanaKey): SolanaKey = Programs.associatedTokenAddress(bill, mint)

    fun validateCreate(
        mode: BillMode,
        now: Long,
        deadline: Long,
        cutoff: Long,
        forfeitAllowed: Boolean,
        shares: List<BillShareInput>,
    ): BillRefusal? {
        if (deadline <= now) return BillRefusal.DEADLINE_PAST
        val window = if (mode == BillMode.SPLIT) SPLIT_WINDOW_SECS else HOLD_WINDOW_SECS
        if (deadline > now + window) return BillRefusal.DEADLINE_TOO_FAR
        when (mode) {
            BillMode.SPLIT -> {
                if (shares.isEmpty() || shares.size > MAX_SHARES) return BillRefusal.SHARE_COUNT
                if (cutoff != 0L) return BillRefusal.CUTOFF
                if (forfeitAllowed) return BillRefusal.FORFEIT_IN_SPLIT
            }
            BillMode.HOLD -> {
                if (shares.size != 1) return BillRefusal.SHARE_COUNT
                if (cutoff <= now || cutoff > deadline) return BillRefusal.CUTOFF
            }
        }
        if (shares.any { it.amount <= 0L }) return BillRefusal.ZERO_AMOUNT
        if (shares.map { it.payer }.toSet().size != shares.size) return BillRefusal.DUPLICATE_PAYER
        return null
    }

    fun createBill(
        programId: SolanaKey,
        payee: SolanaKey,
        mint: SolanaKey,
        billId: Long,
        now: Long,
        deadline: Long,
        shares: List<BillShareInput>,
        mode: BillMode,
        cutoff: Long = 0,
        forfeitAllowed: Boolean = false,
    ): Instruction {
        validateCreate(mode, now, deadline, cutoff, forfeitAllowed, shares)?.let { throw BillRefused(it) }
        val bill = billAddress(programId, payee, billId)
        val out = ByteArrayOutputStream()
        out.write(ixDiscriminator("create_bill"))
        out.write(SolanaTx.u64(billId))
        out.write(SolanaTx.u64(deadline))
        out.write(SolanaTx.u32(shares.size))
        shares.forEach { out.write(it.payer.bytes); out.write(SolanaTx.u64(it.amount)) }
        out.write(if (mode == BillMode.SPLIT) 0 else 1)
        out.write(SolanaTx.u64(cutoff))
        out.write(if (forfeitAllowed) 1 else 0)
        return Instruction(
            programId,
            listOf(
                AccountMeta(payee, signer = true, writable = true),
                AccountMeta(bill, signer = false, writable = true),
                AccountMeta(mint, signer = false, writable = false),
                AccountMeta(vaultAddress(bill, mint), signer = false, writable = true),
                AccountMeta(Programs.TOKEN, signer = false, writable = false),
                AccountMeta(Programs.ASSOCIATED_TOKEN, signer = false, writable = false),
                AccountMeta(Programs.SYSTEM, signer = false, writable = false),
            ),
            out.toByteArray(),
        )
    }

    fun payShare(
        programId: SolanaKey,
        bill: SolanaKey,
        mint: SolanaKey,
        payer: SolanaKey,
        payerToken: SolanaKey,
        payeeToken: SolanaKey,
        index: Int,
        expectedAmount: Long,
    ) = Instruction(
        programId,
        listOf(
            AccountMeta(payer, signer = true, writable = false),
            AccountMeta(bill, signer = false, writable = true),
            AccountMeta(vaultAddress(bill, mint), signer = false, writable = true),
            AccountMeta(payerToken, signer = false, writable = true),
            AccountMeta(payeeToken, signer = false, writable = true),
            AccountMeta(Programs.TOKEN, signer = false, writable = false),
        ),
        ixDiscriminator("pay_share") + byteArrayOf(index.toByte()) + SolanaTx.u64(expectedAmount),
    )

    fun cancel(programId: SolanaKey, bill: SolanaKey, caller: SolanaKey) = Instruction(
        programId,
        listOf(
            AccountMeta(caller, signer = true, writable = false),
            AccountMeta(bill, signer = false, writable = true),
        ),
        ixDiscriminator("cancel"),
    )

    fun refundShare(
        programId: SolanaKey,
        bill: SolanaKey,
        mint: SolanaKey,
        caller: SolanaKey,
        payerToken: SolanaKey,
        index: Int,
    ) = tokenOut(programId, bill, mint, caller, payerToken, "refund_share", byteArrayOf(index.toByte()))

    fun release(programId: SolanaKey, bill: SolanaKey, mint: SolanaKey, caller: SolanaKey, payeeToken: SolanaKey) =
        tokenOut(programId, bill, mint, caller, payeeToken, "release")

    fun refundHold(programId: SolanaKey, bill: SolanaKey, mint: SolanaKey, caller: SolanaKey, payerToken: SolanaKey) =
        tokenOut(programId, bill, mint, caller, payerToken, "refund_hold")

    fun forfeit(programId: SolanaKey, bill: SolanaKey, mint: SolanaKey, payee: SolanaKey, payeeToken: SolanaKey) =
        tokenOut(programId, bill, mint, payee, payeeToken, "forfeit")

    fun closeBill(programId: SolanaKey, bill: SolanaKey, mint: SolanaKey, payee: SolanaKey, payeeToken: SolanaKey) =
        tokenOut(programId, bill, mint, payee, payeeToken, "close_bill", callerWritable = true)

    private fun tokenOut(
        programId: SolanaKey,
        bill: SolanaKey,
        mint: SolanaKey,
        caller: SolanaKey,
        destination: SolanaKey,
        name: String,
        args: ByteArray = ByteArray(0),
        callerWritable: Boolean = false,
    ) = Instruction(
        programId,
        listOf(
            AccountMeta(caller, signer = true, writable = callerWritable),
            AccountMeta(bill, signer = false, writable = true),
            AccountMeta(vaultAddress(bill, mint), signer = false, writable = true),
            AccountMeta(destination, signer = false, writable = true),
            AccountMeta(Programs.TOKEN, signer = false, writable = false),
        ),
        ixDiscriminator(name) + args,
    )

    fun decodeBill(bytes: ByteArray): BillAccount? {
        if (bytes.size < 8 + 32 + 32 + 8 + 8 + 8 + 4 + 4) return null
        if (!bytes.copyOf(8).contentEquals(ACCOUNT_DISCRIMINATOR)) return null
        var at = 8
        fun key(): SolanaKey { val k = SolanaKey.of(bytes.copyOfRange(at, at + 32))!!; at += 32; return k }
        fun i64(): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (bytes[at + i].toLong() and 0xff); at += 8; return v }
        fun u8(): Int = bytes[at++].toInt() and 0xff
        val payee = key()
        val mint = key()
        val billId = i64()
        val deadline = i64()
        val cutoff = i64()
        val forfeit = u8() != 0
        val mode = BillMode.entries.getOrNull(u8()) ?: return null
        val status = BillStatus.entries.getOrNull(u8()) ?: return null
        val bump = u8()
        var n = 0
        for (i in 3 downTo 0) n = (n shl 8) or (bytes[at + i].toInt() and 0xff)
        at += 4
        if (n < 0 || n > MAX_SHARES || bytes.size < at + n * 42) return null
        val shares = List(n) {
            BillShare(key(), i64(), u8() != 0, u8() != 0)
        }
        return BillAccount(payee, mint, billId, deadline, cutoff, forfeit, mode, status, bump, shares)
    }
}
