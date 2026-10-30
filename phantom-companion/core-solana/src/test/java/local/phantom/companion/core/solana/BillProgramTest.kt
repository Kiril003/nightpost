package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun k(b: Int) = SolanaKey.of(ByteArray(32) { b.toByte() })!!
private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

class BillProgramTest {
    private val pid = BillProgram.DEVNET_ID
    private val payee = k(1)
    private val payer = k(2)
    private val payer2 = k(3)
    private val mint = k(4)
    private val ata = k(5)
    private val now = 1_000_000L
    private val bill = BillProgram.billAddress(pid, payee, 7)
    private val vault = BillProgram.vaultAddress(bill, mint)

    private fun flags(ix: Instruction) = ix.accounts.map { (if (it.signer) "s" else "-") + (if (it.writable) "w" else "-") }

    @Test fun placeholderIdParses() {
        assertEquals("BiLL111111111111111111111111111111111111111", pid.base58)
    }

    @Test fun discriminators() {
        assertEquals("1cf1b4a16dffdc2c", BillProgram.ixDiscriminator("create_bill").hex())
        assertEquals("5f0efffe39c0e7ee", BillProgram.ixDiscriminator("pay_share").hex())
        assertEquals("e8dbdf29dbecdcbe", BillProgram.ixDiscriminator("cancel").hex())
        assertEquals("4865f73719ffbdab", BillProgram.ixDiscriminator("refund_share").hex())
        assertEquals("fdf90fce1c7fc1f1", BillProgram.ixDiscriminator("release").hex())
        assertEquals("97e65f71d54ef191", BillProgram.ixDiscriminator("refund_hold").hex())
        assertEquals("509aed9ef4c69a09", BillProgram.ixDiscriminator("forfeit").hex())
        assertEquals("ed6c9743f953f7fa", BillProgram.ixDiscriminator("close_bill").hex())
        assertEquals("464cfbafd4e3d4b3", BillProgram.ACCOUNT_DISCRIMINATOR.hex())
    }

    @Test fun pdaMatchesManualDerivation() {
        val expected = SolanaKey.findProgramAddress(
            listOf("bill".toByteArray(), payee.bytes, hex("0700000000000000")),
            pid,
        ).first
        assertEquals(expected, bill)
        assertEquals(Programs.associatedTokenAddress(bill, mint), vault)
        assertTrue(BillProgram.billAddress(pid, payee, 8) != bill)
    }

    @Test fun createBillSplitBytesAndAccounts() {
        val ix = BillProgram.createBill(
            pid, payee, mint, 7, now, now + 100,
            listOf(BillShareInput(payer, 5), BillShareInput(payer2, 6)), BillMode.SPLIT,
        )
        val want = "1cf1b4a16dffdc2c" + "0700000000000000" + SolanaTx.u64(now + 100).hex() + "02000000" +
            "02".repeat(32) + "0500000000000000" + "03".repeat(32) + "0600000000000000" + "00" + "0000000000000000" + "00"
        assertEquals(want, ix.data.hex())
        assertEquals(pid, ix.program)
        assertEquals(listOf(payee, bill, mint, vault, Programs.TOKEN, Programs.ASSOCIATED_TOKEN, Programs.SYSTEM), ix.accounts.map { it.key })
        assertEquals(listOf("sw", "-w", "--", "-w", "--", "--", "--"), flags(ix))
    }

    @Test fun createBillHoldBytes() {
        val ix = BillProgram.createBill(
            pid, payee, mint, 7, now, 5_000_000, listOf(BillShareInput(payer, 9)), BillMode.HOLD, cutoff = 2_000_000, forfeitAllowed = true,
        )
        val tail = ix.data.copyOfRange(ix.data.size - 10, ix.data.size).hex()
        assertEquals("01" + "80841e0000000000" + "01", tail)
        assertEquals(8 + 8 + 8 + 4 + 40 + 1 + 8 + 1, ix.data.size)
    }

    @Test fun payShare() {
        val ix = BillProgram.payShare(pid, bill, mint, payer, k(6), k(7), 3, 258)
        assertEquals("5f0efffe39c0e7ee" + "03" + "0201000000000000", ix.data.hex())
        assertEquals(listOf(payer, bill, vault, k(6), k(7), Programs.TOKEN), ix.accounts.map { it.key })
        assertEquals(listOf("s-", "-w", "-w", "-w", "-w", "--"), flags(ix))
    }

    @Test fun cancel() {
        val ix = BillProgram.cancel(pid, bill, payer)
        assertEquals("e8dbdf29dbecdcbe", ix.data.hex())
        assertEquals(listOf(payer, bill), ix.accounts.map { it.key })
        assertEquals(listOf("s-", "-w"), flags(ix))
    }

    private fun tokenShape(ix: Instruction, signer: SolanaKey, dest: SolanaKey, signerFlags: String) {
        assertEquals(listOf(signer, bill, vault, dest, Programs.TOKEN), ix.accounts.map { it.key })
        assertEquals(listOf(signerFlags, "-w", "-w", "-w", "--"), flags(ix))
    }

    @Test fun refundShare() {
        val ix = BillProgram.refundShare(pid, bill, mint, payee, ata, 2)
        assertEquals("4865f73719ffbdab" + "02", ix.data.hex())
        tokenShape(ix, payee, ata, "s-")
    }

    @Test fun release() {
        val ix = BillProgram.release(pid, bill, mint, payee, ata)
        assertEquals("fdf90fce1c7fc1f1", ix.data.hex())
        tokenShape(ix, payee, ata, "s-")
    }

    @Test fun refundHold() {
        val ix = BillProgram.refundHold(pid, bill, mint, payer, ata)
        assertEquals("97e65f71d54ef191", ix.data.hex())
        tokenShape(ix, payer, ata, "s-")
    }

    @Test fun forfeit() {
        val ix = BillProgram.forfeit(pid, bill, mint, payee, ata)
        assertEquals("509aed9ef4c69a09", ix.data.hex())
        tokenShape(ix, payee, ata, "s-")
    }

    @Test fun closeBillPayeeIsWritable() {
        val ix = BillProgram.closeBill(pid, bill, mint, payee, ata)
        assertEquals("ed6c9743f953f7fa", ix.data.hex())
        tokenShape(ix, payee, ata, "sw")
    }

    private fun account(n: Int, status: Int = 3, mode: Int = 1): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(BillProgram.ACCOUNT_DISCRIMINATOR)
        out.write(payee.bytes); out.write(mint.bytes)
        out.write(SolanaTx.u64(7)); out.write(SolanaTx.u64(900)); out.write(SolanaTx.u64(-5))
        out.write(1); out.write(mode); out.write(status); out.write(254)
        out.write(SolanaTx.u32(n))
        repeat(n) { i ->
            out.write(k(10 + i).bytes); out.write(SolanaTx.u64(100L + i)); out.write(i % 2); out.write(1 - i % 2)
        }
        return out.toByteArray()
    }

    @Test fun decodeRoundTrip() {
        val b = BillProgram.decodeBill(account(2))!!
        assertEquals(payee, b.payee)
        assertEquals(mint, b.mint)
        assertEquals(7L, b.billId)
        assertEquals(900L, b.deadline)
        assertEquals(-5L, b.cutoff)
        assertTrue(b.forfeitAllowed)
        assertEquals(BillMode.HOLD, b.mode)
        assertEquals(BillStatus.HELD, b.status)
        assertEquals(254, b.bump)
        assertEquals(
            listOf(BillShare(k(10), 100, paid = false, refunded = true), BillShare(k(11), 101, paid = true, refunded = false)),
            b.shares,
        )
    }

    @Test fun decodeRejectsBadInput() {
        val good = account(1)
        assertNotNull(BillProgram.decodeBill(good))
        assertNull(BillProgram.decodeBill(good.copyOf(good.size - 1)))
        assertNull(BillProgram.decodeBill(good.copyOf(20)))
        assertNull(BillProgram.decodeBill(good.copyOf().also { it[0] = (it[0] + 1).toByte() }))
        assertNull(BillProgram.decodeBill(account(1, status = 9)))
        assertNull(BillProgram.decodeBill(account(1, mode = 2)))
        assertNull(BillProgram.decodeBill(account(17)))
    }

    private fun split(
        deadline: Long = now + 10,
        shares: List<BillShareInput> = listOf(BillShareInput(payer, 1)),
        cutoff: Long = 0,
        forfeit: Boolean = false,
    ) = BillProgram.validateCreate(BillMode.SPLIT, now, deadline, cutoff, forfeit, shares)

    private fun hold(
        deadline: Long = now + 100,
        cutoff: Long = now + 50,
        shares: List<BillShareInput> = listOf(BillShareInput(payer, 1)),
    ) = BillProgram.validateCreate(BillMode.HOLD, now, deadline, cutoff, true, shares)

    @Test fun splitGuards() {
        assertNull(split())
        assertNull(split(deadline = now + BillProgram.SPLIT_WINDOW_SECS))
        assertEquals(BillRefusal.DEADLINE_TOO_FAR, split(deadline = now + BillProgram.SPLIT_WINDOW_SECS + 1))
        assertEquals(BillRefusal.DEADLINE_PAST, split(deadline = now))
        assertEquals(BillRefusal.SHARE_COUNT, split(shares = emptyList()))
        val sixteen = List(16) { BillShareInput(k(20 + it), 1) }
        assertNull(split(shares = sixteen))
        assertEquals(BillRefusal.SHARE_COUNT, split(shares = sixteen + BillShareInput(k(99), 1)))
        assertEquals(BillRefusal.ZERO_AMOUNT, split(shares = listOf(BillShareInput(payer, 0))))
        assertEquals(BillRefusal.DUPLICATE_PAYER, split(shares = listOf(BillShareInput(payer, 1), BillShareInput(payer, 2))))
        assertEquals(BillRefusal.CUTOFF, split(cutoff = 5))
        assertEquals(BillRefusal.FORFEIT_IN_SPLIT, split(forfeit = true))
    }

    @Test fun holdGuards() {
        assertNull(hold())
        assertNull(hold(deadline = now + BillProgram.HOLD_WINDOW_SECS, cutoff = now + BillProgram.HOLD_WINDOW_SECS))
        assertEquals(BillRefusal.DEADLINE_TOO_FAR, hold(deadline = now + BillProgram.HOLD_WINDOW_SECS + 1))
        assertNull(hold(deadline = now + 100, cutoff = now + 100))
        assertEquals(BillRefusal.CUTOFF, hold(cutoff = now + 101))
        assertEquals(BillRefusal.CUTOFF, hold(cutoff = now))
        assertEquals(BillRefusal.SHARE_COUNT, hold(shares = emptyList()))
        assertEquals(BillRefusal.SHARE_COUNT, hold(shares = listOf(BillShareInput(payer, 1), BillShareInput(payer2, 1))))
        assertEquals(BillRefusal.ZERO_AMOUNT, hold(shares = listOf(BillShareInput(payer, 0))))
        assertEquals(BillRefusal.DEADLINE_PAST, hold(deadline = now - 1))
    }

    @Test fun builderRefusesBeforeEncoding() {
        val e = assertThrows(BillRefused::class.java) {
            BillProgram.createBill(pid, payee, mint, 1, now, now + BillProgram.SPLIT_WINDOW_SECS + 1, listOf(BillShareInput(payer, 1)), BillMode.SPLIT)
        }
        assertEquals(BillRefusal.DEADLINE_TOO_FAR, e.reason)
        assertFalse(e.message.isNullOrEmpty())
    }
}
