package local.phantom.companion.core.solana

import local.phantom.companion.core.solana.SolanaTrouble.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class SolanaTroubleTest {

    @Test
    fun typicalNodeRefusalsBecomeWhatThePersonCanActOn() {
        val cases = mapOf(
            "Transaction simulation failed: Attempt to debit an account but found no record of a prior credit." to Kind.NO_FEE,
            "Transaction results in an account (0) with insufficient funds for rent" to Kind.NO_FEE,
            "Transaction simulation failed: Error processing Instruction 2: custom program error: 0x1" to Kind.NO_FUNDS,
            "insufficient lamports 1000, need 2000" to Kind.NO_FUNDS,
            "Transaction simulation failed: Blockhash not found" to Kind.EXPIRED,
            "Unable to resolve host \"api.devnet.solana.com\"" to Kind.OFFLINE,
            "HTTP 503" to Kind.OFFLINE,
            "rpc" to Kind.OTHER,
        )
        cases.forEach { (raw, kind) -> assertEquals(raw, kind, SolanaTrouble.classify(raw)) }
    }
}
