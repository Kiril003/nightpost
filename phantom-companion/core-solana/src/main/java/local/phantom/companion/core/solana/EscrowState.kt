package local.phantom.companion.core.solana

enum class EscrowPhase { COLLECTING, LOCKED, RELEASED, REFUNDING, REFUNDED, KEPT, EXPIRED }

data class EscrowState(
    val phase: EscrowPhase,
    val paid: Int,
    val total: Int,
    val lockedUnits: Long,
) {
    companion object {
        fun of(bill: BillAccount, now: Long): EscrowState {
            val paid = bill.shares.count { it.paid }
            val locked = bill.shares.filter { it.paid && !it.refunded }.sumOf { it.amount }
            val late = now > bill.deadline
            val phase = when (bill.status) {
                BillStatus.OPEN -> if (late) EscrowPhase.EXPIRED else EscrowPhase.COLLECTING
                BillStatus.HELD -> if (late) EscrowPhase.EXPIRED else EscrowPhase.LOCKED
                BillStatus.SETTLED, BillStatus.RELEASED -> EscrowPhase.RELEASED
                BillStatus.CANCELLED -> if (locked > 0) EscrowPhase.REFUNDING else EscrowPhase.REFUNDED
                BillStatus.REFUNDED -> EscrowPhase.REFUNDED
                BillStatus.FORFEITED -> EscrowPhase.KEPT
            }
            val gone = phase == EscrowPhase.RELEASED || phase == EscrowPhase.KEPT || phase == EscrowPhase.REFUNDED
            return EscrowState(phase, paid, bill.shares.size, if (gone) 0L else locked)
        }
    }
}
