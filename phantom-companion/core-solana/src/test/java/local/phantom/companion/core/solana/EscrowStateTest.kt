package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Test

class EscrowStateTest {

    private val payee = SolanaKey.of(ByteArray(32) { 1 })!!
    private val mint = SolanaKey.of(ByteArray(32) { 2 })!!
    private val a = SolanaKey.of(ByteArray(32) { 3 })!!
    private val b = SolanaKey.of(ByteArray(32) { 4 })!!
    private val deadline = 1_000L

    private fun bill(mode: BillMode, status: BillStatus, vararg shares: BillShare) =
        BillAccount(payee, mint, 7, deadline, 0, false, mode, status, 255, shares.toList())

    private fun share(who: SolanaKey, amount: Long, paid: Boolean = false, refunded: Boolean = false) =
        BillShare(who, amount, paid, refunded)

    @Test
    fun `an open split counts who paid and what sits in the vault`() {
        val s = EscrowState.of(bill(BillMode.SPLIT, BillStatus.OPEN, share(a, 300, paid = true), share(b, 200)), 500)
        assertEquals(EscrowState(EscrowPhase.COLLECTING, 1, 2, 300), s)
    }

    @Test
    fun `an open split past its deadline is expired, the paid part still locked`() {
        val s = EscrowState.of(bill(BillMode.SPLIT, BillStatus.OPEN, share(a, 300, paid = true), share(b, 200)), deadline + 1)
        assertEquals(EscrowState(EscrowPhase.EXPIRED, 1, 2, 300), s)
    }

    @Test
    fun `a held deposit is locked until the deadline, then expired`() {
        val held = bill(BillMode.HOLD, BillStatus.HELD, share(a, 900, paid = true))
        assertEquals(EscrowPhase.LOCKED, EscrowState.of(held, deadline).phase)
        assertEquals(EscrowState(EscrowPhase.EXPIRED, 1, 1, 900), EscrowState.of(held, deadline + 1))
    }

    @Test
    fun `money that left the vault is not shown as locked`() {
        val settled = bill(BillMode.SPLIT, BillStatus.SETTLED, share(a, 300, paid = true), share(b, 200, paid = true))
        assertEquals(EscrowState(EscrowPhase.RELEASED, 2, 2, 0), EscrowState.of(settled, 10))
        val released = bill(BillMode.HOLD, BillStatus.RELEASED, share(a, 900, paid = true))
        assertEquals(EscrowState(EscrowPhase.RELEASED, 1, 1, 0), EscrowState.of(released, 10))
        val kept = bill(BillMode.HOLD, BillStatus.FORFEITED, share(a, 900, paid = true))
        assertEquals(EscrowState(EscrowPhase.KEPT, 1, 1, 0), EscrowState.of(kept, 10))
        val back = bill(BillMode.HOLD, BillStatus.REFUNDED, share(a, 900, paid = true))
        assertEquals(EscrowState(EscrowPhase.REFUNDED, 1, 1, 0), EscrowState.of(back, 10))
    }

    @Test
    fun `a cancelled split is refunding until every paid share is back`() {
        val half = bill(BillMode.SPLIT, BillStatus.CANCELLED, share(a, 300, paid = true, refunded = true), share(b, 200, paid = true))
        assertEquals(EscrowState(EscrowPhase.REFUNDING, 2, 2, 200), EscrowState.of(half, 10))
        val done = bill(BillMode.SPLIT, BillStatus.CANCELLED, share(a, 300, paid = true, refunded = true), share(b, 200))
        assertEquals(EscrowState(EscrowPhase.REFUNDED, 1, 2, 0), EscrowState.of(done, 10))
    }
}
