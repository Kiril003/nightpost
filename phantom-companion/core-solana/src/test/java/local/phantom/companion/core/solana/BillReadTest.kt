package local.phantom.companion.core.solana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillReadTest {
    private fun k(b: Int) = SolanaKey.of(ByteArray(32) { b.toByte() })!!
    private val program = BillProgram.DEVNET_ID

    private fun account(status: Int, paid: List<Boolean>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(BillProgram.ACCOUNT_DISCRIMINATOR)
        out.write(k(1).bytes); out.write(k(4).bytes)
        out.write(SolanaTx.u64(7)); out.write(SolanaTx.u64(900)); out.write(SolanaTx.u64(0))
        out.write(0); out.write(0); out.write(status); out.write(254)
        out.write(SolanaTx.u32(paid.size))
        paid.forEachIndexed { i, p -> out.write(k(10 + i).bytes); out.write(SolanaTx.u64(100)); out.write(if (p) 1 else 0); out.write(0) }
        return out.toByteArray()
    }

    private fun reply(bytes: ByteArray?, owner: SolanaKey = program, encoding: String = "base64") = SolanaRpc.JSON.parseToJsonElement(
        if (bytes == null) """{"context":{"slot":1},"value":null}"""
        else """{"context":{"slot":1},"value":{"owner":"${owner.base58}","lamports":1,"executable":false,"data":["${java.util.Base64.getEncoder().encodeToString(bytes)}","$encoding"]}}""",
    )

    @Test fun openBillCountsPaidShares() {
        assertEquals(BillView(BillStatus.OPEN, 1, 3, 900), BillRead.parse(reply(account(0, listOf(true, false, false))), program))
    }

    @Test fun settledBillReadsSettled() {
        assertEquals(BillStatus.SETTLED, BillRead.parse(reply(account(1, listOf(true, true))), program)?.status)
    }

    @Test fun missingAccountIsNull() {
        assertNull(BillRead.parse(reply(null), program))
    }

    @Test fun foreignOwnerIsNull() {
        assertNull(BillRead.parse(reply(account(0, listOf(true)), owner = k(9)), program))
    }

    @Test fun foreignBytesAreNull() {
        assertNull(BillRead.parse(reply(ByteArray(40)), program))
        assertNull(BillRead.parse(reply(account(0, listOf(true)), encoding = "base58"), program))
    }
}
