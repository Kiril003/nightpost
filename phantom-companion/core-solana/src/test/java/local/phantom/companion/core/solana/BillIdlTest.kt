package local.phantom.companion.core.solana

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class BillIdlTest {

    private val idl: JsonObject = run {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "solana-programs/idl/nightpost_bill.json") }
            .first { it.isFile }
        SolanaRpc.JSON.parseToJsonElement(file.readText()).jsonObject
    }

    private fun key(b: Int) = SolanaKey.of(ByteArray(32) { b.toByte() })!!
    private val pid = BillProgram.DEVNET_ID
    private val payee = key(1)
    private val payer = key(2)
    private val mint = key(4)
    private val dest = key(5)
    private val payeeToken = key(6)
    private val bill = BillProgram.billAddress(pid, payee, 7)
    private val now = 1_000_000L

    private val built: Map<String, Instruction> = mapOf(
        "create_bill" to BillProgram.createBill(
            pid, payee, mint, 7, now, now + 3_600, listOf(BillShareInput(payer, 500)), BillMode.SPLIT,
        ),
        "pay_share" to BillProgram.payShare(pid, bill, mint, payer, dest, payeeToken, 0, 500),
        "cancel" to BillProgram.cancel(pid, bill, payer),
        "refund_share" to BillProgram.refundShare(pid, bill, mint, payer, dest, 0),
        "release" to BillProgram.release(pid, bill, mint, payer, dest),
        "refund_hold" to BillProgram.refundHold(pid, bill, mint, payer, dest),
        "forfeit" to BillProgram.forfeit(pid, bill, mint, payee, dest),
        "close_bill" to BillProgram.closeBill(pid, bill, mint, payee, dest),
    )

    private fun keyFor(ix: String, role: String): SolanaKey = when (role) {
        "payee" -> payee
        "payer", "caller" -> payer
        "mint" -> mint
        "bill" -> bill
        "vault" -> BillProgram.vaultAddress(bill, mint)
        "payer_token_account" -> dest
        "payee_token_account" -> if (ix == "pay_share") payeeToken else dest
        else -> error("$ix: idl account $role has no expected key")
    }

    private fun bytes(a: JsonArray) = a.map { it.jsonPrimitive.int.toByte() }.toByteArray()
    private fun JsonObject.flag(name: String) = this[name]?.jsonPrimitive?.boolean ?: false
    private fun names(a: JsonArray) = a.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    private fun type(name: String) = idl["types"]!!.jsonArray.map { it.jsonObject }
        .first { it["name"]!!.jsonPrimitive.content == name }["type"]!!.jsonObject

    @Test
    fun `the idl is the program the app talks to`() {
        assertEquals(pid.base58, idl["address"]!!.jsonPrimitive.content)
        assertEquals(built.keys.sorted(), names(idl["instructions"]!!.jsonArray).sorted())
    }

    @Test
    fun `every instruction has the idl discriminator and its account order and flags`() {
        for (ix in idl["instructions"]!!.jsonArray.map { it.jsonObject }) {
            val name = ix["name"]!!.jsonPrimitive.content
            val ours = built.getValue(name)
            assertEquals(name, bytes(ix["discriminator"]!!.jsonArray).toList(), ours.data.copyOf(8).toList())
            val accounts = ix["accounts"]!!.jsonArray.map { it.jsonObject }
            assertEquals(name, accounts.map { it.flag("signer") to it.flag("writable") },
                ours.accounts.map { it.signer to it.writable })
            accounts.forEachIndexed { i, a ->
                val role = a["name"]!!.jsonPrimitive.content
                val want = a["address"]?.jsonPrimitive?.content?.let { SolanaKey.of(it) } ?: keyFor(name, role)
                assertEquals("$name.$role", want, ours.accounts[i].key)
            }
        }
    }

    @Test
    fun `instruction args are written in idl order`() {
        val args = idl["instructions"]!!.jsonArray.map { it.jsonObject }
            .associate { it["name"]!!.jsonPrimitive.content to names(it["args"]!!.jsonArray) }
        assertEquals(listOf("bill_id", "deadline", "shares", "mode", "cutoff", "forfeit_allowed"), args["create_bill"])
        assertEquals(listOf("index", "expected_amount"), args["pay_share"])
        assertEquals(listOf("index"), args["refund_share"])
        listOf("cancel", "release", "refund_hold", "forfeit", "close_bill").forEach { assertEquals(it, emptyList<String>(), args[it]) }
    }

    @Test
    fun `the bill account decodes in idl field order with idl enum order`() {
        val account = idl["accounts"]!!.jsonArray.single().jsonObject
        assertEquals("Bill", account["name"]!!.jsonPrimitive.content)
        assertEquals(bytes(account["discriminator"]!!.jsonArray).toList(), BillProgram.ACCOUNT_DISCRIMINATOR.toList())
        assertEquals(
            listOf("payee", "mint", "bill_id", "deadline", "cutoff", "forfeit_allowed", "mode", "status", "bump", "shares"),
            names(type("Bill")["fields"]!!.jsonArray),
        )
        assertEquals(listOf("payer", "amount", "paid", "refunded"), names(type("Share")["fields"]!!.jsonArray))
        assertEquals(BillMode.entries.map { it.name }, names(type("BillMode")["variants"]!!.jsonArray).map { it.uppercase() })
        assertEquals(BillStatus.entries.map { it.name }, names(type("BillStatus")["variants"]!!.jsonArray).map { it.uppercase() })
    }
}
